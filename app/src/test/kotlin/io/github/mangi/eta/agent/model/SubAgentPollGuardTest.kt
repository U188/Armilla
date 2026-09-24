package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentPollGuardTest {
    private var clock = 0L
    private fun guard(enabled: Boolean = true) = SubAgentPollGuard(enabled) { clock }
    private fun call(taskId: String?) = AgentModelClient.ToolCall("c", SubAgentPollGuard.TOOL,
        JSONObject().also { if (taskId != null) it.put("task_id", taskId) }.toString())
    private fun result(status: String) =
        AgentModelClient.ToolResult(JSONObject().put("ok", true).put("status", status).toString())

    private fun catalog() = JSONArray()
        .put(JSONObject().put("function", JSONObject().put("name", SubAgentPollGuard.TOOL)))
        .put(JSONObject().put("function", JSONObject().put("name", "delegate_task")))

    @Test fun runningPollIsRejectedWithSuggestedDelayAndTerminalPollIsAlwaysAllowed() {
        val guard = guard()
        assertNull(guard.reject(call("t1")))
        guard.observe(call("t1"), result("running"))
        clock = 10_000
        val rejection = guard.reject(call("t1"))
        assertNotNull(rejection)
        assertEquals(SubAgentPollGuard.CODE, rejection!!.code)
        assertEquals(50_000L, rejection.nextPollAfterMs)
        assertTrue(rejection.message.contains(rejection.nextPollAfterMs.toString()))
        // 已到达建议时刻后放行（当前 clock 已越过下一次允许查询的时刻）。
        clock = 130_000
        assertNull(guard.reject(call("t1")))
        // 终态永久放行，并清掉节奏与违规计数。
        guard.observe(call("t1"), result("completed"))
        clock = 131_000
        assertNull(guard.reject(call("t1")))
    }

    @Test fun listQueryUnknownTaskAndOtherToolsAreNeverConstrained() {
        val guard = guard()
        guard.observe(call("t1"), result("running"))
        clock = 1_000
        assertNull(guard.reject(call(null)))
        assertNull(guard.reject(AgentModelClient.ToolCall("c", "delegate_task", "{}")))
        assertNull(guard.reject(AgentModelClient.ToolCall("c", SubAgentPollGuard.TOOL, "not-json")))
    }

    @Test fun repeatedViolationsSuspendTheToolOnlyForABoundedWindow() {
        val guard = guard()
        guard.observe(call("t1"), result("running"))
        repeat(SubAgentPollGuard.MAX_STRIKES) { assertNotNull(guard.reject(call("t1"))) }
        assertTrue(guard.suspended)
        val filtered = guard.availableTools(catalog())
        assertEquals(1, filtered.length())
        assertEquals("delegate_task", filtered.getJSONObject(0).getJSONObject("function").getString("name"))
        // 摘除只是冷静期，到点必须自动恢复，绝不永久挡住结果取回。
        clock += SubAgentPollGuard.REMOVE_FOR_MS
        assertFalse(guard.suspended)
        assertEquals(2, guard.availableTools(catalog()).length())
    }

    @Test fun disabledGuardNeverInterferes() {
        val guard = guard(enabled = false)
        guard.observe(call("t1"), result("running"))
        clock = 1
        assertNull(guard.reject(call("t1")))
        assertFalse(guard.suspended)
    }

    @Test fun callerThatWaitsTheSuggestedDelayIsNeverSuspended() {
        val guard = guard()
        guard.observe(call("t1"), result("running"))
        // 首次窗口 60s。反复「等到建议时刻再查」，绝不应触发连续违规摘除。
        repeat(SubAgentPollGuard.MAX_STRIKES + 2) {
            clock += 30_000
            val rejection = guard.reject(call("t1"))
            assertNotNull(rejection)
            // 等满被拒时返回的剩余时间后再查，应被放行。
            clock += rejection!!.nextPollAfterMs
            assertNull(guard.reject(call("t1")))
            // 放行后必须再观察一次真实结果，刷新下一次允许查询的时刻。
            guard.observe(call("t1"), result("running"))
            assertFalse(guard.suspended)
        }
    }
}
