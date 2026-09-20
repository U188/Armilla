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
    private val roles: List<String> = List(workers.size) { "research" },
    private val workspace: SubAgentWorkspace? = null,
    private val executeWorkspaceChild: ((AgentModelClient.ModelConfig, String, AgentRunController, String, String, Boolean) -> String)? = null,
    private val executeChild: (AgentModelClient.ModelConfig, String, AgentRunController) -> String,
) : AutoCloseable {
    init { require(workers.isNotEmpty() && workers.size <= 2 && roles.size == workers.size) }

    private class Task(val id: String, val worker: Int, val role: String, val project: String, @Volatile var workspaceId: String? = null) {
        @Volatile var workspacePath = ""
        @Volatile var executing = false

        val controller = AgentRunController()
        @Volatile var state = "running"
        @Volatile var result = ""
        @Volatile var errorCode = ""
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
                "manage_agent_workspace" -> manage(args)
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
        if (tasks.values.count { it.state == "running" || it.executing } >= 2) return errorResult("SUB_AGENT_LIMIT")
        if (tasks.size >= 16) return errorResult("SUB_AGENT_TASK_BUDGET")
        val instruction = args.getString("task")
        val context = args.optString("context")
        require(instruction.isNotBlank() && instruction.length <= 12000 && context.length <= 20000)
        val role = args.optString("role", "research")
        require(role in setOf("research", "implementation", "review", "summary"))
        val worker = if (args.has("worker")) args.getInt("worker") - 1 else {
            val desired = if (role == "summary") "review" else role
            roles.indexOf(desired).takeIf { it >= 0 } ?: if (role == "research") tasks.size % workers.size else return errorResult("ROLE_NOT_CONFIGURED")
        }
        require(worker in workers.indices)
        if (role != "research" && roles[worker] != (if (role == "summary") "review" else role)) return errorResult("WORKER_ROLE_MISMATCH")
        val project = args.optString("project")
        val workspaceId = args.optString("workspace_id").ifBlank { null }
        if (role == "implementation" || workspaceId != null) {
            require(workspace != null && executeWorkspaceChild != null && Regex("/workspace/[^/]+").matches(project))
        }
        if (workspaceId != null && tasks.values.any { it.project == project && it.workspaceId == workspaceId && (it.state == "running" || it.executing) }) return errorResult("WORKSPACE_IN_USE")
        if (role == "implementation" && tasks.values.any { it.project == project && it.role == role && (it.state == "running" || it.executing) }) return errorResult("WORKSPACE_IN_USE")
        require(role != "implementation" || workspaceId == null)
        val task = Task(UUID.randomUUID().toString(), worker, role, project, workspaceId)
        tasks[task.id] = task
        task.future = pool.submit {
            task.executing = true
            var ownsWorkspaceLease = false
            try {
                task.controller.throwIfCancelled()
                if (role == "implementation") {
                    val prepared = workspace!!.requireOperation(project, "prepare")
                    task.workspaceId = prepared.getString("id")
                    ownsWorkspaceLease = true
                    task.workspacePath = prepared.getString("path")
                } else if (workspaceId != null) {
                    val existing = workspace!!.requireOperation(project, "begin_review", workspaceId)
                    check(existing.getString("state") == "reviewing")
                    ownsWorkspaceLease = true
                    task.workspacePath = existing.getString("path")
                }
                task.controller.throwIfCancelled()
                val prompt = "Role: $role\nTask:\n$instruction\n\nContext supplied by main agent:\n$context"
                val answer = task.workspaceId?.let { id ->
                    executeWorkspaceChild!!.invoke(workers[worker], prompt, task.controller, project, id, role == "implementation")
                } ?: executeChild(workers[worker], prompt, task.controller)
                task.controller.throwIfCancelled()
                if (role == "implementation") workspace!!.requireOperation(project, "seal", task.workspaceId)
                else if (task.workspaceId != null) workspace!!.requireOperation(project, if (role == "review") "review" else "end_review", task.workspaceId)
                task.controller.throwIfCancelled()
                synchronized(task) {
                    if (task.state == "running") {
                        task.result = answer.take(16000) + if (answer.length > 16000) "\n[结果已截断]" else ""
                        task.state = "completed"
                    }
                }
            } catch (error: Exception) {
                // Future.cancel interrupts the worker. Clear only for bounded cleanup, then restore.
                val interrupted = Thread.interrupted()
                if (ownsWorkspaceLease && task.workspaceId != null) runCatching { workspace?.operation(project, if (role == "implementation") "fail" else "end_review", task.workspaceId) }
                if (interrupted) Thread.currentThread().interrupt()
                synchronized(task) {
                    if (task.state == "running") {
                        task.errorCode = when (error) {
                            is SubAgentContextLimitException -> "SUB_AGENT_CONTEXT_LIMIT"
                            is WorkspaceOperationException -> error.code
                            else -> ""
                        }
                        task.result = if (error is SubAgentContextLimitException)
                            "子代理上下文不足，自动压缩不可用或未能释放足够空间。请拆分任务或调整模型窗口后重新委派；已有工作树改动保留。"
                        else "子代理未完成，请主代理接手或重新委派。"
                        task.state = "failed"
                    }
                }
            } finally {
                task.executing = false
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
            .put("role", task.role).put("project", task.project).put("error_code", task.errorCode)
            .put("workspace_id", task.workspaceId ?: JSONObject.NULL).put("workspace_path", task.workspacePath)
            .put("review_required", true)
    }
    @Synchronized private fun manage(args: JSONObject): JSONObject {
        if (closed) return errorResult("RUN_CLOSED")
        val backend = workspace ?: return errorResult("WORKSPACE_UNAVAILABLE")
        val action = args.getString("action")
        require(action in setOf("list", "inspect", "merge", "discard"))
        val project = args.getString("project")
        val id = args.optString("workspace_id").ifBlank { null }
        if (tasks.values.any { it.project == project && (id == null || it.workspaceId == id) && (it.state == "running" || it.executing) }) return errorResult("WORKSPACE_IN_USE")
        return backend.operation(project, action, id)
    }
    private fun errorResult(code: String) = JSONObject().put("ok", false).put("code", code)
    @Synchronized override fun close() {
        closed = true
        tasks.values.forEach { stop(it, "cancelled") }
        pool.shutdownNow()
        timer.shutdownNow()
    }
}
