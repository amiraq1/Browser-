package com.agentic.browser

import android.app.Application
import android.webkit.WebView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.agentic.browser.agent.AgentCommandParser
import com.agentic.browser.agent.BackendMode
import com.agentic.browser.agent.CommandExecutor
import com.agentic.browser.agent.ContextManager
import com.agentic.browser.agent.DuckDuckGoE2ERunner
import com.agentic.browser.agent.ExecutionResult
import com.agentic.browser.agent.LiteRtLmAgentManager
import com.agentic.browser.web.AgentBridge
import com.agentic.browser.web.AgenticWebViewFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class AgentBrowserViewModel(application: Application) : AndroidViewModel(application) {
    val bridge = AgentBridge()
    private val _status = MutableStateFlow("Agent idle")
    val status: StateFlow<String> = _status
    private val _statusLines = MutableStateFlow(listOf("Agent idle"))
    val statusLines: StateFlow<List<String>> = _statusLines
    private val _prompt = MutableStateFlow("")
    val prompt: StateFlow<String> = _prompt
    private var agent: LiteRtLmAgentManager? = null
    private var activeJob: Job? = null
    private val contextManager = ContextManager()

    fun updatePrompt(value: String) { _prompt.value = value.take(1000) }
    fun setStatus(value: String) {
        val line = value.trim().ifBlank { return }
        _status.value = line
        _statusLines.value = (_statusLines.value + line).takeLast(20)
    }

    fun initializeAgent(modelPath: String, backend: BackendMode = BackendMode.CPU) {
        if (agent != null) return
        contextManager.clearHistory()
        agent = LiteRtLmAgentManager(getApplication(), modelPath, backend)
        viewModelScope.launch {
            setStatus("Loading LiteRT-LM...")
            runCatching { agent?.initialize() }
                .onSuccess { setStatus("LiteRT-LM ready") }
                .onFailure { setStatus("Model load failed: ${it.message}") }
        }
    }

    fun runAgent(webView: WebView) {
        val userCommand = _prompt.value.trim()
        if (userCommand.isBlank()) { setStatus("Prompt empty"); return }
        val manager = agent ?: run { setStatus("Agent not initialized"); return }
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            runCatching {
                setStatus("Extracting DOM")
                AgenticWebViewFactory.injectDomSimplifier(webView)
                delay(250)
                val initialDom = bridge.domTree.value
                if (initialDom.isBlank()) {
                    val feedback = compactFeedback("Error: DOM is empty. Wait or refresh and use valid IDs from the current DOM.")
                    contextManager.addSystemFeedback(feedback)
                    setStatus("Error feedback added")
                    setStatus("Failed after retries: empty DOM")
                    return@launch
                }
                contextManager.updateCurrentDom(initialDom)
                val executor = CommandExecutor(webView, ::setStatus)
                val maxRetries = 2
                var retryCount = 0

                while (retryCount <= maxRetries) {
                    setStatus("Prompt built")
                    val prompt = contextManager.buildPrompt(userCommand, bridge.domTree.value)
                    setStatus("LLM thinking")

                    val rawResult = runCatching { manager.planWithPrompt(prompt).trim() }
                    if (rawResult.isFailure) {
                        val message = rawResult.exceptionOrNull()?.message?.take(160) ?: "inference failure"
                        val feedback = compactFeedback("Error: LiteRT-LM inference failed ($message). Return one valid JSON action.")
                        contextManager.addSystemFeedback(feedback)
                        setStatus("Error feedback added")
                        if (retryCount < maxRetries) {
                            retryCount++
                            setStatus("Retry count: $retryCount/$maxRetries")
                            continue
                        }
                        setStatus("Failed after retries: inference failure")
                        return@launch
                    }

                    val raw = rawResult.getOrThrow()
                    val parseResult = AgentCommandParser.parse(raw)
                    if (parseResult.isFailure) {
                        val parseErr = parseResult.exceptionOrNull()?.message?.take(180) ?: "invalid JSON"
                        val feedback = compactFeedback("Error: $parseErr. Use the strict JSON schema and valid DOM IDs only.")
                        contextManager.addInteraction(userCommand, raw, "parse_failed", feedback)
                        contextManager.addSystemFeedback(feedback)
                        setStatus("Error feedback added")
                        if (retryCount < maxRetries) {
                            retryCount++
                            setStatus("Retry count: $retryCount/$maxRetries")
                            continue
                        }
                        setStatus("Failed after retries: $parseErr")
                        return@launch
                    }

                    val command = parseResult.getOrThrow()
                    setStatus("JSON parsed: ${command.javaClass.simpleName}")
                    setStatus("JS executing")
                    when (val result = executor.execute(command)) {
                        is ExecutionResult.Success -> {
                            contextManager.addInteraction(userCommand, raw, "success")
                            delay(350)
                            AgenticWebViewFactory.injectDomSimplifier(webView)
                            delay(200)
                            contextManager.updateCurrentDom(bridge.domTree.value)
                            setStatus("Finished")
                            return@launch
                        }
                        is ExecutionResult.Done -> {
                            contextManager.addInteraction(userCommand, raw, "done:${result.reason.take(160)}")
                            setStatus("Finished: ${result.reason}")
                            return@launch
                        }
                        is ExecutionResult.ExtractedData -> {
                            contextManager.addInteraction(userCommand, raw, "extracted:${result.keyName.take(64)}")
                            setStatus("Extracted: ${result.keyName}")
                            AgenticWebViewFactory.injectDomSimplifier(webView)
                            delay(250)
                            contextManager.updateCurrentDom(bridge.domTree.value)
                            continue
                        }
                        is ExecutionResult.AwaitingHuman -> {
                            contextManager.addInteraction(userCommand, raw, "awaiting_human:${result.reason.take(160)}")
                            setStatus("Agent paused for human action: ${result.reason}")
                            return@launch
                        }
                        is ExecutionResult.Failed -> {
                            val reason = result.reason.take(180)
                            val feedback = compactFeedback("Error: $reason. Use only valid IDs from the current DOM.")
                            contextManager.addInteraction(userCommand, raw, "execution_failed:$reason", feedback)
                            contextManager.addSystemFeedback(feedback)
                            setStatus("Error feedback added")
                            if (retryCount < maxRetries) {
                                retryCount++
                                setStatus("Retry count: $retryCount/$maxRetries")
                                AgenticWebViewFactory.injectDomSimplifier(webView)
                                delay(250)
                                contextManager.updateCurrentDom(bridge.domTree.value)
                                continue
                            }
                            setStatus("Failed after retries: $reason")
                            return@launch
                        }
                    }
                }
            }.onFailure { setStatus("Agent error: ${it.message}") }
        }
    }

    fun runE2ETest(webView: WebView) {
        activeJob?.cancel()
        activeJob = viewModelScope.launch {
            val runner = DuckDuckGoE2ERunner(bridge, ::setStatus)
            runner.run(webView)
                .onSuccess { setStatus("DuckDuckGo E2E OK: ${it.take(140)}") }
                .onFailure { setStatus("DuckDuckGo E2E failed: ${it.message}") }
        }
    }

    private fun compactFeedback(message: String): String {
        return """{"system_feedback":${JSONObject.quote(message.trim().take(220))}}"""
    }

    override fun onCleared() { activeJob?.cancel(); agent?.close(); agent = null; super.onCleared() }
}
