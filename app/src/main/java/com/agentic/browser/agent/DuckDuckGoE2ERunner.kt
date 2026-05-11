package com.agentic.browser.agent

import android.webkit.WebView
import com.agentic.browser.web.AgentBridge
import com.agentic.browser.web.AgenticWebViewFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

class DuckDuckGoE2ERunner(
    private val bridge: AgentBridge,
    private val onStatus: (String) -> Unit
) {
    suspend fun run(webView: WebView): Result<String> = runCatching {
        val executor = CommandExecutor(webView, onStatus)
        onStatus("E2E: opening DuckDuckGo HTML")
        loadUrl(webView, DUCKDUCKGO_HTML_URL)
        waitForReadyState(webView)

        AgenticWebViewFactory.injectDomSimplifier(webView)
        delay(400)

        val domBefore = bridge.domTree.value
        val inputId = findInputId(domBefore)
        val submitId = findSearchButtonId(domBefore)

        when (val result = executor.execute(AgentCommand.InputText(inputId, DUCKDUCKGO_QUERY))) {
            is ExecutionResult.Failed -> error("E2E input_text failed: ${result.reason}")
            else -> Unit
        }

        val submitResult = executor.execute(AgentCommand.SubmitForm(inputId))
        if (submitResult is ExecutionResult.Failed) {
            when (val fallback = executor.execute(AgentCommand.Click(submitId))) {
                is ExecutionResult.Failed -> error("E2E submit failed: ${fallback.reason}")
                else -> Unit
            }
        }

        executor.execute(AgentCommand.Wait("wait for DuckDuckGo results"))
        waitForReadyState(webView)
        AgenticWebViewFactory.injectDomSimplifier(webView)
        delay(350)

        val resultCount = estimateResultRows(bridge.domTree.value)
        val finish = executor.execute(AgentCommand.Finish("DuckDuckGo results loaded"))
        if (finish is ExecutionResult.Failed) {
            error("E2E finish failed: ${finish.reason}")
        }

        JSONObject(
            mapOf(
                "ok" to true,
                "engine" to "duckduckgo-html",
                "query" to DUCKDUCKGO_QUERY,
                "inputId" to inputId,
                "submitId" to submitId,
                "estimatedResultRows" to resultCount
            )
        ).toString()
    }

    private suspend fun loadUrl(webView: WebView, url: String) = withContext(Dispatchers.Main) {
        webView.loadUrl(url)
    }

    private suspend fun waitForReadyState(webView: WebView, attempts: Int = 40, delayMs: Long = 200L) {
        repeat(attempts) {
            val state = evaluate(webView, "(function(){return document.readyState;})();")
                .removeSurrounding("\"")
                .lowercase()
            if (state == "complete" || state == "interactive") return
            delay(delayMs)
        }
        error("Timed out waiting for page readiness")
    }

    private suspend fun evaluate(webView: WebView, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value.orEmpty())
            }
        }
    }

    private fun findInputId(dom: String): String {
        val rows = domRows(dom)
        val searchInput = rows.firstOrNull {
            val lower = it.lowercase()
            lower.contains("searchinput") || (lower.contains("input") && lower.contains("search"))
        } ?: rows.firstOrNull { it.lowercase().contains("input") }
        return searchInput?.extractId() ?: error("E2E could not find an input id")
    }

    private fun findSearchButtonId(dom: String): String {
        val rows = domRows(dom)
        val button = rows.firstOrNull {
            val lower = it.lowercase()
            lower.contains("button") && (lower.contains("search") || lower.contains("go"))
        } ?: rows.firstOrNull { it.lowercase().contains("button") }
        return button?.extractId() ?: error("E2E could not find a search button id")
    }

    private fun estimateResultRows(dom: String): Int {
        val rows = domRows(dom)
        return rows.count { row ->
            val lower = row.lowercase()
            lower.contains("link") &&
                !lower.contains("settings") &&
                !lower.contains("privacy") &&
                !lower.contains("feedback")
        }
    }

    private fun domRows(dom: String): List<String> = dom.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }

    private fun String.extractId(): String? {
        val start = indexOf("[ID:")
        if (start < 0) return null
        val end = indexOf("]", start)
        if (end <= start + 4) return null
        return substring(start + 4, end).trim()
    }

    companion object {
        private const val DUCKDUCKGO_HTML_URL = "https://html.duckduckgo.com/html/"
        private const val DUCKDUCKGO_QUERY = "MediaPipe LLM"
    }
}
