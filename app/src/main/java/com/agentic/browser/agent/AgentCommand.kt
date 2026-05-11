package com.agentic.browser.agent

sealed interface AgentCommand {
    data class Click(val id: String) : AgentCommand
    data class InputText(val id: String, val text: String) : AgentCommand
    data class SubmitForm(val id: String) : AgentCommand
    data class Scroll(val direction: ScrollDirection) : AgentCommand
    data class Wait(val reason: String) : AgentCommand
    data class Finish(val message: String) : AgentCommand
    data class ExtractData(val id: String, val keyName: String) : AgentCommand
    data class AskHuman(val reason: String) : AgentCommand
}

enum class ScrollDirection { UP, DOWN }
