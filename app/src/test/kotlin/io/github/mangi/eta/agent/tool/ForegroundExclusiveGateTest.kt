package io.github.mangi.eta.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundExclusiveGateTest {
    @Test
    fun serializesForegroundAndBrowserToolsOnly() {
        assertTrue(ForegroundExclusiveGate.shouldSerialize("observe_screen"))
        assertTrue(ForegroundExclusiveGate.shouldSerialize("tap_element"))
        assertTrue(ForegroundExclusiveGate.shouldSerialize("browser_use"))
        assertFalse(ForegroundExclusiveGate.shouldSerialize("memory_get"))
        assertFalse(ForegroundExclusiveGate.shouldSerialize("terminal"))
        assertFalse(ForegroundExclusiveGate.shouldSerialize("web_search"))
    }
}
