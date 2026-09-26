package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.delegation.SubAgentCoordinator
import io.github.mangi.eta.agent.model.AgentModelClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Process-local child ownership. No recovery is claimed after process death. */
internal object AgentChildTaskGroups {
    data class StopTarget(val ownerId: String, val generations: Set<String>)
    private class Group(
        val ownerId: String, val runId: String, val generation: String, val leaseId: String,
        val coordinator: SubAgentCoordinator, val releaseTools: () -> Unit,
    ) {
        var attached = true
        var closed = false
        var leaseHeld = true
    }
    private const val MAX_OLD_GROUPS_PER_OWNER = 8
    private val groups = linkedMapOf<String, Group>()
    private val changes = MutableStateFlow(0L)
    val revision: StateFlow<Long> = changes
    private fun changed() { changes.value = changes.value + 1 }
    private fun releaseChild(block: () -> Unit) = AgentChildToolOwnership.releaseChild(block)

    /** A child may not be dispatched unless it has its OWN FGS lease, keyed by a fresh generation. */
    fun register(context: Context, ownerId: String, runId: String, coordinator: SubAgentCoordinator, releaseTools: () -> Unit): String? {
        val generation = UUID.randomUUID().toString()
        val leaseId = "child:$generation"
        val stopRequested = AtomicBoolean(false)
        if (!AgentExecutionService.acquire(context, leaseId, onStop = {
            stopRequested.set(true)
            stopGeneration(generation)
        })) {
            releaseChild(releaseTools)
            return null
        }
        synchronized(this) {
            groups[generation] = Group(ownerId, runId, generation, leaseId, coordinator, releaseTools)
            changed()
        }
        if (stopRequested.get()) stopGeneration(generation)
        // Foreground rejection can race registration. Reject delegation if its callback won.
        return synchronized(this) { generation.takeIf { groups[it]?.closed == false } }
    }
    fun hasActive(ownerId: String): Boolean {
        val snapshot = synchronized(this) { groups.values.filter { it.ownerId == ownerId && !it.closed }.toList() }
        return snapshot.any { it.coordinator.hasActiveTasks() }
    }
    fun captureStopTarget(ownerId: String): StopTarget? = synchronized(this) {
        val ids = groups.values.filter { it.ownerId == ownerId && !it.closed }.mapTo(linkedSetOf()) { it.generation }
        if (ids.isEmpty()) null else StopTarget(ownerId, ids)
    }
    fun stop(target: StopTarget): Boolean {
        val selected = synchronized(this) { groups.values.filter { it.ownerId == target.ownerId && it.generation in target.generations && !it.closed }.toList() }
        selected.forEach(::close)
        return selected.isNotEmpty()
    }
    fun stopAll() { synchronized(this) { groups.values.filter { !it.closed }.toList() }.forEach(::close) }
    fun stopRun(runId: String): Boolean {
        val selected = synchronized(this) { groups.values.filter { it.runId == runId && !it.closed }.toList() }
        selected.forEach(::close)
        return selected.isNotEmpty()
    }
    private fun stopGeneration(generation: String) { synchronized(this) { groups[generation] }?.let(::close) }

    /** Parent exit relinquishes only its admission path; active children keep their tools and lease. */
    fun detach(generation: String) {
        synchronized(this) { groups[generation]?.also { it.attached = false; changed() } }
        onTaskChanged(generation)
    }
    /** Invoked at queued admission, every terminal transition, and worker exit. */
    fun onTaskChanged(generation: String) {
        val group = synchronized(this) { groups[generation] } ?: return
        val active = group.coordinator.hasActiveTasks()
        val release = synchronized(this) {
            if (groups[generation] !== group || group.closed) return@synchronized false
            changed()
            if (!group.attached && !active && group.leaseHeld) {
                group.leaseHeld = false
                true
            } else false
        }
        if (release) {
            AgentExecutionService.release(group.leaseId)
            runCatching { releaseChild(group.releaseTools) }
            prune(group.ownerId)
        }
    }
    private fun prune(ownerId: String) {
        val victims = synchronized(this) {
            groups.values.filter { it.ownerId == ownerId && !it.attached && !it.leaseHeld && !it.closed }
                .dropLast(MAX_OLD_GROUPS_PER_OWNER).also { expired ->
                    expired.forEach { groups.remove(it.generation); it.closed = true }
                    if (expired.isNotEmpty()) changed()
                }
        }
        victims.forEach { runCatching { it.coordinator.close() } }
    }
    private fun close(group: Group) {
        val release = synchronized(this) {
            if (group.closed) return
            group.closed = true
            groups.remove(group.generation)
            val held = group.leaseHeld
            group.leaseHeld = false
            changed()
            held
        }
        try { group.coordinator.close() } finally {
            if (release) AgentExecutionService.release(group.leaseId)
            runCatching { releaseChild(group.releaseTools) }
        }
    }

    /** Task IDs remain on their frozen original model; new delegates use only the current group. */
    fun execute(ownerId: String, currentGeneration: String?, call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrNull() ?: return error("INVALID_TASK_ARGUMENTS")
        val id = args.optString("task_id")
        if (call.name == "get_task_result" && id.isBlank()) return list(ownerId, args)
        val candidates = synchronized(this) { groups.values.filter { it.ownerId == ownerId && !it.closed }.toList() }
        val group = if (id.isNotBlank()) candidates.firstOrNull { it.coordinator.ownsTask(id) }
            else candidates.firstOrNull { it.generation == currentGeneration && it.attached }
        return group?.coordinator?.execute(call) ?: error(if (id.isNotBlank()) "TASK_NOT_FOUND" else "RUN_CLOSED")
    }
    fun requestCompact(ownerId: String, id: String, keep: Int?, model: AgentModelClient.ModelConfig?): Boolean {
        val coordinators = synchronized(this) { groups.values.filter { it.ownerId == ownerId && !it.closed }.map { it.coordinator } }
        return coordinators.firstOrNull { it.ownsTask(id) }?.requestCompact(id, keep, model) ?: false
    }
    private fun list(ownerId: String, args: JSONObject): AgentModelClient.ToolResult {
        val offset = args.optInt("offset", 0).coerceIn(0, 10_000)
        val coordinators = synchronized(this) { groups.values.filter { it.ownerId == ownerId && !it.closed }.map { it.coordinator } }
        val ids = coordinators.flatMap { it.taskIds() }.take(10_000)
        val page = JSONArray()
        for (id in ids.drop(offset).take(20)) {
            val coordinator = coordinators.firstOrNull { it.ownsTask(id) } ?: continue
            val result = runCatching {
                JSONObject(coordinator.execute(AgentModelClient.ToolCall("list-$id", "get_task_result", JSONObject().put("task_id", id).toString())).content)
            }.getOrNull() ?: continue
            page.put(JSONObject().put("task_id", id).put("status", result.optString("status"))
                .put("role", result.optString("role")).put("agent_id", result.optString("agent_id")))
        }
        return AgentModelClient.ToolResult(JSONObject().put("ok", true).put("tasks", page)
            .put("total", ids.size).put("next_offset", if (offset + page.length() < ids.size) offset + page.length() else JSONObject.NULL)
            .toString(), sensitive = true)
    }
    private fun error(code: String) = AgentModelClient.ToolResult(JSONObject().put("ok", false).put("code", code).toString(), sensitive = true)
}
