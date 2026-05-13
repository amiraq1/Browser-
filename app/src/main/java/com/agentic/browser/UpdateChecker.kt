package com.agentic.browser

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

sealed class UpdateCheckOutcome {
    object Idle : UpdateCheckOutcome()
    object Checking : UpdateCheckOutcome()
    data class UpToDate(val currentVersion: String) : UpdateCheckOutcome()
    data class Available(val latestVersion: String, val url: String) : UpdateCheckOutcome()
    data class Error(val message: String) : UpdateCheckOutcome()
}

class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val currentVersion: String = VERSION,
    private val repo: String = DEFAULT_REPO
) {
    fun check(): UpdateCheckOutcome {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return UpdateCheckOutcome.Error("HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                if (body.isBlank()) return UpdateCheckOutcome.Error("Empty response body")
                val json = org.json.JSONObject(body)
                val tag = json.optString("tag_name").removePrefix("v").trim()
                if (tag.isBlank()) return UpdateCheckOutcome.Error("Missing tag_name")
                val rawUrl = json.optString("html_url", "").trim()
                val safeUrl = if (rawUrl.startsWith("https://")) rawUrl else ""
                if (isNewer(tag, currentVersion)) {
                    UpdateCheckOutcome.Available(tag, safeUrl)
                } else {
                    UpdateCheckOutcome.UpToDate(currentVersion)
                }
            }
        } catch (e: Exception) {
            UpdateCheckOutcome.Error(e.message?.take(180) ?: "network error")
        }
    }

    companion object {
        const val VERSION = "1.0.0"
        const val DEFAULT_REPO = "anthropics/AgenticBrowserLiteRT"

        fun isNewer(remote: String, local: String): Boolean {
            val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
            val l = local.split(".").map { it.toIntOrNull() ?: 0 }
            val max = maxOf(r.size, l.size)
            for (i in 0 until max) {
                val rv = r.getOrElse(i) { 0 }
                val lv = l.getOrElse(i) { 0 }
                if (rv > lv) return true
                if (rv < lv) return false
            }
            return false
        }
    }
}
