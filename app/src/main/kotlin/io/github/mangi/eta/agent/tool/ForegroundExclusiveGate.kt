package io.github.mangi.eta.agent.tool

import io.github.mangi.eta.agent.overlay.AgentOverlayVisibilityPolicy
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 前台界面与共享浏览器不能并行操作设备，但不应因此取消其他会话。
 * 后到的独占工具在这里排队，模型请求和普通工具仍可同时进行。
 */
internal object ForegroundExclusiveGate {
    private val lock = ReentrantLock()

    fun shouldSerialize(toolName: String): Boolean {
        val name = toolName.trim()
        return name.equals("browser_use", ignoreCase = true) ||
            AgentOverlayVisibilityPolicy.isForegroundOperationTool(name)
    }

    fun <T> withLock(block: () -> T): T = lock.withLock(block)
}
