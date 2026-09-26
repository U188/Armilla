package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/** Bounded allowlisted operational evidence. No prompts, deltas, reasoning, arguments or tool results. */
internal class SubAgentEventJournal(private val capacity: Int = 64, private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    init { require(capacity > 0) }
    private val events = ArrayDeque<JSONObject>()
    private var sequence = 0L
    private var revision = 0L
    var lastProgressMs: Long = now()
        private set
    var lastDataMs: Long = now()
        private set
    var lastHeartbeatMs: Long = now()
        private set
    var phase: String = "queued"
        private set
    var checkpoint: String = ""
        private set

    @Synchronized fun mark(kind: String, tool: String = "", progress: Boolean = false, data: Boolean = false) {
        val at = now()
        if (kind == "heartbeat") lastHeartbeatMs = at else phase = kind
        if (progress) lastProgressMs = at
        if (data) lastDataMs = at
        val item = JSONObject().put("seq", ++sequence).put("at_ms", at).put("stage", kind)
        if (tool.isNotBlank()) item.put("tool", tool.takeIf { it == "workspace_file" || SubAgentTools.allows(it) } ?: "other")
        if (events.size >= capacity) events.removeFirst()
        events.addLast(item)
        revision++
        (this as java.lang.Object).notifyAll()
    }
    /** Same predicate feeds the journal AND the coordinator watchdog. */
    @Synchronized fun accept(event: AgentEvent): Boolean {
        val progress = when (event) {
            is AgentEvent.ToolFinished -> event.success == true && event.name != "report_task_progress"
            is AgentEvent.HostedToolFinished -> event.success
            is AgentEvent.ContextCompacted -> event.applied
            is AgentEvent.AssistantBlockDelta -> event.kind == AgentEvent.AssistantBlockKind.TEXT && event.deltaChars > 0
            else -> false
        }
        when (event) {
            is AgentEvent.ToolStarted -> mark("tool_started", event.name, data = true)
            is AgentEvent.ToolFinished -> mark(if (event.success == true) "tool_finished" else "tool_failed", event.name, progress = progress, data = true)
            is AgentEvent.HostedToolFinished -> mark(if (event.success) "tool_finished" else "tool_failed", event.name, progress = progress, data = true)
            is AgentEvent.ProviderRequestStarted -> mark("provider_request", data = true)
            is AgentEvent.ProviderResponseStarted -> mark("provider_response", data = true)
            is AgentEvent.AssistantBlockDelta -> if (progress) mark("text_progress", progress = true, data = true)
            is AgentEvent.ContextCompacted -> mark("compaction_finished", progress = progress, data = true)
            is AgentEvent.ContextCompactionStarted -> mark("compaction_started", data = true)
            else -> Unit
        }
        return progress
    }
    /** A checkpoint is an explicit high-level report, not inferred from arbitrary model text. */
    @Synchronized fun setCheckpoint(text: String) {
        checkpoint = text.take(1000)
        mark("checkpoint", data = true)
    }
    @Synchronized fun page(after: Long, limit: Int): JSONObject {
        val cursor = after.coerceAtLeast(0)
        val oldest = events.firstOrNull()?.getLong("seq") ?: sequence + 1
        val items = events.filter { it.getLong("seq") > cursor }.take(limit.coerceIn(1, 32))
        return JSONObject().put("events", JSONArray(items)).put("oldest_seq", oldest)
            .put("next_seq", items.lastOrNull()?.getLong("seq") ?: maxOf(cursor, oldest - 1))
            .put("latest_seq", sequence).put("truncated", cursor < oldest - 1)
            .put("phase", phase).put("last_progress_ms", lastProgressMs)
            .put("last_data_ms", lastDataMs).put("last_heartbeat_ms", lastHeartbeatMs).put("checkpoint", checkpoint)
    }
    /** Wake for operational events or task state changes, not merely Future completion. */
    @Synchronized fun awaitPage(after: Long, limit: Int, waitMs: Long, stillWaiting: () -> Boolean = { true }): JSONObject {
        val deadline = System.nanoTime() + waitMs.coerceIn(0, 10_000) * 1_000_000
        val initial = revision
        while (sequence <= after && revision == initial && stillWaiting()) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            if (remaining <= 0) break
            try { (this as java.lang.Object).wait(remaining.coerceAtLeast(1)) }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        return page(after, limit)
    }
    @Synchronized fun clear() { events.clear(); checkpoint = ""; revision++; (this as java.lang.Object).notifyAll() }
}
