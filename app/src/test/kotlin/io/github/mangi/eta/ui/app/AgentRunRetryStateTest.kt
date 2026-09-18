package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent
import org.junit.Assert.*
import org.junit.Test

class AgentRunRetryStateTest {
    @Test fun waitIsScopedToRunAndEndsWhenNextRequestStarts() {
        val state = AgentRunRetryState()
        state.accept("old", AgentEvent.ModelRetryScheduled(1, 1, 3, 2000, "network"))
        assertTrue(state.isWaiting("old"))
        assertFalse(state.isWaiting("new"))
        state.accept("old", AgentEvent.ProviderRequestStarted(2))
        assertFalse(state.isWaiting("old"))
        state.accept("new", AgentEvent.ModelRetryScheduled(1, 1, 3, 2000, "network"))
        state.clear("new")
        assertFalse(state.isWaiting("new"))
    }
}
