package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 子代理完成通知的三条硬约束（方案 §2 修正 1b）：
 * 只到模型、不落盘、不占压缩配额，且绝不进入 transcript 外流路径。
 */
class AgentChildNoticeDisciplineTest {
    private fun notice(body: String) = AgentContextCompactor.childNoticeUserContent(body)

    @Test
    fun childNoticeIsNotCountedAsAUserTurn() {
        val injected = notice("task_id=t1 status=completed 正文")
        assertTrue(AgentContextCompactor.isChildNoticeUserMessage(
            AgentModelClient.ConversationMessage("user", injected)))
        assertFalse(AgentContextCompactor.isChildNoticeUserMessage(
            AgentModelClient.ConversationMessage("user", "普通用户消息")))
        assertFalse(AgentContextCompactor.isChildNoticeUserMessage(
            AgentModelClient.ConversationMessage("assistant", injected)))

        // 通知正好是最后一条 user 消息时，它不能吃掉 keep 配额：
        // keep=1 必须落到真正的用户轮次（index 0），而不是被通知顶到 index 2。
        val history = listOf(
            AgentModelClient.ConversationMessage("user", "真实用户轮次"),
            AgentModelClient.ConversationMessage("assistant", "回答"),
            AgentModelClient.ConversationMessage("user", injected),
        )
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(history, 1))
    }

    @Test
    fun childNoticeNeverReachesTranscriptOrDurablePrefix() {
        val body = "task_id=t1 status=completed 结论：子代理敏感正文"
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", notice(body)))
            .put(JSONObject().put("role", "user").put("content", "普通用户消息"))
            .put(JSONObject().put("role", "assistant").put("content", "reply"))

        // 关键：sensitiveToolCallIds 为空也必须剔除通知。通知的临时性不依赖工具敏感标记，
        // 因此不能落在早退分支里；而且必须整条剔除，不能替换成占位文本（占位消息会被持久化并回传模型）。
        val transcript = AgentConversationCodec.transcript(messages, 0, emptySet())
        val encoded = transcript.joinToString { it.content }
        assertFalse(encoded.contains("子代理敏感正文"))
        assertEquals(2, transcript.size)
        assertFalse(transcript.any { it.content.contains(AgentContextCompactor.CHILD_NOTICE_USER_PREFIX) })
        assertTrue(transcript.any { it.content == "普通用户消息" })

        // 压缩存档前缀走同一条路径（redactSensitiveMessages），空敏感集时也必须剔除。
        val durablePrefix = AgentConversationCodec.redactSensitiveMessages(
            listOf(AgentModelClient.ConversationMessage("user", notice(body))), emptySet())
        assertTrue(durablePrefix.isEmpty())
    }

    @Test
    fun mergeKeepsOneMessagePerRoundAndReportsOmittedNoticeCount() {
        assertEquals("A\n\nB", AgentChildNotice.merge(listOf("A", "B")))

        val large = "x".repeat(AgentChildNotice.BUDGET_CHARS)
        val merged = AgentChildNotice.merge(listOf(large, "second", "third"))
        assertTrue(merged.startsWith(large))
        assertFalse(merged.contains("second"))
        assertTrue(merged.contains("还有 2 条"))
    }
}
