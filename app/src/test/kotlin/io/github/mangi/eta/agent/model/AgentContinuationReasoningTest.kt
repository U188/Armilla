package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentContinuationReasoningTest {
    private val thinking = AssistantBlockKind.THINKING

    @Test fun hidesFirstReasoningAtNonzeroIndexButNotLaterBlocks() {
        val filter = AgentContinuationReasoning()
        filter.beginRequest(true)
        assertNull(filter.visibleEvent(ProviderEvent.BlockStart(thinking, 7)))
        assertNull(filter.visibleEvent(ProviderEvent.BlockDelta(thinking, 7, "hidden")))
        assertNull(filter.visibleEvent(ProviderEvent.BlockEnd(thinking, 7, content = "hidden replacement", replaceContent = true)))
        val later = ProviderEvent.BlockDelta(thinking, 9, "later")
        assertEquals(later, filter.visibleEvent(later))
        filter.visibleEvent(ProviderEvent.BlockEnd(thinking, 9, content = "later corrected", replaceContent = true))
        assertEquals("later corrected", filter.visibleCompletedReasoning("hidden replacement later corrected"))
    }

    @Test fun ordinaryRequestsAndLaterRequestsKeepReasoning() {
        val filter = AgentContinuationReasoning()
        filter.beginRequest(false)
        val delta = ProviderEvent.BlockDelta(thinking, 0, "original")
        assertEquals(delta, filter.visibleEvent(delta))
        assertEquals("original", filter.visibleCompletedReasoning("original"))
        filter.beginRequest(true)
        assertNull(filter.visibleEvent(delta))
        filter.beginRequest(false)
        assertEquals(delta, filter.visibleEvent(delta))
    }

    @Test fun nonStreamingFallbackAndRepeatedResumesCannotRestoreHiddenReasoning() {
        val filter = AgentContinuationReasoning()
        repeat(3) {
            filter.beginRequest(true)
            assertEquals("", filter.visibleCompletedReasoning("resume thinking"))
            assertNull(filter.visibleEvent(ProviderEvent.BlockEnd(thinking, 5, content = "hidden", replaceContent = true)))
            assertEquals("", filter.visibleCompletedReasoning("hidden"))
        }
    }

    @Test fun transportRetryResetsHiddenBlockSelection() {
        val filter = AgentContinuationReasoning()
        filter.beginRequest(true)
        filter.visibleEvent(ProviderEvent.BlockDelta(thinking, 3, "hidden"))
        filter.visibleEvent(ProviderEvent.BlockDelta(thinking, 4, "failed visible"))
        filter.visibleEvent(ProviderEvent.RequestStarted)
        assertNull(filter.visibleEvent(ProviderEvent.BlockDelta(thinking, 0, "retry hidden")))
        assertEquals("", filter.visibleCompletedReasoning("retry hidden"))
    }
}
