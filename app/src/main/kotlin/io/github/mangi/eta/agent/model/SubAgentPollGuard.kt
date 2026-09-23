package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import org.json.JSONArray
import org.json.JSONObject

/**
 * 轮询退避门禁：只约束主代理对**仍在 running** 的子任务反复查询。
 *
 * 设计要点（与既有 `AgentDelegationArgumentRepair` 同形状，但不复制其永久失效语义）：
 * - `reject()` 只拒绝，不执行工具，也不伪造结果；拒绝时返回明确的错误码与建议等待毫秒。
 * - **终态永久放行**：只有上一次观测到的状态是 `running` 才计数，避免阻断结果获取。
 * - 不带 `task_id` 的列表查询、`cancel_task` / `continue_task` 完全不受影响。
 * - `disabled` 语义改为**有时限的挂起**（[REMOVE_FOR_MS]）：摘除工具只是让主代理冷静一下，
 *   到点自动恢复，绝不会永久挡住结果取回。
 * - **不做长阻塞**：不改变 `wait_ms ≤ 10000` 的既有上限。这是与参考文章的刻意偏离：
 *   手机端不能让用户等待 16 分钟。
 *
 * 可用性开关：`Prefs` 的 `agent_subagent_poll_guard`（默认开启，设为 `false` 整体回退为纯轮询）。
 */
internal class SubAgentPollGuard(
    private val enabled: Boolean = true,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Entry(
        var lastAt: Long,
        var level: Int = 0,
        var strikes: Int = 0,
        var running: Boolean = false,
    )

    data class Rejection(val code: String, val message: String, val nextPollAfterMs: Long)

    private val entries = mutableMapOf<String, Entry>()

    /** 被拒绝的查询总次数，供度量基线使用。 */
    var rejections: Int = 0
        private set

    private var suspendedUntil: Long = 0

    /** 工具是否处于冷静期（此时从本轮目录里摘除）。 */
    val suspended: Boolean
        get() = enabled && now() < suspendedUntil

    fun availableTools(catalog: JSONArray): JSONArray {
        if (!suspended) return catalog
        return JSONArray().also { filtered ->
            for (index in 0 until catalog.length()) {
                val entry = catalog.get(index)
                if ((entry as? JSONObject)?.optJSONObject("function")?.optString("name") != TOOL) filtered.put(entry)
            }
        }
    }

    /** @return null 表示放行。 */
    fun reject(call: AgentModelClient.ToolCall): Rejection? {
        if (!enabled || call.name != TOOL) return null
        val taskId = taskId(call) ?: return null
        val entry = entries[taskId] ?: return null
        if (!entry.running) return null
        val wait = LEVELS[entry.level.coerceIn(0, LEVELS.lastIndex)]
        val elapsed = (now() - entry.lastAt).coerceAtLeast(0)
        if (elapsed >= wait) return null
        val remaining = wait - elapsed
        entry.strikes += 1
        rejections += 1
        entry.level = (entry.level + 1).coerceAtMost(LEVELS.lastIndex)
        // 连续违规才短时摘除工具，给主代理一个不再空转的明确信号；到点自动恢复。
        if (entry.strikes >= MAX_STRIKES) suspendedUntil = now() + REMOVE_FOR_MS
        return Rejection(CODE, message(remaining), remaining)
    }

    /** 观察一次真正执行的查询结果；只有它才更新状态与节奏。 */
    fun observe(call: AgentModelClient.ToolCall, result: AgentModelClient.ToolResult) {
        if (!enabled || call.name != TOOL) return
        val taskId = taskId(call)
        val parsed = runCatching { JSONObject(result.content) }.getOrNull()
        if (taskId == null) {
            // 列表查询是重新发现 task_id 的合法手段，不参与退避。
            return
        }
        val status = parsed?.optString("status").orEmpty()
        val entry = entries.getOrPut(taskId) { Entry(now()) }
        entry.lastAt = now()
        entry.running = status == "running" || status == "queued"
        if (!entry.running) {
            // 终态放行：清掉节奏与违规计数，避免过时状态继续限制后续查询。
            entry.level = 0
            entry.strikes = 0
        }
    }

    private fun taskId(call: AgentModelClient.ToolCall): String? =
        runCatching { JSONObject(call.argumentsJson).optString("task_id").takeIf { it.isNotBlank() } }.getOrNull()

    private fun message(remainingMs: Long): String =
        "子任务仍在运行，同一任务的连续查询过于频繁。请等待约 ${remainingMs} 毫秒后重试，" +
            "或改为继续做其它工作；子任务完成时会主动通知，不需要空转查询。" +
            "终态任务与不带 task_id 的列表查询不受此限制。"

    companion object {
        const val TOOL = "get_task_result"
        const val CODE = "POLLING_TOO_FREQUENT"
        const val PREF_KEY = "agent_subagent_poll_guard"
        const val MAX_STRIKES = 3
        const val REMOVE_FOR_MS = 60_000L

        /** 1 → 2 → 4 → 8 → 16 分钟，到顶保持。 */
        val LEVELS = longArrayOf(60_000L, 120_000L, 240_000L, 480_000L, 960_000L)

        fun enabled(): Boolean =
            runCatching { Prefs.getString(PREF_KEY, "true") != "false" }.getOrDefault(true)
    }
}
