package com.agentic.browser.agent

import android.webkit.WebView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.coroutines.resume

// Security boundary: model output never executes as raw JavaScript.
// Only parsed AgentCommand values can reach this executor and all injected text is escaped via JSONObject.quote.
class CommandExecutor(private val webView: WebView, private val onStatus: (String) -> Unit) {
    suspend fun execute(command: AgentCommand): ExecutionResult = when (command) {
        is AgentCommand.Click -> evalClick(command.id)
        is AgentCommand.InputText -> evalInputText(command.id, command.text)
        is AgentCommand.SubmitForm -> evalSubmitForm(command.id)
        is AgentCommand.Scroll -> evalScroll(command.direction)
        is AgentCommand.ExtractData -> evalExtractData(command.id, command.keyName)
        is AgentCommand.AskHuman -> ExecutionResult.AwaitingHuman(command.reason)
        is AgentCommand.Wait -> {
            onStatus("Waiting: ${command.reason}")
            delay(700)
            ExecutionResult.Success("{\"ok\":true,\"action\":\"wait\",\"reason\":${JSONObject.quote(command.reason)}}")
        }
        is AgentCommand.Finish -> ExecutionResult.Done(command.message)
    }

    private suspend fun evalClick(id: String): ExecutionResult {
        onStatus("Executing JS: click($id)")
        val js = """
            (function(){
                if(!window.__agenticClick) return JSON.stringify({ok:false,error:'Agent JS not installed'});
                return window.__agenticClick(${JSONObject.quote(id)});
            })();
        """
        return evaluate(js).toExecutionResult()
    }

    private suspend fun evalInputText(id: String, text: String): ExecutionResult {
        onStatus("Executing JS: input_text($id)")
        val js = """
            (function(){
                if(!window.__agenticInputText) return JSON.stringify({ok:false,error:'Agent JS not installed'});
                return window.__agenticInputText(${JSONObject.quote(id)}, ${JSONObject.quote(text)});
            })();
        """
        return evaluate(js).toExecutionResult()
    }

    private suspend fun evalSubmitForm(id: String): ExecutionResult {
        onStatus("Executing JS: submit_form($id)")
        val js = """
            (function(){
                if(!window.__agenticSubmitForm) return JSON.stringify({ok:false,error:'Agent JS not installed'});
                return window.__agenticSubmitForm(${JSONObject.quote(id)});
            })();
        """
        return evaluate(js).toExecutionResult()
    }

    private suspend fun evalScroll(direction: ScrollDirection): ExecutionResult {
        val directionText = direction.name.lowercase()
        onStatus("Executing JS: scroll($directionText)")
        val js = """
            (function(){
                if(!window.__agenticScroll) return JSON.stringify({ok:false,error:'Agent JS not installed'});
                return window.__agenticScroll(${JSONObject.quote(directionText)});
            })();
        """
        return evaluate(js).toExecutionResult()
    }

    private suspend fun evalExtractData(id: String, keyName: String): ExecutionResult {
        onStatus("Executing JS: extract_data($id,$keyName)")
        val js = """
            (function(){
                if(!window.__agenticExtractData) return JSON.stringify({ok:false,error:'Agent JS not installed'});
                return window.__agenticExtractData(${JSONObject.quote(id)}, ${JSONObject.quote(keyName)});
            })();
        """
        return evaluate(js).toExecutionResult()
    }

    private suspend fun evaluate(script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            webView.evaluateJavascript(script) { if (cont.isActive) cont.resume(it.orEmpty()) }
        }
    }

    private fun String.toExecutionResult(): ExecutionResult = runCatching {
        val unwrapped = removeSurrounding("\"").replace("\\\"", "\"").replace("\\\\", "\\")
        val obj = JSONObject(unwrapped)
        if (obj.optBoolean("ok", false) &&
            obj.optString("action") == "extract_data" &&
            obj.has("key_name")
        ) {
            ExecutionResult.ExtractedData(
                keyName = obj.optString("key_name", ""),
                value = obj.optString("value", ""),
                raw = unwrapped
            )
        } else if (obj.optBoolean("ok", false)) {
            ExecutionResult.Success(unwrapped)
        } else {
            ExecutionResult.Failed(obj.optString("error", "JS failed"))
        }
    }.getOrElse { ExecutionResult.Failed("Invalid JS response") }
}

sealed interface ExecutionResult {
    data class Success(val raw: String) : ExecutionResult
    data class ExtractedData(val keyName: String, val value: String, val raw: String) : ExecutionResult
    data class AwaitingHuman(val reason: String) : ExecutionResult
    data class Done(val reason: String) : ExecutionResult
    data class Failed(val reason: String) : ExecutionResult
}
