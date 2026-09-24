package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.MentionedConversation
import org.junit.Assert.*
import org.junit.Test
import java.io.File

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
        val text = ConversationMention.transcript(listOf(AgentMessageUi("a", "开头" + "x".repeat(20_000) + "结尾")), 180)
        assertTrue(text.length <= 180)
        assertTrue(text.contains("开头"))
        assertTrue(text.contains("结尾"))
        assertTrue(text.contains("中间记录已省略"))
        assertEquals("", ConversationMention.transcript(listOf(AgentMessageUi("a", "text")), 0))
    }
    @Test fun fullConversationIsKeptInOriginalOrder() {
        val text = ConversationMention.transcript(listOf(UserMessageUi("1", "开头"), AgentMessageUi("2", "回答")))
        assertEquals("User: 开头\n\nAssistant: 回答", text)
        assertFalse(text.contains("已截取"))
    }
    @Test fun overflowKeepsStartAndEnd() {
        val messages = (1..40).map { index ->
            if (index % 2 == 1) UserMessageUi("$index", "问题$index")
            else AgentMessageUi("$index", "回答$index")
        }
        val text = ConversationMention.transcript(messages, 120)
        assertTrue(text.contains("问题1"))
        assertTrue(text.contains("回答40"))
        assertFalse(text.contains("问题20"))
        assertTrue(text.contains("中间记录已省略"))
    }
    @Test fun thinkingAndToolSummaryAreIncluded() {
        val text = ConversationMention.transcript(listOf(
            ThinkingMessageUi("t", "先看上下文", isStreaming = false),
            ToolSummaryMessageUi("s", listOf("read_file", "terminal")),
            AgentMessageUi("a", "结论"),
        ))
        assertTrue(text.contains("Thinking: 先看上下文"))
        assertTrue(text.contains("Tools: read_file, terminal"))
        assertTrue(text.contains("Assistant: 结论"))
    }
    @Test fun quotedReferencesNeverRecursivelyExpand() {
        val content = AgentFileReferencePromptCodec.format("参考它", emptyList(), listOf(MentionedConversation("id", "旧会话", "do not recursively copy me")))
        val text = ConversationMention.transcript(listOf(UserMessageUi("u", content)))
        assertTrue(text.contains("旧会话"))
        assertTrue(text.contains("参考它"))
        assertFalse(text.contains("do not recursively copy me"))
    }
    @Test fun toolEvidenceIncludesArgumentsAndResults() {
        val text = ConversationMention.transcript(listOf(ToolActivityMessageUi(
            id = "t",
            toolName = "read_file",
            status = ToolActivityStatusUi.Success,
            argumentsSummary = "path=/workspace/Armilla/README.md",
            command = "cat README.md",
            resultSummary = "# Armilla",
            imageCount = 2,
        )))
        assertTrue(text.contains("read_file"))
        assertTrue(text.contains("Success"))
        assertTrue(text.contains("path=/workspace/Armilla/README.md"))
        assertTrue(text.contains("cat README.md"))
        assertTrue(text.contains("# Armilla"))
        assertTrue(text.contains("Images: 2"))
        assertFalse(text.contains("不含原始参数或结果"))
    }

    @Test fun toolEvidenceWritesDetailsToWorkspaceFileWhenFilesDirIsProvided() {
        val filesDir = File.createTempFile("mention-files", null).apply {
            delete()
            mkdirs()
        }
        val text = ConversationMention.transcript(
            listOf(ToolActivityMessageUi(
                id = "tool-1",
                toolName = "read_file",
                status = ToolActivityStatusUi.Success,
                argumentsSummary = "path=/workspace/Armilla/README.md",
                command = "cat README.md",
                resultSummary = "# Armilla",
            )),
            filesDir = filesDir,
            conversationId = "conv-a",
        )
        assertTrue(text.contains("Details file:"))
        assertTrue(text.contains("conv-a"))
        assertTrue(text.contains("快照缓存/tools"))
        assertTrue(text.contains("read_file"))
        assertFalse(text.contains("path=/workspace/Armilla/README.md"))
        val file = File(text.substringAfter("Details file: ").substringBefore('\n'))
        assertTrue(file.isFile)
        val details = file.readText()
        assertTrue(details.contains("path=/workspace/Armilla/README.md"))
        assertTrue(details.contains("cat README.md"))
        assertTrue(details.contains("# Armilla"))
        filesDir.deleteRecursively()
    }

    @Test fun toolEvidenceOmitsBlankOptionalFields() {
        val text = ConversationMention.transcript(listOf(ToolActivityMessageUi(
            id = "t",
            toolName = "search_files",
            status = ToolActivityStatusUi.Running,
            argumentsSummary = " ",
        )))
        assertEquals("Tool search_files: Running", text)
    }
    @Test fun terminalNoticeIsRetained() {
        val text = ConversationMention.transcript(listOf(SystemNoticeMessageUi("s", SystemNoticeCode.Stopped)))
        assertTrue(text.contains("Stopped"))
    }
    @Test fun totalBudgetIncludesAttachedSnapshots() {
        assertEquals(1_000, ConversationMention.remainingTranscriptBudget(listOf(
            PendingConversationMentionUi("1", "one", "one", "x".repeat(ConversationMention.MAX_TOTAL_CHARS - 1_000)),
        )))
    }
}
