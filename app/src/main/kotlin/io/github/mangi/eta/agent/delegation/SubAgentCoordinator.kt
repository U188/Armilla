package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Owned by a single parent run. Terminal state cannot be overwritten by a late worker. */
internal class SubAgentCoordinator(
    private val workers: List<AgentModelClient.ModelConfig>,
    private val timeoutMs: Long = 180_000,
    private val executeChild: (AgentModelClient.ModelConfig, String, AgentRunController) -> String,
) : AutoCloseable {
    init { require(workers.isNotEmpty() && workers.size <= 2) }

    private class Task(val id: String, val worker: Int) {
        val controller = AgentRunController()
        @Volatile var state = "running"
        @Volatile var result = ""
        @Volatile var future: Future<*>? = null
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val timer = Executors.newSingleThreadScheduledExecutor()
    private val tasks = linkedMapOf<String, Task>()
    private var closed = false

    fun execute(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult {
        val json = try {
            val args = JSONObject(call.argumentsJson)
            when (call.name) {
                "delegate_task" -> start(args)
                "get_task_result" -> get(args)
                "cancel_task" -> {
                    val task = find(args.getString("task_id"))
                    stop(task, "cancelled")
                    snapshot(task)
                }
                else -> error("Unknown delegation tool")
            }
        } catch (_: IllegalArgumentException) {
            JSONObject().put("ok", false).put("code", "INVALID_TASK_ARGUMENTS")
        } catch (_: org.json.JSONException) {
            JSONObject().put("ok", false).put("code", "INVALID_TASK_ARGUMENTS")
        }
        // Child evidence may contain sensitive tool output. Do not persist raw returned text.
        return AgentModelClient.ToolResult(json.toString(), sensitive = true)
    }

    @Synchronized private fun start(args: JSONObject): JSONObject {
        if (closed) return errorResult("RUN_CLOSED")
        if (tasks.values.count { it.state == "running" } >= 2) return errorResult("SUB_AGENT_LIMIT")
        if (tasks.size >= 16) return errorResult("SUB_AGENT_TASK_BUDGET")
        val instruction = args.getString("task")
        val context = args.optString("context")
        require(instruction.isNotBlank() && instruction.length <= 12000 && context.length <= 20000)
        val worker = if (args.has("worker")) args.getInt("worker") - 1 else tasks.size % workers.size
        require(worker in workers.indices)
        val task = Task(UUID.randomUUID().toString(), worker)
        tasks[task.id] = task
        task.future = pool.submit {
            try {
                val answer = executeChild(workers[worker], "Task:\n$instruction\n\nContext supplied by main agent:\n$context", task.controller)
                synchronized(task) {
                    if (task.state == "running") {
                        task.result = answer.take(16000) + if (answer.length > 16000) "\n[结果已截断]" else ""
                        task.state = "completed"
                    }
                }
            } catch (_: Exception) {
                synchronized(task) {
                    if (task.state == "running") { task.result = "子代理未完成，请主代理接手或重新委派。"; task.state = "failed" }
                }
            }
        }
        timer.schedule({ stop(task, "timed_out") }, timeoutMs, TimeUnit.MILLISECONDS)
        return snapshot(task)
    }
    @Synchronized private fun find(id: String): Task = requireNotNull(tasks[id]) { "Task does not belong to this run" }
    private fun get(args: JSONObject): JSONObject {
        val task = find(args.getString("task_id"))
        val wait = args.optLong("wait_ms", 0).coerceIn(0, 10000)
        if (wait > 0 && task.state == "running") {
            try { task.future?.get(wait, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { }
            catch (_: java.util.concurrent.CancellationException) { }
            catch (_: java.util.concurrent.ExecutionException) { }
        }
        return snapshot(task)
    }
    private fun stop(task: Task, state: String) {
        synchronized(task) {
            if (task.state != "running") return
            task.state = state
        }
        task.controller.cancel()
        task.future?.cancel(true)
    }
    private fun snapshot(task: Task): JSONObject = synchronized(task) {
        JSONObject().put("ok", true).put("task_id", task.id).put("worker", task.worker + 1)
            .put("status", task.state).put("result", task.result)
            .put("review_required", true)
    }
    private fun errorResult(code: String) = JSONObject().put("ok", false).put("code", code)
    @Synchronized override fun close() {
        closed = true
        tasks.values.forEach { stop(it, "cancelled") }
        pool.shutdownNow()
        timer.shutdownNow()
    }
}
