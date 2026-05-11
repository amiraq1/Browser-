package com.agentic.browser.web

import android.webkit.JavascriptInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AgentBridge {
    private val _domTree = MutableStateFlow("")
    val domTree: StateFlow<String> = _domTree
    private val _errors = MutableStateFlow<String?>(null)
    val errors: StateFlow<String?> = _errors

    @JavascriptInterface fun onDomExtracted(payload: String) { _domTree.value = payload.take(12_000) }
    @JavascriptInterface fun onDomError(error: String) { _errors.value = error.take(512) }
}
