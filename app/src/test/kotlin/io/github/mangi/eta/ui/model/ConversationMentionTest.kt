package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.MentionedConversation
import org.junit.Assert.*
import org.junit.Test

class ConversationMentionTest {
    private fun summary(id: String, title: String = id, time: Long = 0L) = ConversationSummaryUi(
        id = id, title = title, preview = "预览", timeLabel = "", updatedAtMillis = time, mode = ConversationModeUi.Chat,
    )
    @Test fun querySupportsChineseAndCursorInMiddle() {
        assertEquals("价格", ConversationMention.queryAtCursor("结合@价格看看", 5)?.query)
        assertEquals("", ConversationMention.queryAtCursor("@", 1)?.query)
        assertEquals("旧 会话", ConversationMention.queryAtCursor("@旧 会话", 5)?.query)
        assertNull(ConversationMention.queryAtCursor("user@example.com", 16))
        assertNull(ConversationMention.queryAtCursor("@旧\n消息", 5))
    }
    @Test fun candidatesExcludeSelfAndDuplicatesAndSortByRecent() {
        val all = listOf(summary("a", time = 1), summary("b", time = 9), summary("c", time = 3), summary("d", time = 4))
        assertEquals(listOf("d", "c"), ConversationMention.candidates(all, "", "a", setOf("b")).map { it.id })
        assertEquals(listOf("b"), ConversationMention.candidates(all, "b", null, emptySet()).map { it.id })
        assertEquals(1, ConversationMention.candidates(all, "预览", null, emptySet(), 1).size)
    }
    @Test fun oversizedSingleMessageIsBoundedAndMarked() {
        val text = ConversationMention.transcript(listOf(AgentMessageUi("a", "x".repeat(20_000) + "结尾")), 150)
        assertTrue(text.length <= 150)
        assertTrue(text.startsWith("[已截取："))
        assertTrue(text.endsWith("结尾"))
        assertEquals("", ConversationMention.transcript(listOf(AgentMessageUi("a", "text")), 0))
    }
    @Test fun recentMessagesKeptInOriginalOrder() {
        val text = ConversationMention.transcript(listOf(UserMessageUi("1", "开头"), AgentMessageUi("2", "回答")))
        assertEquals("User: 开头\n\nAssistant: 回答", text)
    }
    @Test fun quotedReferencesNeverRecursivelyExpand() {
        val content = AgentFileReferencePromptCodec.format("参考它", emptyList(), listOf(MentionedConversation("id", "旧会话", "do not recursively copy me")))
        val text = ConversationMention.transcript(listOf(UserMessageUi("u", content)))
        assertTrue(text.contains("旧会话"))
        assertTrue(text.contains("参考它"))
        assertFalse(text.contains("do not recursively copy me"))
    }
    @Test fun toolEvidenceDoesNotLeakRawSensitiveResults() {
        val text = ConversationMention.transcript(listOf(ToolActivityMessageUi(
            id = "t", toolName = "wifi_credentials", status = ToolActivityStatusUi.Success,
            argumentsSummary = "secret arguments", resultSummary = "secret password",
        )))
        assertTrue(text.contains("wifi_credentials"))
        assertTrue(text.contains("Success"))
        assertFalse(text.contains("secret"))
    }
    @Test fun terminalNoticeIsRetained() {
        val text = ConversationMention.transcript(listOf(SystemNoticeMessageUi("s", SystemNoticeCode.Stopped)))
        assertTrue(text.contains("Stopped"))
    }
    @Test fun totalBudgetIncludesAttachedSnapshots() {
        assertEquals(1_000, ConversationMention.remainingTranscriptBudget(listOf(
            PendingConversationMentionUi("1", "one", "one", "x".repeat(15_000)),
        )))
    }
}
