# 范围 H：AgentRuntimeService.kt 精确锚点补丁（需主代理应用）

此工作树的 `workspace_file` 只有整文件 `write`、没有局部替换；`AgentRuntimeService.kt` 为 50,239 字符，不能在不重新手写整个原文件的情况下安全地局部修改。以下每段的 old 为精确原文，new 可逐段替换。**尚未在 Service 文件应用；不可把本工作树当作已集成的实现。** B 候选 `AgentChildTaskGroups` / `AgentRuntimeRunLease` 已在本树；但读取时本树的 `AgentRuntimeWire` 尚无 `MSG_STOP_MAIN_RUN`，该常量及客户端实现需主代理确保已集成。严禁在没有 Wire 常量时提交 Service 修改。

## 1. 追踪每个 run 对应的 child owner（即 effectiveModelSessionId）

在 `private val pendingStartRequests = linkedMapOf<String, PendingStartRequest>()` **后**增加：

```kotlin
    // Main-looper only. Preserve the owner after a terminal parent has left sessions:
    // a later explicit cancel must still reach its detached child generations.
    // A reused runId can have outstanding children from more than one model session.
    private val childOwnersByRunId = linkedMapOf<String, MutableSet<String>>()
```

不要把 parent 的 `AgentRuntimeSession` 或 FGS lease 保留在这个表内。这个表在 `onDestroy` 清空；如需进一步有界化，须先让 Groups 提供按 runId 判断仍存活 group 的 API，不能在 parent 终态立即删除。

## 2. onDestroy：先关 groups，再关 sessions

old:
```kotlin
    override fun onDestroy() {
        failPendingStarts("Agent Runtime 服务已停止")
        sessions.cancelAll("Agent Runtime 服务已停止")
        overlaySession = null
```
new:
```kotlin
    override fun onDestroy() {
        // The ExecutionService stop callbacks and this destroy path must both retire
        // queued and executing children; a parent being terminal is not an exemption.
        AgentChildTaskGroups.stopAll()
        failPendingStarts("Agent Runtime 服务已停止")
        sessions.cancelAll("Agent Runtime 服务已停止")
        childOwnersByRunId.clear()
        overlaySession = null
```

不要在 parent terminal / network failure / worker finally 调用 stopAll、stopRun 或 detachOwner；executor 自己调用 group.detach(generation)，其 active child 持有自身 lease。

## 3. 消息分派：MSG_STOP_MAIN_RUN 只走 parent-only

old:
```kotlin
                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
```
new:
```kotlin
                AgentRuntimeWire.MSG_CANCEL -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) cancelRun(runId)
                }

                AgentRuntimeWire.MSG_STOP_MAIN_RUN -> {
                    val runId = msg.data?.let(AgentRuntimeWire::runIdFromBundle).orEmpty()
                    if (runId.isNotBlank()) stopMainRun(runId)
                }

                AgentRuntimeWire.MSG_ACK_RESULT -> {
```

现有 `isMessageSenderAllowed(msg)` 整段必须原封不动：新消息仍在同一 UID/允许包的闸门之后分派，不额外跳过任何校验；不改变 INGEST ACK / outbox / attach/replay / transcript ACK 的流程。

## 4. startRun：FGS 身份必须为一次 worker 的全新 lease，callback 只能取消捕获的 session

old:
```kotlin
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val executionHeld = AgentExecutionService.acquire(
            this, "run:${request.runId}", allowBoundFallback = allowBoundFallback,
        ) { session.cancel("已停止") }
```
new:
```kotlin
        // A replacement may have the same runId while the old worker is still alive.
        // Its foreground callback and release must never operate on the replacement.
        val runLease = AgentRuntimeRunLease.create(request.runId)
        // Root 入口保留原有绑定服务生命周期；新增 FGS 不能成为厂商后台入口的新前置权限。
        val allowBoundFallback = RootAccess.isGranted
        val executionHeld = AgentExecutionService.acquire(
            this, runLease.id, allowBoundFallback = allowBoundFallback,
        ) { session.cancel("已停止") }
```

在 `sessions.put(session)` **后**增加（只有成功获准入的 run 才登记，允许 bound fallback 的 run 也登记）：
```kotlin
        childOwnersByRunId.getOrPut(request.runId) { linkedSetOf() }
            .add(request.effectiveModelSessionId)
```

old:
```kotlin
        thread(name = "agent-runtime") {
            try {
                executeRun(session, request)
            } finally {
                AgentExecutionService.release("run:${request.runId}")
            }
        }
```
new:
```kotlin
        try {
            thread(name = "agent-runtime") {
                try {
                    executeRun(session, request)
                } catch (failure: Throwable) {
                    AndroidAgentLogger.error(
                        "Agent runtime worker failed: type=${failure.safeLogType()}"
                    )
                    // Executor normally absorbs model/provider failures and publishes the
                    // result itself. This handles only a failure escaping that boundary.
                    val error = "Agent Runtime 执行失败"
                    runCatching { session.cancel(error) }
                    mainHandler.post {
                        if (sessions.contains(session)) {
                            postTerminalOverlay(
                                session, session.terminalResult ?: AgentRuntimeWire.RunResult(
                                    runId = request.runId, ok = false, content = "", error = error,
                                ), null,
                            )
                        }
                    }
                } finally {
                    // Never release a runId-derived fixed key: an older worker can finish
                    // after its replacement starts and would release the new worker's lease.
                    AgentExecutionService.release(runLease.id)
                }
            }
        } catch (failure: Throwable) {
            AgentExecutionService.release(runLease.id)
            AndroidAgentLogger.error(
                "Agent runtime thread start failed: type=${failure.safeLogType()}"
            )
            val error = "Agent Runtime 无法启动执行线程"
            runCatching { session.cancel(error) }
            postTerminalOverlay(
                session, session.terminalResult ?: AgentRuntimeWire.RunResult(
                    runId = request.runId, ok = false, content = "", error = error,
                ), null,
            )
        }
```

注意：`postTerminalOverlay` 自己调用 `mainHandler.post`，所以 worker catch 内的外层 `mainHandler.post` 可省略，只要确保 `sessions.contains(session)` 的身份检查与最终回调安全。异常兜底若需要保证带 handoff 的 outbox，应在 `session.cancel` 之前调用现有 `persistCompletedRun(request, failureResult)`（且限未终态），但不能把 `session.cancel` 误当作已持久化提交；常规 `AgentRuntimeRunExecutor` 已自行持久化。

## 5. UI 和 IPC 取消：parent-only 与整个 task 明确区分

old:
```kotlin
    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        pendingStartRequests.remove(runId)?.let { pending ->
            failPendingStart(pending, "已停止")
            return
        }
        val session = sessions.get(runId) ?: return
        if (session.requestStop() && overlaySession === session) {
            state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
        }
    }
```
new:
```kotlin
    private fun stopMainRun(runId: String) {
        if (runId.isBlank()) return
        pendingStartRequests.remove(runId)?.let { pending ->
            failPendingStart(pending, "已停止")
        }
        val session = sessions.get(runId) ?: return
        if (session.requestStop() && overlaySession === session) {
            state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
        }
    }

    private fun cancelRun(runId: String) {
        if (runId.isBlank()) return
        // Snapshot BEFORE asking the parent to stop. The worker can detach as soon as
        // its controller is cancelled. Capture the owner even after parent TERMINAL.
        val owners = childOwnersByRunId[runId].orEmpty().toList()
        val targets = owners.mapNotNull(AgentChildTaskGroups::captureStopTarget)
        pendingStartRequests.remove(runId)?.let { pending ->
            failPendingStart(pending, "已停止")
        }
        val session = sessions.get(runId)
        if (session?.requestStop() == true && overlaySession === session) {
            state.value = state.value.copy(status = AgentOverlayStatus.Stopping)
        }
        targets.forEach(AgentChildTaskGroups::stop)
        // Fallback for a terminal parent whose owner was not retained, or a group
        // created in the narrow window after capture. stopRun snapshots groups by
        // runId before closing; never broaden this fallback to all process groups.
        AgentChildTaskGroups.stopRun(runId)
    }
```

`requestStop()`（overlay）仍调用 `cancelRun(runId)`，旧 UI Stop 的语义依旧是整任务停。不要用 `sessions.get(runId) ?: return` 退出 whole-task cancel：父可能已经成功/失败并从 registry 删除，子还在。也不要在 `stopMainRun` 触碰 `AgentChildTaskGroups`。`requestStop` session requestStop 而非 session.cancel：让 executor 封存已产生的 history/终态。

注意：最后 `stopRun(runId)` 在极端复用同一 runId 且新旧 parent 分属不同 owner 时会停两批同 runId children；IPC 只有 runId，本就无法区分同名新旧 run。若主代理已扩展 nonce/世代进 IPC，可以按携带世代过滤；不要破坏现有 nonce/ACK 机制。

## 集成核验（主代理测试）

- 独立子任务排队/运行中时，MSG_STOP_MAIN_RUN -> parent 停且 child lease 保留；parent 成功和网络失败也同样保留。
- MSG_CANCEL -> 捕获 owner 的历史/当前 groups，停 queued 与 executing，parent RUNNING、TERMINAL 都有效；未运行但仍在 ingest 的 pending request 返回一次 ingested ACK 与停止结果。
- 同 runId 替换：旧 worker `release(oldLease.id)` 不释放新 lease；旧 FGS stop callback 只取消旧 session；旧 terminal callback `sessions.remove(session)` 不删新 session。
- `onDestroy()` -> 先 `stopAll()` 再 `sessions.cancelAll`；ExecutionService stop 与 destroy 幂等；不调用 `detachOwner`。
- 不修改 `AgentRuntimeWire` 中原 IPC 格式、outbox、attach/replay 与 same-UID 闸门。

未编译/运行测试（依任务约束）。
