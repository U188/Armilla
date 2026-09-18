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
}
