package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SubAgentCompletionNoticeTest {
    private val model = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test", model = "child", systemPrompt = "")
    private fun call(name: String, args: JSONObject) = AgentModelClient.ToolCall("call", name, args.toString())
    private fun start(c: SubAgentCoordinator) = JSONObject(c.execute(call("delegate_task", JSONObject().put("task", "check evidence"))).content)
    private fun get(c: SubAgentCoordinator, id: String, wait: Int = 1000) = JSONObject(c.execute(call("get_task_result", JSONObject().put("task_id", id).put("wait_ms", wait))).content)

    @Test fun longReportKeepsHeadAndConclusionTail() {
        val body = "H".repeat(9_000) + "MIDDLE".repeat(2_000) + "结论在尾部：TCO=42"
        SubAgentCoordinator(listOf(model)) { _, _, _ -> body }.use { c ->
            val result = get(c, start(c).getString("task_id")).getString("result")
            assertTrue(result.length < 16_100)
            assertTrue(result.startsWith("H".repeat(256)))
            assertTrue(result.endsWith("结论在尾部：TCO=42"))
            assertTrue(result.contains("结果中段已省略"))
        }
    }

    @Test fun shortReportIsNotTruncated() {
        SubAgentCoordinator(listOf(model)) { _, _, _ -> "short report" }.use { c ->
            assertEquals("short report", get(c, start(c).getString("task_id")).getString("result"))
        }
    }

    @Test fun terminalStateNotifiesTheParentExactlyOnce() {
        val completions = CopyOnWriteArrayList<SubAgentCompletion>()
        SubAgentCoordinator(listOf(model), onCompleted = { completions += it }) { _, _, _ -> "done" }.use { c ->
            val id = start(c).getString("task_id")
            assertEquals("completed", get(c, id).getString("status"))
            assertEquals(1, completions.size)
            val completion = completions.first()
            assertEquals(id, completion.taskId)
            assertEquals("completed", completion.status)
            assertEquals("done", completion.result)
            assertEquals("research", completion.role)
            assertTrue(SubAgentNotice.text(completion).contains("task_id=$id"))
        }
    }

    @Test fun cancellationNotifiesOnceWithTerminalStatus() {
        val completions = CopyOnWriteArrayList<SubAgentCompletion>()
        val entered = CountDownLatch(1)
        val block = CountDownLatch(1)
        SubAgentCoordinator(listOf(model), onCompleted = { completions += it }) { _, _, controller ->
            entered.countDown()
            try { block.await() } catch (_: InterruptedException) { }
            controller.throwIfCancelled()
            "late"
        }.use { c ->
            val id = start(c).getString("task_id")
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            JSONObject(c.execute(call("cancel_task", JSONObject().put("task_id", id))).content)
            assertEquals("cancelled", get(c, id, 0).getString("status"))
            // worker 被 future.cancel 打断后走 cancelled 出口；通知必须恰好一次，绝不能报成 failed。
            assertEquals(1, completions.size)
            assertEquals("cancelled", completions.first().status)
        }
    }
}
