package com.agentic.browser.agent

import org.json.JSONException
import org.json.JSONObject

// Security boundary: this parser is the only gate from model text to executable commands.
// It rejects malformed JSON, unknown actions, unknown keys, missing required keys, and invalid IDs.
object AgentCommandParser {
    private val allowedActions = setOf("click", "input_text", "submit_form", "scroll", "wait", "finish", "extract_data", "ask_human")

    fun parse(rawResponse: String): Result<AgentCommand> = runCatching {
        val jsonText = extractFirstJsonObject(rawResponse) ?: throw JSONException("No JSON object found")
        val obj = JSONObject(jsonText)
        val action = obj.requiredAction()
        when (action) {
            "click" -> {
                obj.requireOnly("action", "id")
                AgentCommand.Click(obj.requiredPositiveIntegerString("id"))
            }

            "input_text" -> {
                obj.requireOnly("action", "id", "text")
                AgentCommand.InputText(
                    id = obj.requiredPositiveIntegerString("id"),
                    text = obj.requiredString("text", allowBlank = true)
                )
            }

            "submit_form" -> {
                obj.requireOnly("action", "id")
                AgentCommand.SubmitForm(obj.requiredPositiveIntegerString("id"))
            }

            "scroll" -> {
                obj.requireOnly("action", "direction")
                val direction = when (obj.requiredString("direction").lowercase()) {
                    "up" -> ScrollDirection.UP
                    "down" -> ScrollDirection.DOWN
                    else -> throw JSONException("direction must be 'up' or 'down'")
                }
                AgentCommand.Scroll(direction)
            }

            "wait" -> {
                obj.requireOnly("action", "reason")
                AgentCommand.Wait(obj.requiredString("reason", allowBlank = false, maxLen = 220))
            }

            "finish" -> {
                obj.requireOnly("action", "message")
                AgentCommand.Finish(obj.requiredString("message", allowBlank = true, maxLen = 220))
            }

            "extract_data" -> {
                obj.requireOnly("action", "id", "key_name")
                AgentCommand.ExtractData(
                    id = obj.requiredPositiveIntegerString("id"),
                    keyName = obj.requiredString("key_name", allowBlank = false, maxLen = 64)
                )
            }

            "ask_human" -> {
                obj.requireOnly("action", "reason")
                AgentCommand.AskHuman(obj.requiredString("reason", allowBlank = false, maxLen = 220))
            }

            else -> throw JSONException("Unsupported action: $action")
        }
    }

    private fun JSONObject.requiredAction(): String {
        val action = requiredString("action").lowercase()
        if (action !in allowedActions) throw JSONException("Unsupported action: $action")
        return action
    }

    private fun JSONObject.requiredPositiveIntegerString(key: String): String {
        val value = requiredString(key)
        if (!value.matches(Regex("^[1-9][0-9]*$"))) {
            throw JSONException("$key must be a string containing a positive integer")
        }
        return value
    }

    private fun JSONObject.requiredString(key: String, allowBlank: Boolean = false, maxLen: Int = 2000): String {
        if (!has(key)) throw JSONException("Missing $key")
        val value = opt(key)
        if (value !is String) throw JSONException("$key must be a string")
        if (!allowBlank && value.isBlank()) throw JSONException("$key must not be blank")
        if (value.length > maxLen) throw JSONException("$key exceeds max length $maxLen")
        return value
    }

    private fun JSONObject.requireOnly(vararg keys: String) {
        val allowed = keys.toSet()
        val names = names() ?: return
        for (i in 0 until names.length()) {
            val key = names.optString(i)
            if (key !in allowed) throw JSONException("Unexpected key: $key")
        }
    }

    private fun extractFirstJsonObject(raw: String): String? {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        var depth = 0
        var start = -1
        var inString = false
        var escaped = false
        for (i in text.indices) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (inString) continue
            if (c == '{') {
                if (depth == 0) start = i
                depth++
            } else if (c == '}') {
                depth--
                if (depth == 0 && start >= 0) return text.substring(start, i + 1)
            }
        }
        return null
    }
}
