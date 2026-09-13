package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 屏幕与共享浏览器按「当前这条消息 / 这次 run」互斥。
 *
 * 某个会话一旦开始操作屏幕，就一直占到这次 run 结束；其他会话在真正执行屏幕工具前排队，
 * 等整段任务结束再开始，而不是按单个 tap / observe 插花。
 */
internal object ForegroundExclusiveGate {
    private val lock = ReentrantLock()
    private val ownerChanged = lock.newCondition()
    private val waiters = ArrayDeque<String>()
    private var ownerRunId: String? = null

    fun shouldSerialize(toolName: String): Boolean {
        val name = toolName.trim()
        return name.equals("browser_use", ignoreCase = true) ||
            AgentOverlayVisibilityPolicy.isForegroundOperationTool(name)
    }

    fun acquire(runId: String, isClosed: () -> Boolean = { false }): Boolean {
        val id = runId.trim()
        if (id.isEmpty()) return !isClosed()
        lock.lockInterruptibly()
        try {
            if (isClosed()) return false
            if (ownerRunId == id) return true
            if (!waiters.contains(id)) waiters.addLast(id)
            while (true) {
                if (isClosed()) {
                    waiters.remove(id)
                    return false
                }
                if (ownerRunId == id) return true
                if (ownerRunId == null && waiters.firstOrNull() == id) {
                    waiters.removeFirst()
                    ownerRunId = id
                    return true
                }
                ownerChanged.await()
            }
        } catch (_: InterruptedException) {
            waiters.remove(id)
            Thread.currentThread().interrupt()
            return false
        } finally {
            lock.unlock()
        }
    }

    fun release(runId: String) {
        val id = runId.trim()
        if (id.isEmpty()) return
        lock.withLock {
            waiters.remove(id)
            if (ownerRunId == id) ownerRunId = null
            ownerChanged.signalAll()
        }
    }

    internal fun resetForTests() {
        lock.withLock {
            waiters.clear()
            ownerRunId = null
            ownerChanged.signalAll()
        }
    }

    internal fun ownerForTests(): String? = lock.withLock { ownerRunId }

    internal fun waitersForTests(): List<String> = lock.withLock { waiters.toList() }
}
