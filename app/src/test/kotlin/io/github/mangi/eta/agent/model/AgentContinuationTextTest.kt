package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentContinuationTextTest {
    @Test fun repeatedChineseSentenceIsRemovedEvenAcrossSingleCharacterDeltas() {
        val filter = AgentContinuationText("报告已经发来。他打开邮箱。")
        val resumed = "他打开邮箱。点开附件。"
        val visible = resumed.map { filter.append(it.toString()) }.joinToString("") + filter.finish()
        assertEquals("点开附件。", visible)
        assertEquals("点开附件。", filter.normalize(resumed))
    }

    @Test fun longestExactOverlapWinsAndBodyRepetitionIsNotTouched() {
        val filter = AgentContinuationText("开头。他打开邮箱。他打开邮箱。")
        assertEquals("点开附件。他打开邮箱。", filter.append("他打开邮箱。他打开邮箱。点开附件。他打开邮箱。") + filter.finish())
    }

    @Test fun mismatchFlushesPendingTextAndShortCommonFragmentsArePreserved() {
        val filter = AgentContinuationText("他打开邮箱。")
        assertEquals("", filter.append("他打开"))
        assertEquals("他打开窗户。", filter.append("窗户。"))
        assertEquals("", filter.finish())
        val short = AgentContinuationText("好。")
        assertEquals("好。没问题。", short.append("好。没问题。"))
    }

    @Test fun pausedBeforeOverlapDecisionNeverLosesIncompleteNewText() {
        val filter = AgentContinuationText("他打开邮箱。")
        assertEquals("", filter.append("他打开"))
        assertEquals("他打开", filter.finish())
        assertEquals("他打开", filter.normalize("他打开"))
    }

    @Test fun endReplacementAndFinalResponseUseSameSeamAndOnlyFirstTextBlockIsFiltered() {
        val filter = AgentContinuationTextEvents("他打开邮箱。")
        filter.map(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 8))
        assertTrue(filter.map(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 8, "他打开")).isEmpty())
        val end = filter.map(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, 8, content = "他打开邮箱。点开附件。", replaceContent = true))
        assertEquals("点开附件。", (end.last() as ProviderEvent.BlockEnd).content)
        assertEquals("点开附件。", filter.normalize("他打开邮箱。点开附件。"))
        val later = ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 9, "他打开邮箱。")
        assertEquals(listOf(later), filter.map(later))
    }

    @Test fun ordinaryRequestIsNotDeduplicatedAndTransportRetryResetsOpening() {
        val plain = AgentContinuationTextEvents("")
        assertEquals("他打开邮箱。他打开邮箱。", plain.normalize("他打开邮箱。他打开邮箱。"))
        val filter = AgentContinuationTextEvents("他打开邮箱。")
        filter.map(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "他打开"))
        filter.map(ProviderEvent.RequestStarted)
        val event = filter.map(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "他打开邮箱。点开附件。")).single() as ProviderEvent.BlockDelta
        assertEquals("点开附件。", event.delta)
    }
}
