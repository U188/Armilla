package io.github.mangi.eta.ui.app

import android.os.SystemClock

/**
 * 根页面防误触退出：第一次按返回只记时，[timeoutMs] 内再按一次才允许退出。
 */
internal class DoublePressExitGuard(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val uptimeMillis: () -> Long = SystemClock::elapsedRealtime,
) {
    private var lastPressAt = 0L

    fun consume(): Boolean {
        val now = uptimeMillis()
        val shouldExit = lastPressAt != 0L && now - lastPressAt <= timeoutMs
        lastPressAt = if (shouldExit) 0L else now
        return shouldExit
    }

    fun reset() {
        lastPressAt = 0L
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 2_000L
    }
}
