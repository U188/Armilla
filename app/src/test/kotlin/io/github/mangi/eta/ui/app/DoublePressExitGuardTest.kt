package io.github.mangi.eta.ui.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoublePressExitGuardTest {
    @Test
    fun firstPressDoesNotExitAndSecondPressWithinWindowDoes() {
        var now = 1_000L
        val guard = DoublePressExitGuard(timeoutMs = 2_000L) { now }

        assertFalse(guard.consume())
        now = 2_999L
        assertTrue(guard.consume())
    }

    @Test
    fun pressAfterTimeoutStartsANewWindow() {
        var now = 1_000L
        val guard = DoublePressExitGuard(timeoutMs = 2_000L) { now }

        assertFalse(guard.consume())
        now = 3_001L
        assertFalse(guard.consume())
        now = 4_000L
        assertTrue(guard.consume())
    }

    @Test
    fun resetClearsPendingExit() {
        var now = 1_000L
        val guard = DoublePressExitGuard(timeoutMs = 2_000L) { now }

        assertFalse(guard.consume())
        guard.reset()
        now = 1_500L
        assertFalse(guard.consume())
    }
}
