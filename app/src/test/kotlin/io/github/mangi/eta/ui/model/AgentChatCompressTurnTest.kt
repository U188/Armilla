package io.github.mangi.eta.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatCompressTurnTest {
    @Test
    fun previousAssistantDoesNotCountAsCurrentTurnOutput() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "上一轮"),
                AgentMessageUi("a1", "旧回复"),
                UserMessageUi("u2", "新问题"),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertFalse(state.hasStartedCurrentTurnOutput())
        assertFalse(state.hasRunningTools())
    }

    @Test
    fun currentTurnThinkingCounts() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "上一轮"),
                AgentMessageUi("a1", "旧回复"),
                UserMessageUi("u2", "新问题"),
                ThinkingMessageUi("t2", "思考中", isStreaming = true),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = true,
        )
        assertTrue(state.hasStartedCurrentTurnOutput())
    }

    @Test
    fun currentTurnRunningToolCountsAsRunningAndStarted() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "新问题"),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Running,
                    argumentsSummary = "ls",
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertTrue(state.hasRunningTools())
        assertTrue(state.hasStartedCurrentTurnOutput())
    }

    @Test
    fun finishedToolsDoNotCountAsRunning() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "新问题"),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "ls",
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertFalse(state.hasRunningTools())
        assertTrue(state.hasStartedCurrentTurnOutput())
    }

    @Test
    fun previousTurnToolsDoNotCountAsCurrentTurnTools() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "上一轮"),
                ToolActivityMessageUi(
                    id = "tool-old",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "ls",
                ),
                UserMessageUi("u2", "新问题"),
                ThinkingMessageUi("t2", "思考中", isStreaming = true),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = true,
        )
        assertFalse(state.hasCurrentTurnTools())
        assertTrue(state.hasStartedCurrentTurnOutput())
    }

    @Test
    fun currentTurnFinishedToolsCount() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "新问题"),
                ToolActivityMessageUi(
                    id = "tool-1",
                    toolName = "run_command",
                    status = ToolActivityStatusUi.Success,
                    argumentsSummary = "ls",
                ),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertTrue(state.hasCurrentTurnTools())
    }

    @Test
    fun partialAssistantAfterLastUserShouldContinue() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "写5000字故事"),
                AgentMessageUi("a1", "故事写到一半"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        assertTrue(state.hasPartialAssistantAfterLastUser())
    }

    @Test
    fun steerAfterPartialShouldNotCountAsPartialToContinue() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "写5000字故事"),
                AgentMessageUi("a1", "故事写到一半"),
                UserMessageUi("u2", "还有没"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        assertFalse(state.hasPartialAssistantAfterLastUser())
    }

    @Test
    fun appendSupplementStaysInSameTurnForKeepAndResume() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "写5000字故事"),
                AgentMessageUi("a1", "故事写到一半"),
                UserMessageUi("user-run-1-supplement-0", "还有没"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        assertTrue(state.hasPartialAssistantAfterLastUser())
        assertTrue(state.hasStartedCurrentTurnOutput())
        assertFalse(state.hasCurrentTurnTools())
    }

    @Test
    fun userOnlyTurnHasNoPartialAssistant() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi("u1", "写5000字故事"),
            ),
            input = "",
            isStreaming = true,
            thinkingEnabled = false,
        )
        assertFalse(state.hasPartialAssistantAfterLastUser())
    }
}
