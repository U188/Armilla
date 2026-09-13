package io.github.mangi.eta.agent.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRuntimeSessionRegistryTest {
    @Test
    fun putSameRunIdReplacesWithoutDroppingOthers() {
        val registry = AgentRuntimeSessionRegistry()
        val firstA = AgentRuntimeSession("run-a")
        val runB = AgentRuntimeSession("run-b")
        val secondA = AgentRuntimeSession("run-a")
        registry.put(firstA)
        registry.put(runB)
        val previous = registry.put(secondA)
        assertTrue(previous === firstA)
        assertTrue(registry.contains(secondA))
        assertTrue(registry.contains(runB))
        assertFalse(registry.contains(firstA))
        assertEquals(listOf("run-a", "run-b"), registry.activeRunIds())
    }

    @Test
    fun removeOnlyMatchingInstance() {
        val registry = AgentRuntimeSessionRegistry()
        val session = AgentRuntimeSession("run-a")
        val stale = AgentRuntimeSession("run-a")
        registry.put(session)
        assertFalse(registry.remove(stale))
        assertTrue(registry.contains(session))
        assertTrue(registry.remove(session))
        assertTrue(registry.isEmpty())
    }
}
