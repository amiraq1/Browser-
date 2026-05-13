package com.agentic.browser

import android.app.Application
import android.content.Context
import android.net.Uri
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
import com.agentic.browser.memory.AgentMemoryDb
import com.agentic.browser.memory.AgentMemoryRepository
import com.agentic.browser.model.ModelDownloadState
import com.agentic.browser.model.ModelDownloader
import com.agentic.browser.model.ModelImportState
import com.agentic.browser.model.ModelRecoveryManager
import com.agentic.browser.web.AgentBridge
import com.agentic.browser.web.AgenticWebViewFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.system.measureTimeMillis

data class BrowserAgentUiState(
    val command: String = "",
    val terminalLines: List<String> = listOf("Agent idle"),
    val modelState: ModelDownloadState = ModelDownloadState.Idle,
    val isModelReady: Boolean = false,
    val modelPath: String? = null,
    val isFirstRunModelSetup: Boolean = true,
    val modelImportState: ModelImportState = ModelImportState.Idle,
    val isAgentBusy: Boolean = false,
    val isAwaitingHuman: Boolean = false,
    val humanFallbackReason: String? = null,
    val pausedUserTask: String? = null,
    val contextUsagePercent: Int = 0,
    val currentUrl: String? = null,
    val modelUrlInput: String = "",
    val updateState: UpdateCheckOutcome = UpdateCheckOutcome.Idle
)

class BrowserAgentViewModel(application: Application) : AndroidViewModel(application) {
    val bridge = AgentBridge()

    private val contextManager = ContextManager()
    private val memoryRepository = AgentMemoryRepository(AgentMemoryDb.getInstance(application).extractedFactDao())
    private val modelDownloader = ModelDownloader(application)
    private val modelRecoveryManager = ModelRecoveryManager(application)
    private val updateChecker: UpdateChecker = UpdateChecker()
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(BrowserAgentUiState())
    val uiState: StateFlow<BrowserAgentUiState> = _uiState.asStateFlow()

    private var activeJob: Job? = null
    private var agentManager: LiteRtLmAgentManager? = null
    private var loadedModelPath: String? = null
    private var latestMemoryContext: String? = null
    private var lastDomInjectEpochMs: Long = 0L

    init {
        viewModelScope.launch {
            modelDownloader.state.collectLatest { state ->
                _uiState.update { old ->
                    val nextPath = (state as? ModelDownloadState.Ready)?.modelPath ?: old.modelPath
                    old.copy(
                        modelState = state,
                        modelPath = nextPath
                    )
                }
                when (state) {
                    is ModelDownloadState.Downloading -> addLog("Model downloading: ${state.progressPercent}%")
                    is ModelDownloadState.Extracting -> addLog("Model validating/finalizing")
                    is ModelDownloadState.Ready -> {
                        addLog("Model ready: ${state.modelPath}")
                        persistModelPath(state.modelPath)
                        initializeAgentManager(state.modelPath)
                    }
                    is ModelDownloadState.Error -> addLog("Model error: ${state.message}")
                    ModelDownloadState.Idle -> Unit
                }
            }
        }

        viewModelScope.launch {
            modelRecoveryManager.state.collectLatest { importState ->
                _uiState.update { it.copy(modelImportState = importState) }
                when (importState) {
                    is ModelImportState.Importing -> addLog("Importing local model: ${importState.progressPercent}%")
                    ModelImportState.Validating -> addLog("Validating model")
                    is ModelImportState.Ready -> {
                        addLog("Model recovered: ${importState.modelPath}")
                        persistModelPath(importState.modelPath)
                        _uiState.update {
                            it.copy(
                                modelPath = importState.modelPath,
                                modelState = ModelDownloadState.Ready(importState.modelPath)
                            )
                        }
                        initializeAgentManager(importState.modelPath)
                    }
                    is ModelImportState.Error -> addLog("Import failed: ${importState.message}")
                    ModelImportState.Idle -> Unit
                }
            }
        }

        viewModelScope.launch {
            bridge.domTree.collectLatest { dom ->
                contextManager.updateCurrentDom(dom)
                refreshContextUsage()
            }
        }

        viewModelScope.launch {
            bridge.errors.collectLatest { error ->
                if (!error.isNullOrBlank()) addLog("DOM bridge error: $error")
            }
        }
    }

    fun updateCommand(text: String) {
        _uiState.update { it.copy(command = text.take(1000)) }
        refreshContextUsage()
    }

    fun updateModelUrl(text: String) {
        _uiState.update { it.copy(modelUrlInput = text.take(2048)) }
    }

    fun navigate(webView: WebView) {
        val cmd = _uiState.value.command.trim()
        if (cmd.isBlank()) {
            addLog("Navigation skipped: empty command")
            return
        }
        val factory = AgenticWebViewFactory(getApplication(), bridge, ::addLog)
        val target = factory.normalizeUrl(cmd)
        webView.loadUrl(target)
        _uiState.update { it.copy(currentUrl = target) }
        addLog("Navigate: $target")
    }

    fun executeAgent(webView: WebView) {
        val state = _uiState.value
        if (state.isAgentBusy) {
            addLog("Agent busy. Wait for current run to finish.")
            return
        }
        if (state.isAwaitingHuman) {
            addLog("Agent paused for human intervention. Tap Resume to continue.")
            return
        }

        val task = state.command.trim()
        if (task.isBlank()) {
            addLog("Waiting for command")
            return
        }

        val manager = agentManager
        if (manager == null || !state.isModelReady) {
            addLog("Model not ready. Download or select a model first.")
            return
        }

        activeJob = viewModelScope.launch {
            _uiState.update { it.copy(isAgentBusy = true) }
            runCatching {
                runAgentLoop(webView = webView, task = task, manager = manager)
            }.onFailure { error ->
                if (error is CancellationException) {
                    addLog("Inference cancelled")
                } else {
                    addLog("Failed after retries: ${error.message ?: "unknown"}")
                }
            }
            _uiState.update { it.copy(isAgentBusy = false) }
        }
    }

    fun resumeAfterHuman(webView: WebView) {
        val state = _uiState.value
        if (state.isAgentBusy) {
            addLog("Agent busy. Resume ignored.")
            return
        }
        if (!state.isAwaitingHuman) {
            addLog("Resume ignored: agent is not paused")
            return
        }

        val task = state.pausedUserTask?.trim().orEmpty().ifBlank { state.command.trim() }
        if (task.isBlank()) {
            addLog("Resume failed: no paused task available")
            _uiState.update { it.copy(isAwaitingHuman = false, humanFallbackReason = null, pausedUserTask = null) }
            return
        }

        val manager = agentManager
        if (manager == null || !state.isModelReady) {
            addLog("Model not ready. Download or select a model first.")
            return
        }

        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isAwaitingHuman = false,
                    humanFallbackReason = null,
                    pausedUserTask = null,
                    isAgentBusy = true
                )
            }

            runCatching {
                resumeDomUpdatesInternal(webView)
                rebuildDomSnapshot(webView, force = true)
                val feedback = compactFeedback("Human intervention complete. DOM updated. Proceed with original task.")
                contextManager.addSystemFeedback(feedback)
                addLog("↻ self-heal feedback added")
                runAgentLoop(webView = webView, task = task, manager = manager)
            }.onFailure { error ->
                if (error is CancellationException) {
                    addLog("Inference cancelled")
                } else {
                    addLog("Failed after retries: ${error.message ?: "resume failure"}")
                }
            }

            _uiState.update { it.copy(isAgentBusy = false) }
        }
    }

    fun runE2EDuckDuckGo(webView: WebView) {
        val state = _uiState.value
        if (state.isAgentBusy) {
            addLog("Agent busy. E2E delayed.")
            return
        }
        if (state.isAwaitingHuman) {
            addLog("E2E blocked: agent is paused for human intervention")
            return
        }

        activeJob = viewModelScope.launch {
            _uiState.update { it.copy(isAgentBusy = true) }
            val runner = DuckDuckGoE2ERunner(bridge, ::addLog)
            runner.run(webView)
                .onSuccess { addLog("Finished") }
                .onFailure { addLog("Failed after retries: ${it.message}") }
            _uiState.update { it.copy(isAgentBusy = false) }
        }
    }

    fun downloadModel(url: String) {
        val targetUrl = url.trim()
        if (targetUrl.isBlank()) {
            addLog("Model URL is blank.")
            return
        }
        val outputFileName = extractModelFileName(targetUrl)
        addLog("Model download started: $outputFileName")
        modelDownloader.downloadModel(targetUrl, outputFileName)
    }

    fun importLocalModel(uri: Uri) {
        addLog("Local model selected: $uri")
        viewModelScope.launch {
            addLog("Copying model to private storage")
            val displayName = runCatching { queryDisplayName(uri) }.getOrNull()
            modelRecoveryManager.importModelFromUri(uri, displayName)
                .onFailure { addLog("Import failed: ${it.message ?: "unknown error"}") }
        }
    }

    fun cancelModelDownload() {
        addLog("Model download cancel requested")
        modelDownloader.cancelDownload()
        addLog("Download cancelled")
    }

    fun loadExistingModelIfAvailable() {
        addLog("Validating model setup")
        val persisted = prefs.getString(KEY_MODEL_PATH, null)
        val candidates = linkedSetOf<String>()
        if (!persisted.isNullOrBlank()) candidates += persisted
        modelDownloader.getExistingModelPath(MODEL_FILE_NAME)?.let { candidates += it }

        for (candidate in candidates) {
            addLog("Validating model: $candidate")
            val validated = modelDownloader.getExistingModelPathFromAbsolute(candidate)
            if (validated != null) {
                applyValidatedModel(validated)
                return
            }
            addLog("Model invalid: $candidate")
        }

        val invalidPersisted = !persisted.isNullOrBlank()
        _uiState.update {
            it.copy(
                modelState = if (invalidPersisted) {
                    ModelDownloadState.Error("Model invalid. Delete and download a valid file.")
                } else {
                    ModelDownloadState.Idle
                },
                isModelReady = false,
                isFirstRunModelSetup = true,
                modelPath = persisted
            )
        }
        if (invalidPersisted) {
            addLog("Model invalid. Use Delete Model then download again.")
        } else {
            addLog("Model missing. First-run setup required.")
        }
    }

    fun clearLogs() {
        _uiState.update { it.copy(terminalLines = emptyList()) }
        addLog("Logs cleared")
    }

    fun checkForUpdates() {
        if (_uiState.value.updateState is UpdateCheckOutcome.Checking) {
            addLog("UPDATE checking: already in progress")
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(updateState = UpdateCheckOutcome.Checking) }
            addLog("UPDATE checking: github releases")
            val outcome = withContext(Dispatchers.IO) {
                runCatching { updateChecker.check() }.getOrElse {
                    UpdateCheckOutcome.Error(it.message?.take(180) ?: "unexpected error")
                }
            }
            when (outcome) {
                is UpdateCheckOutcome.UpToDate ->
                    addLog("UPDATE up-to-date: ${outcome.currentVersion}")
                is UpdateCheckOutcome.Available ->
                    addLog("UPDATE available: ${outcome.latestVersion}")
                is UpdateCheckOutcome.Error ->
                    addLog("UPDATE error: ${outcome.message}")
                else -> Unit
            }
            _uiState.update { it.copy(updateState = outcome) }
        }
    }

    fun clearContextMemory() {
        contextManager.clearHistory()
        latestMemoryContext = null
        refreshContextUsage()
        addLog("Context memory cleared")
    }

    fun clearRoomMemory() {
        viewModelScope.launch {
            memoryRepository.clearAllFacts()
            addLog("Room memory cleared")
        }
    }

    fun reExtractDom(webView: WebView) {
        viewModelScope.launch {
            rebuildDomSnapshot(webView, force = true)
            if (bridge.domTree.value.isBlank()) addLog("DOM not ready") else addLog("DOM re-extracted")
        }
    }

    fun deleteCurrentModel() {
        val current = _uiState.value.modelPath ?: prefs.getString(KEY_MODEL_PATH, null)
        if (current.isNullOrBlank()) {
            addLog("No model file selected to delete")
            return
        }
        runCatching {
            modelDownloader.deleteModelAtPath(current)
            modelRecoveryManager.deleteRecoveredModel(current)
        }
        clearPersistedModelPath()
        closeAgentManager()
        _uiState.update {
            it.copy(
                modelState = ModelDownloadState.Idle,
                isModelReady = false,
                isFirstRunModelSetup = true,
                modelPath = null
            )
        }
        addLog("Model deleted: $current")
    }

    private suspend fun runAgentLoop(
        webView: WebView,
        task: String,
        manager: LiteRtLmAgentManager
    ) {
        addLog("DOM extraction")
        val domMs = rebuildDomSnapshot(webView)
        addLog("Metric dom_ms=$domMs")

        if (bridge.domTree.value.isBlank()) {
            val feedback = compactFeedback("Error: Empty DOM. Refresh and use only IDs from current DOM.")
            contextManager.addSystemFeedback(feedback)
            addLog("↻ self-heal feedback added")
            addLog("Failed after retries: empty DOM")
            return
        }

        val executor = CommandExecutor(webView, ::addLog)
        val maxRetries = 2
        val maxTurns = 8
        var retryCount = 0
        var turnCount = 0

        while (retryCount <= maxRetries && turnCount < maxTurns) {
            turnCount++
            latestMemoryContext = memoryRepository.buildMemoryContext(task, limit = MAX_MEMORY_FACTS)
            refreshContextUsage()

            addLog("Prompt built")
            val prompt = contextManager.buildPrompt(task, bridge.domTree.value, latestMemoryContext)
            val memoryFactsCount = countInjectedFacts(latestMemoryContext)
            addLog(
                "Metric prompt_chars=${prompt.length}, context_percent=${_uiState.value.contextUsagePercent}, memory_facts=$memoryFactsCount"
            )

            addLog("LLM thinking")
            val inferenceStartMs = System.currentTimeMillis()
            val rawResult = runCatching { manager.planWithPrompt(prompt).trim() }
            val inferenceDurationMs = System.currentTimeMillis() - inferenceStartMs
            addLog("Metric inference_ms=$inferenceDurationMs")
            if (rawResult.isFailure) {
                val msg = rawResult.exceptionOrNull()?.message?.take(180) ?: "inference failed"
                val feedback = compactFeedback("Error: inference failed ($msg). Return one valid action.")
                contextManager.addSystemFeedback(feedback)
                addLog("↻ self-heal feedback added")
                if (retryCount < maxRetries) {
                    retryCount++
                    addLog("Retry count: $retryCount/$maxRetries")
                    addLog("Metric retry_count=$retryCount")
                    continue
                }
                addLog("Failed after retries: inference failure")
                return
            }

            val rawJson = rawResult.getOrThrow()
            val parsed = AgentCommandParser.parse(rawJson)
            if (parsed.isFailure) {
                val reason = parsed.exceptionOrNull()?.message?.take(200) ?: "malformed JSON"
                val feedback = compactFeedback("Error: $reason. Use strict schema and valid IDs only.")
                contextManager.addInteraction(task, rawJson, "parse_failed", feedback)
                contextManager.addSystemFeedback(feedback)
                addLog("↻ self-heal feedback added")
                if (retryCount < maxRetries) {
                    retryCount++
                    addLog("Retry count: $retryCount/$maxRetries")
                    addLog("Metric retry_count=$retryCount")
                    continue
                }
                addLog("Failed after retries: $reason")
                return
            }

            val command = parsed.getOrThrow()
            addLog("JSON parsed")
            addLog("JS executing")
            val jsStartMs = System.currentTimeMillis()
            val result = executor.execute(command)
            val jsDurationMs = System.currentTimeMillis() - jsStartMs
            addLog("Metric js_ms=$jsDurationMs")

            when (result) {
                is ExecutionResult.Success -> {
                    contextManager.addInteraction(task, rawJson, "success")
                    val successDomMs = rebuildDomSnapshot(webView)
                    addLog("Metric dom_ms=$successDomMs")
                    addLog("Finished")
                    return
                }

                is ExecutionResult.Done -> {
                    contextManager.addInteraction(task, rawJson, "finish:${result.reason.take(160)}")
                    addLog("Finished")
                    return
                }

                is ExecutionResult.ExtractedData -> {
                    contextManager.addInteraction(task, rawJson, "extracted:${result.keyName.take(64)}")
                    val sourceUrl = webView.url.orEmpty().ifBlank {
                        _uiState.value.currentUrl.orEmpty().ifBlank { "about:blank" }
                    }
                    memoryRepository.saveFact(result.keyName, result.value, sourceUrl)
                    addLog("Fact saved: ${result.keyName.take(64)}")
                    val extractDomMs = rebuildDomSnapshot(webView)
                    addLog("Metric dom_ms=$extractDomMs")
                    continue
                }

                is ExecutionResult.AwaitingHuman -> {
                    val reason = result.reason.trim().take(220).ifBlank { "Human intervention required" }
                    contextManager.addInteraction(task, rawJson, "awaiting_human:$reason")
                    pauseDomUpdatesInternal(webView)
                    _uiState.update {
                        it.copy(
                            isAwaitingHuman = true,
                            humanFallbackReason = reason,
                            pausedUserTask = task,
                            isAgentBusy = false
                        )
                    }
                    addLog("AGENT PAUSED: $reason")
                    addLog("Complete the action in WebView, then tap Resume.")
                    return
                }

                is ExecutionResult.Failed -> {
                    val reason = result.reason.take(200)
                    val feedback = compactFeedback("Error: $reason. Use only valid IDs from current DOM.")
                    contextManager.addInteraction(task, rawJson, "execution_failed:$reason", feedback)
                    contextManager.addSystemFeedback(feedback)
                    addLog("↻ self-heal feedback added")
                    if (retryCount < maxRetries) {
                        retryCount++
                        addLog("Retry count: $retryCount/$maxRetries")
                        addLog("Metric retry_count=$retryCount")
                        val retryDomMs = rebuildDomSnapshot(webView)
                        addLog("Metric dom_ms=$retryDomMs")
                        continue
                    }
                    addLog("Failed after retries: $reason")
                    return
                }
            }
        }

        addLog("Failed after retries: max turn limit reached")
    }

    private suspend fun rebuildDomSnapshot(webView: WebView, force: Boolean = false): Long {
        return measureTimeMillis {
            val now = System.currentTimeMillis()
            val shouldInject = force || (now - lastDomInjectEpochMs) >= DOM_INJECT_THROTTLE_MS
            if (shouldInject) {
                val injected = runCatching {
                    AgenticWebViewFactory.injectDomSimplifier(webView)
                    true
                }.getOrElse { error ->
                    addLog("WebView unavailable: ${error.message ?: "destroyed"}")
                    false
                }
                if (injected) {
                    lastDomInjectEpochMs = now
                    delay(240)
                }
            } else {
                delay(120)
            }
            contextManager.updateCurrentDom(bridge.domTree.value)
            _uiState.update { state -> state.copy(currentUrl = runCatching { webView.url }.getOrNull() ?: state.currentUrl) }
        }
    }

    private suspend fun pauseDomUpdatesInternal(webView: WebView) {
        evaluateOnMain(
            webView,
            "(function(){ return window.__agenticPauseDomUpdates ? window.__agenticPauseDomUpdates() : JSON.stringify({ok:false,error:'pause function missing'}); })();"
        )
    }

    private suspend fun resumeDomUpdatesInternal(webView: WebView) {
        evaluateOnMain(
            webView,
            "(function(){ return window.__agenticResumeDomUpdates ? window.__agenticResumeDomUpdates() : JSON.stringify({ok:false,error:'resume function missing'}); })();"
        )
    }

    private suspend fun evaluateOnMain(webView: WebView, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            runCatching {
                webView.evaluateJavascript(script) { result ->
                    if (cont.isActive) cont.resume(result.orEmpty())
                }
            }.onFailure { error ->
                if (cont.isActive) cont.resume("""{"ok":false,"error":"${error.message ?: "webview unavailable"}"}""")
            }
        }
    }

    private fun applyValidatedModel(modelPath: String) {
        persistModelPath(modelPath)
        _uiState.update {
            it.copy(
                modelState = ModelDownloadState.Ready(modelPath),
                modelPath = modelPath,
                isFirstRunModelSetup = false
            )
        }
        addLog("MODEL restored from persisted path: $modelPath")
        initializeAgentManager(modelPath)
    }

    private fun initializeAgentManager(modelPath: String) {
        if (loadedModelPath == modelPath && agentManager != null && _uiState.value.isModelReady) return
        loadedModelPath = modelPath
        contextManager.clearHistory()
        latestMemoryContext = null

        viewModelScope.launch {
            runCatching {
                closeAgentManager()
                agentManager = LiteRtLmAgentManager(getApplication(), modelPath, BackendMode.CPU)
                addLog("Agent initializing")
                val initMs = measureTimeMillis { agentManager?.initialize() }
                addLog("Metric model_init_ms=$initMs")
                addLog("Agent initialized")
                addLog("Agent ready")
                _uiState.update { state ->
                    state.copy(
                        modelPath = modelPath,
                        modelState = ModelDownloadState.Ready(modelPath),
                        isModelReady = true,
                        isFirstRunModelSetup = false,
                        isAgentBusy = false
                    )
                }
                addLog("MODEL ready state set true")
            }.onFailure { error ->
                runCatching { agentManager?.close() }
                agentManager = null
                loadedModelPath = null
                addLog("MODEL init failed: ${error.message ?: "unknown"}")
                _uiState.update { state ->
                    state.copy(
                        isModelReady = false,
                        modelState = ModelDownloadState.Error("Agent init failed: ${error.message}"),
                        isFirstRunModelSetup = true,
                        isAgentBusy = false
                    )
                }
            }
        }
    }

    private fun closeAgentManager() {
        runCatching {
            agentManager?.close()
            if (agentManager != null) addLog("Agent closed")
            agentManager = null
        }
    }

    private fun persistModelPath(path: String) {
        val previous = prefs.getString(KEY_MODEL_PATH, null)
        prefs.edit().putString(KEY_MODEL_PATH, path).apply()
        if (previous != path) {
            addLog("MODEL path persisted: $path")
        }
    }

    private fun clearPersistedModelPath() {
        prefs.edit().remove(KEY_MODEL_PATH).apply()
    }

    private fun extractModelFileName(url: String): String {
        val fallback = MODEL_FILE_NAME
        val pathSegment = url.substringAfterLast('/').substringBefore('?').trim()
        if (pathSegment.isBlank()) return fallback
        val safe = pathSegment.replace(Regex("[^A-Za-z0-9._-]"), "_").take(64)
        if (safe.endsWith(".litertlm") || safe.endsWith(".task") || safe.endsWith(".bin")) {
            return safe
        }
        return fallback
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return null
    }

    private fun addLog(message: String) {
        val line = message.trim().take(220)
        if (line.isBlank()) return
        _uiState.update { old ->
            val logs = (old.terminalLines + line).takeLast(MAX_LOG_LINES)
            val usage = contextManager.estimateContextUsagePercent(old.command, bridge.domTree.value, latestMemoryContext)
            old.copy(terminalLines = logs, contextUsagePercent = usage)
        }
    }

    private fun refreshContextUsage() {
        _uiState.update { old ->
            old.copy(
                contextUsagePercent = contextManager.estimateContextUsagePercent(
                    old.command,
                    bridge.domTree.value,
                    latestMemoryContext
                )
            )
        }
    }

    private fun compactFeedback(message: String): String {
        return """{"system_feedback":${JSONObject.quote(message.trim().take(220))}}"""
    }

    private fun countInjectedFacts(memoryContext: String?): Int {
        if (memoryContext.isNullOrBlank()) return 0
        return memoryContext.lines().count { it.trim().startsWith("source_url:") }
    }

    override fun onCleared() {
        runCatching {
            if (activeJob?.isActive == true) addLog("Inference cancelled")
            activeJob?.cancel()
            modelDownloader.cancelDownload()
            addLog("Download cancelled")
            closeAgentManager()
        }
        super.onCleared()
    }

    companion object {
        private const val PREFS_NAME = "browser_agent_prefs"
        private const val KEY_MODEL_PATH = "model_path"
        private const val MODEL_FILE_NAME = "model.litertlm"
        private const val MAX_LOG_LINES = 50
        private const val MAX_MEMORY_FACTS = 4
        private const val DOM_INJECT_THROTTLE_MS = 200L
    }
}
