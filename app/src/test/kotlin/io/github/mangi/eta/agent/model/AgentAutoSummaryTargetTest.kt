package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentAutoSummaryTargetTest {
    private fun history(chars: Int, tailChars: Int = 40) = listOf(
        AgentModelClient.ConversationMessage("user", "a".repeat(chars)),
        AgentModelClient.ConversationMessage("user", "b".repeat(tailChars)),
    )
    private fun auto(history: List<AgentModelClient.ConversationMessage>, window: Int? = 256_000,
                     overhead: Int = 0, reserve: Int = 4096) =
        AgentContextCompactor.resolveTargetTokens(0, history, 1, window, overhead, reserve)

    @Test fun autoIsFirstAndExistingOptionsAndDefaultAreUnchanged() {
        assertEquals(listOf(0, 500, 1000, 2000, 4000), AgentContextCompactor.TARGET_TOKEN_OPTIONS)
        assertEquals(2000, AgentContextCompactor.DEFAULT_TARGET_TOKENS)
    }

    @Test fun preferenceCoercionPreservesAutoInsteadOfConvertingTo500() {
        for (value in AgentContextCompactor.TARGET_TOKEN_OPTIONS)
            assertEquals(value, AgentContextCompactor.coerceTargetPreference(value))
        assertEquals(500, AgentContextCompactor.coerceTargetPreference(-1))
        assertEquals(4000, AgentContextCompactor.coerceTargetPreference(Int.MAX_VALUE))
    }

    @Test fun fixedTargetsIgnoreAutomaticHeuristics() {
        for (target in listOf(500, 1000, 2000, 4000)) {
            assertEquals(target, AgentContextCompactor.resolveTargetTokens(target, history(40_000), 1, 8192))
        }
    }

    @Test fun autoGrowsWithSelectedHistoryRatherThanBeingAnotherFixedOption() {
        val small = auto(history(10_000))
        val medium = auto(history(160_000))
        val large = auto(history(400_000))
        assertEquals(1000, small)
        assertTrue(medium > small)
        assertTrue(large > medium)
        assertEquals(8000, large)
    }

    @Test fun mainWindowCapsTargetEvenWhenSummaryModelCouldFitMore() {
        assertEquals(1000, auto(history(400_000), 32_000))
        assertEquals(4000, auto(history(400_000), 128_000))
        assertEquals(8000, auto(history(400_000), 1_000_000))
    }

    @Test fun largeVerbatimTailAndEnvelopeReduceAvailableSummarySpace() {
        val source = history(400_000, 60_000)
        val target = auto(source, 32_000, overhead = 9000)
        assertTrue(target in 500 until 1000)
        val tail = AgentContextBudget.countMessage(source.last())
        assertTrue(target * 2L + tail + 9000 + 512 <= AgentCompressionBoundary.inputLimit(32_000))
    }

    @Test fun insufficientRoomFailsWithoutShrinkingProtectedTail() {
        val source = history(40_000, 40_000)
        val copy = source.toList()
        assertThrows(IllegalArgumentException::class.java) { auto(source, 8192) }
        assertEquals(copy, source)
    }

    @Test fun absentWindowStillUsesBoundedContentBasedTarget() {
        assertEquals(1000, auto(history(10_000), null))
        assertEquals(8000, auto(history(400_000), null))
    }

    @Test fun modelOutputReservationAlsoCountsAgainstAvailableRoom() {
        val source = history(400_000, 60_000)
        val ordinary = auto(source, 32_000, overhead = 1000)
        val constrained = auto(source, 32_000, overhead = 1000, reserve = 12_000)
        assertTrue(constrained <= ordinary)
        assertTrue(constrained * 2L + AgentContextBudget.countMessage(source.last()) + 1000 + 512 <=
            AgentCompressionBoundary.inputLimit(32_000, 12_000))
    }
}
