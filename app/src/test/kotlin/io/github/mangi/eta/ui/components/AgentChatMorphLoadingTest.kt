package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatMorphLoadingTest {

    @Test
    fun waitingAfterSendBeforeAnyOutput() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "你好"),
            AgentMessageUi(id = "agent-1", content = "", isStreaming = true),
        )
        assertTrue(isWaitingForFirstModelOutput(messages))
        assertTrue(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
    }

    @Test
    fun hidesOnceThinkingStarts() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "你好"),
            ThinkingMessageUi(id = "think-1", content = "先想想", isStreaming = true),
        )
        assertFalse(isWaitingForFirstModelOutput(messages))
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
    }

    @Test
    fun hidesOnceAssistantTextStarts() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "你好"),
            AgentMessageUi(id = "agent-1", content = "好的", isStreaming = true),
        )
        assertFalse(isWaitingForFirstModelOutput(messages))
    }

    @Test
    fun hidesOnceToolStarts() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "查一下"),
            ToolActivityMessageUi(
                id = "tool-1",
                toolName = "search",
                status = ToolActivityStatusUi.Running,
                argumentsSummary = "q=eta",
            ),
        )
        assertFalse(isWaitingForFirstModelOutput(messages))
    }

    @Test
    fun blankPlaceholderDoesNotCountAsOutput() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "你好"),
        )
        assertTrue(isWaitingForFirstModelOutput(messages))
    }

    @Test
    fun entireGenerationModeKeepsShowingAfterOutput() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "你好"),
            AgentMessageUi(id = "agent-1", content = "好的", isStreaming = true),
        )
        assertTrue(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = true,
                beforeResponseOnly = false,
            ),
        )
    }

    @Test
    fun disabledOrPausedOrCompressingHidesIndicator() {
        val messages = listOf(UserMessageUi(id = "user-1", content = "你好"))
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = false,
                beforeResponseOnly = true,
            ),
        )
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                isPaused = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                isCompressingContext = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = false,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
    }

    @Test
    fun steerSupplementDoesNotCountAsNewWait() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "你好"),
            AgentMessageUi(id = "agent-1", content = "先说到这", isStreaming = true),
            UserMessageUi(id = "user-1-supplement-0", content = "继续"),
        )
        assertFalse(isWaitingForFirstModelOutput(messages))
        assertFalse(
            shouldShowMorphLoadingIndicator(
                messages = messages,
                isStreaming = true,
                enabled = true,
                beforeResponseOnly = true,
            ),
        )
    }
}
