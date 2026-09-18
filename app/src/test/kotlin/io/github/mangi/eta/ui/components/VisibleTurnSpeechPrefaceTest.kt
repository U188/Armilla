package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VisibleTurnSpeechPrefaceTest {
    @Test fun readsVisibleAssistantTextAndSkipsThinking() {
        val messages = listOf(
            UserMessageUi(id = "u", content = "第三项是什么"),
            ThinkingMessageUi(
                id = "t",
                content = "内部推理：先核对 Voice Mode 再写答案，这段不该被朗读。",
                isStreaming = false,
            ),
            AgentMessageUi(
                id = "mid",
                content = "先把 RikkaHub 的 Voice Mode 流程说清楚：它是语音进、语音出的对话，不是点一下朗读。",
            ),
            ToolActivityMessageUi(
                id = "tool",
                toolName = "web_search",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "search",
            ),
            AgentMessageUi(id = "final", content = "第三项是 Voice Mode：语音对话，不是朗读。"),
        )
        val preface = visibleTurnSpeechPreface(messages, "final")
        assertEquals(
            "先把 RikkaHub 的 Voice Mode 流程说清楚：它是语音进、语音出的对话，不是点一下朗读。",
            preface,
        )
        assertFalse(preface.contains("内部推理"))
    }
    @Test fun doesNotLeakVisibleTextFromThePreviousTurn() {
        val messages = listOf(
            UserMessageUi(id = "u1", content = "第一问"),
            AgentMessageUi(id = "a1", content = "第一轮可见正文"),
            UserMessageUi(id = "u2", content = "第二问"),
            AgentMessageUi(id = "mid2", content = "第二轮前置正文"),
            AgentMessageUi(id = "final2", content = "第二轮最终正文"),
        )

        assertEquals("第二轮前置正文", visibleTurnSpeechPreface(messages, "final2"))
    }

    @Test fun keepsVisibleTextAcrossSteeringSupplement() {
        val messages = listOf(
            UserMessageUi(id = "u", content = "开始任务"),
            AgentMessageUi(id = "mid1", content = "第一段可见正文"),
            UserMessageUi(id = "u-supplement-1", content = "补充要求"),
            AgentMessageUi(id = "mid2", content = "第二段可见正文"),
            AgentMessageUi(id = "final", content = "最终正文"),
        )

        assertEquals(
            "第一段可见正文\n\n第二段可见正文",
            visibleTurnSpeechPreface(messages, "final"),
        )
    }

}
