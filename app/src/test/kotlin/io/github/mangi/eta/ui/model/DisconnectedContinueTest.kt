package io.github.mangi.eta.ui.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisconnectedContinueTest {
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
}
