package com.agentic.browser.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagerTest {

    @Test
    fun keepsSystemPromptDomAndTask() {
        val manager = ContextManager(maxInteractions = 3)
        val prompt = manager.buildPrompt(userTask = "Search", currentDom = "[ID:1] Input")

        assertTrue(prompt.contains("deterministic browser UI agent"))
        assertTrue(prompt.contains("CURRENT_DOM:"))
        assertTrue(prompt.contains("[ID:1] Input"))
        assertTrue(prompt.contains("USER_TASK:"))
        assertTrue(prompt.contains("Search"))
    }

    @Test
    fun evictsOldInteractionsAfterN() {
        val manager = ContextManager(maxInteractions = 2)
        manager.addInteraction("task1", "{\"a\":1}", "ok1")
        manager.addInteraction("task2", "{\"a\":2}", "ok2")
        manager.addInteraction("task3", "{\"a\":3}", "ok3")

        val prompt = manager.buildPrompt(userTask = "x", currentDom = "dom")
        assertFalse(prompt.contains("task=task1"))
        assertTrue(prompt.contains("task=task2"))
        assertTrue(prompt.contains("task=task3"))
    }

    @Test
    fun injectsMemoryContextCompactly() {
        val manager = ContextManager()
        val memory = """
            <MEMORY>
            email: test@example.com
            source_url: https://example.com
            </MEMORY>
        """.trimIndent()

        val prompt = manager.buildPrompt("use memory", "dom", memory)
        assertTrue(prompt.contains("<MEMORY>"))
        assertTrue(prompt.contains("email: test@example.com"))
    }
}
