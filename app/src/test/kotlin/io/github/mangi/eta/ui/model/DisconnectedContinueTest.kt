package io.github.mangi.eta.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectedContinueTest {
    @Test fun oldRetryBeforeTerminalBoundaryDoesNotEnableContinueForANewerStop() {
        val messages = listOf(
            UserMessageUi("user-run-old", "task"),
            SystemNoticeMessageUi("assistant-run-old-retry-1", SystemNoticeCode.ModelRetry),
            SystemNoticeMessageUi("fail", SystemNoticeCode.RuntimeFailed),
            AgentMessageUi("assistant-run-new-1", "retry output"),
            SystemNoticeMessageUi("stop", SystemNoticeCode.Stopped),
        )
        assertFalse(messages.stoppedDuringModelRetry())
        assertFalse(canContinueDisconnectedRun(messages))
    }

    @Test
    fun runtimeFailureNoticeEnablesContinue() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "继续刚才的任务"),
            AgentMessageUi(id = "assistant-1", content = "已完成 4 个步骤"),
            SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.RuntimeFailed, detail = "connection closed"),
        )
        assertTrue(canContinueDisconnectedRun(messages))
        assertFalse(canContinuePausedGeneration(messages))
    }

    @Test
    fun laterUserMessageDisablesDisconnectedContinue() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.RuntimeFailed, detail = "connection closed"),
            UserMessageUi(id = "user-2", content = "换个问题"),
        )
        assertFalse(canContinueDisconnectedRun(messages))
    }

    @Test
    fun emptyResultDoesNotLookLikeDisconnect() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            SystemNoticeMessageUi(id = "empty-1", code = SystemNoticeCode.EmptyResult),
        )
        assertFalse(canContinueDisconnectedRun(messages))
    }

    @Test
    fun resumeSupplementDoesNotHideFailureNotice() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            AgentMessageUi(id = "assistant-1", content = "半截正文"),
            SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.RuntimeFailed, detail = "connection closed"),
            UserMessageUi(id = "user-run-2-supplement-resume", content = "从被打断的位置直接接着做。"),
        )
        assertTrue(canContinueDisconnectedRun(messages))
    }

    @Test
    fun failedRoundStaysClosedAfterNotice() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "user-1", content = "任务"),
                AgentMessageUi(id = "assistant-1", content = "已完成 4 个步骤"),
                SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.RuntimeFailed, detail = "connection closed"),
            ),
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        )
        assertFalse(state.hasPartialAssistantAfterLastUser())
        assertFalse(state.hasStartedCurrentTurnOutput())
        assertTrue(canContinueDisconnectedRun(state.messages))
    }

    @Test
    fun pauseAndSteerStayOnTheSameOpenTurn() {
        val state = AgentChatUiState(
            messages = listOf(
                UserMessageUi(id = "user-1", content = "任务"),
                AgentMessageUi(id = "assistant-1", content = "做到一半", isStreaming = false),
                UserMessageUi(id = "user-1-supplement-1", content = "再补充一句"),
            ),
            input = "",
            isStreaming = true,
            isPaused = true,
            thinkingEnabled = false,
        )
        assertTrue(state.hasPartialAssistantAfterLastUser())
        assertTrue(state.hasStartedCurrentTurnOutput())
        assertFalse(canContinueDisconnectedRun(state.messages))
    }

    @Test
    fun stoppingWhileWaitingForApiRetryEnablesContinue() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            SystemNoticeMessageUi(
                id = "assistant-run-1-retry-1",
                code = SystemNoticeCode.ModelRetry,
                detail = "模型请求暂时中断，2 秒后重试（1/3）",
            ),
            SystemNoticeMessageUi(id = "fail-1", code = SystemNoticeCode.Stopped),
        )
        assertTrue(messages.stoppedDuringModelRetry())
        assertTrue(canContinueDisconnectedRun(messages))
        assertFalse(AgentChatUiState(
            messages = messages,
            input = "",
            isStreaming = false,
            thinkingEnabled = false,
        ).hasPartialAssistantAfterLastUser())
    }

    @Test
    fun ordinaryStopWithoutRetryDoesNotLookLikeDisconnect() {
        val messages = listOf(
            UserMessageUi(id = "user-1", content = "任务"),
            AgentMessageUi(id = "assistant-1", content = "写到一半"),
            SystemNoticeMessageUi(id = "stop-1", code = SystemNoticeCode.Stopped),
        )
        assertFalse(messages.stoppedDuringModelRetry())
        assertFalse(canContinueDisconnectedRun(messages))
    }
}
