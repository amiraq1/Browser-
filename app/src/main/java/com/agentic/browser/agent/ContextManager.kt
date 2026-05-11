package com.agentic.browser.agent

import org.json.JSONObject
import java.util.ArrayDeque

val SYSTEM_PROMPT: String = """
You are a deterministic browser UI agent running on-device.
Return exactly one minified JSON object per step.

Allowed actions (exactly eight, no others):
1. {"action":"click","id":"integer"}
2. {"action":"input_text","id":"integer","text":"string"}
3. {"action":"submit_form","id":"integer"}
4. {"action":"scroll","direction":"up|down"}
5. {"action":"wait","reason":"string"}
6. {"action":"finish","message":"string"}
7. {"action":"extract_data","id":"integer","key_name":"string"}
8. {"action":"ask_human","reason":"string"}

Hard constraints:
- Output exactly one minified JSON object.
- No markdown.
- No explanations.
- No arrays.
- No comments.
- No extra fields.
- Use only IDs from the current DOM.
- If unsure, use wait or finish. Never invent IDs.

Example 1:
DOM:
[ID:1] SearchInput: Search query | [ID:2] Button: Search
User task:
Search for MediaPipe LLM
Output:
{"action":"input_text","id":"1","text":"MediaPipe LLM"}

Example 2:
DOM:
[ID:5] Link: Login | [ID:6] Link: Documentation
User task:
Open the login page
Output:
{"action":"click","id":"5"}
""".trimIndent()

data class AgentInteraction(
    val userTask: String,
    val agentJson: String,
    val executionResult: String,
    val systemFeedback: String? = null
)

data class SystemFeedback(val message: String)

class ContextManager(
    private val maxInteractions: Int = 3,
    private val maxFeedback: Int = 4,
    private val targetPromptChars: Int = 16_000
) {
    private val lock = Any()
    private val interactions = ArrayDeque<AgentInteraction>(maxInteractions + 1)
    private val feedbackQueue = ArrayDeque<SystemFeedback>(maxFeedback + 1)
    private var currentDomSnapshot: String = ""

    fun updateCurrentDom(dom: String) {
        synchronized(lock) {
            currentDomSnapshot = dom.take(12_000)
        }
    }

    fun addInteraction(
        userTask: String,
        agentJson: String,
        executionResult: String,
        systemFeedback: String? = null
    ) {
        synchronized(lock) {
            interactions.addLast(
                AgentInteraction(
                    userTask = userTask.trim().take(300),
                    agentJson = agentJson.trim().take(320),
                    executionResult = executionResult.trim().take(260),
                    systemFeedback = systemFeedback?.trim()?.take(260)
                )
            )
            while (interactions.size > maxInteractions) interactions.removeFirst()
            if (!systemFeedback.isNullOrBlank()) {
                addSystemFeedbackLocked(systemFeedback)
            }
        }
    }

    fun addSystemFeedback(message: String) {
        synchronized(lock) {
            addSystemFeedbackLocked(message)
        }
    }

    fun clearHistory() {
        synchronized(lock) {
            interactions.clear()
            feedbackQueue.clear()
            currentDomSnapshot = ""
        }
    }

    fun buildPrompt(userTask: String, currentDom: String, memoryContext: String? = null): String {
        val task = userTask.trim().take(1_000)
        val dom = currentDom.take(12_000)
        val memory = memoryContext?.trim()?.take(1_200)
        val snapshot: String
        val localInteractions: List<AgentInteraction>
        val localFeedback: List<SystemFeedback>
        synchronized(lock) {
            if (dom.isNotBlank()) currentDomSnapshot = dom
            snapshot = if (dom.isNotBlank()) dom else currentDomSnapshot
            localInteractions = interactions.toList()
            localFeedback = feedbackQueue.toList()
        }

        val sb = StringBuilder(SYSTEM_PROMPT.length + snapshot.length + task.length + 2048)
        sb.append(SYSTEM_PROMPT).append('\n')
        sb.append("CURRENT_DOM:\n")
            .append(if (snapshot.isBlank()) "[EMPTY_DOM]" else snapshot)
            .append('\n')
        sb.append("USER_TASK:\n")
            .append(if (task.isBlank()) "[EMPTY_TASK]" else task)
            .append('\n')
        if (!memory.isNullOrBlank()) {
            sb.append(memory).append('\n')
        } else {
            sb.append("<MEMORY> none </MEMORY>\n")
        }

        if (localInteractions.isNotEmpty()) {
            sb.append("RECENT_INTERACTIONS:\n")
            localInteractions.forEachIndexed { index, item ->
                sb.append(index + 1)
                    .append(") task=").append(item.userTask)
                    .append(" | agent=").append(item.agentJson)
                    .append(" | result=").append(item.executionResult)
                if (!item.systemFeedback.isNullOrBlank()) {
                    sb.append(" | feedback=").append(item.systemFeedback)
                }
                sb.append('\n')
            }
        }

        if (localFeedback.isNotEmpty()) {
            sb.append("SYSTEM_FEEDBACK:\n")
            localFeedback.forEach { fb ->
                sb.append("{\"system_feedback\":")
                    .append(JSONObject.quote(fb.message))
                    .append("}\n")
            }
        }
        sb.append("OUTPUT:\n")
        return sb.toString()
    }

    fun estimateContextUsagePercent(userTask: String, currentDom: String, memoryContext: String? = null): Int {
        val task = userTask.trim().take(1_000)
        val dom = currentDom.take(12_000)
        val memory = memoryContext?.trim()?.take(1_200).orEmpty()
        val interactionsChars: Int
        val feedbackChars: Int
        synchronized(lock) {
            interactionsChars = interactions.sumOf {
                it.userTask.length + it.agentJson.length + it.executionResult.length + (it.systemFeedback?.length ?: 0) + 24
            }
            feedbackChars = feedbackQueue.sumOf { it.message.length + 24 }
        }
        val estimatedChars = SYSTEM_PROMPT.length + task.length + dom.length + memory.length + interactionsChars + feedbackChars + 256
        val percent = (estimatedChars * 100) / targetPromptChars
        return percent.coerceIn(0, 100)
    }

    private fun addSystemFeedbackLocked(message: String) {
        val normalized = message.trim().take(280)
        if (normalized.isBlank()) return
        feedbackQueue.addLast(SystemFeedback(normalized))
        while (feedbackQueue.size > maxFeedback) feedbackQueue.removeFirst()
    }
}
