package com.agentic.browser.web

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.agentic.browser.agent.DomSimplifierPayload

class AgenticWebViewFactory(private val context: Context, private val bridge: AgentBridge, private val onStatus: (String) -> Unit) {
    @SuppressLint("SetJavaScriptEnabled")
    fun create(): WebView = WebView(context).apply {
        // Security baseline: keep WebView surface locked down and route all agent actions through CommandExecutor only.
        setBackgroundColor(Color.rgb(10,10,10))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        addJavascriptInterface(bridge, DomSimplifierPayload.BRIDGE_NAME)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                return request.url.scheme !in setOf("http", "https")
            }
            override fun onPageFinished(view: WebView, url: String?) { onStatus("Extracting DOM..."); injectDomSimplifier(view) }
        }
        loadUrl(DEFAULT_HOME)
    }
    fun normalizeUrl(raw: String): String { val t=raw.trim(); if(t.isBlank()) return DEFAULT_HOME; val u=Uri.parse(t); if(u.scheme=="http"||u.scheme=="https") return t; return if(t.contains(".")&&!t.contains(" ")) "https://$t" else "https://www.google.com/search?q=${Uri.encode(t)}" }
    companion object { private const val DEFAULT_HOME="https://www.google.com"; fun injectDomSimplifier(webView: WebView){ webView.evaluateJavascript(DomSimplifierPayload.build(), null) } }
}
