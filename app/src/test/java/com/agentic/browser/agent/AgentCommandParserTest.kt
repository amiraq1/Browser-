package com.agentic.browser.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCommandParserTest {
    private fun parseSuccess(json: String): AgentCommand {
        val result = AgentCommandParser.parse(json)
        assertTrue("Expected success but got: ${result.exceptionOrNull()?.message}", result.isSuccess)
        return result.getOrThrow()
    }

    @Test
    fun acceptsValidClick() {
        assertEquals(AgentCommand.Click("1"), parseSuccess("""{"action":"click","id":"1"}"""))
    }

    @Test
    fun acceptsValidInputText() {
        assertEquals(AgentCommand.InputText("2", "hello"), parseSuccess("""{"action":"input_text","id":"2","text":"hello"}"""))
    }

    @Test
    fun acceptsValidSubmitForm() {
        assertEquals(AgentCommand.SubmitForm("3"), parseSuccess("""{"action":"submit_form","id":"3"}"""))
    }

    @Test
    fun acceptsValidScroll() {
        assertEquals(AgentCommand.Scroll(ScrollDirection.DOWN), parseSuccess("""{"action":"scroll","direction":"down"}"""))
    }

    @Test
    fun acceptsValidWait() {
        assertEquals(AgentCommand.Wait("loading"), parseSuccess("""{"action":"wait","reason":"loading"}"""))
    }

    @Test
    fun acceptsValidFinish() {
        assertEquals(AgentCommand.Finish("done"), parseSuccess("""{"action":"finish","message":"done"}"""))
    }

    @Test
    fun acceptsValidExtractData() {
        assertEquals(AgentCommand.ExtractData("9", "title"), parseSuccess("""{"action":"extract_data","id":"9","key_name":"title"}"""))
    }

    @Test
    fun acceptsValidAskHuman() {
        assertEquals(AgentCommand.AskHuman("captcha"), parseSuccess("""{"action":"ask_human","reason":"captcha"}"""))
    }

    @Test
    fun rejectsUnknownAction() {
        val result = AgentCommandParser.parse("{\"action\":\"hack\",\"id\":\"1\"}")
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsExtraFields() {
        val result = AgentCommandParser.parse("{\"action\":\"click\",\"id\":\"1\",\"x\":\"y\"}")
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsMalformedJson() {
        val result = AgentCommandParser.parse("{\"action\":\"click\"")
        assertTrue(result.isFailure)
    }

    @Test
    fun rejectsInvalidId() {
        val result = AgentCommandParser.parse("{\"action\":\"click\",\"id\":\"0\"}")
        assertTrue(result.isFailure)
    }
}
