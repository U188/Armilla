package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class AgentContinuationBlocksTest {
    private val text = AssistantBlockKind.TEXT
    private val thinking = AssistantBlockKind.THINKING

    @Test fun repeatedContinuationReplacementNeverDropsPreviousPrefixes() {
        val blocks = AgentContinuationBlocks()
        var expected = ""
        repeat(4) { request ->
            blocks.beginRequest(request > 0)
            val start = blocks.map(1, ProviderEvent.BlockStart(text, 0)) as ProviderEvent.BlockStart
            assertEquals(0, start.index)
            blocks.map(1, ProviderEvent.BlockDelta(text, 0, "draft-$request"))
            val suffix = "part-$request "
            val end = blocks.map(1, ProviderEvent.BlockEnd(text, 0, content = suffix, replaceContent = true)) as ProviderEvent.BlockEnd
            expected += suffix
            assertEquals(expected, end.content)
            assertTrue(end.replaceContent)
        }
    }

    @Test fun newlyStartedThinkingAndTextDoNotCollideWithPriorRequest() {
        val blocks = AgentContinuationBlocks()
        blocks.beginRequest(false)
        val first = blocks.map(1, ProviderEvent.BlockDelta(text, 0, "old")) as ProviderEvent.BlockDelta
        blocks.beginRequest(true)
        val reason = blocks.map(1, ProviderEvent.BlockDelta(thinking, 0, "reason")) as ProviderEvent.BlockDelta
        val next = blocks.map(1, ProviderEvent.BlockDelta(text, 1, "new")) as ProviderEvent.BlockDelta
        assertEquals(listOf(0, 1, 2), listOf(first.index, reason.index, next.index))
        val end = blocks.map(1, ProviderEvent.BlockEnd(text, 1, content = "corrected", replaceContent = true)) as ProviderEvent.BlockEnd
        assertEquals("corrected", end.content)
    }

    @Test fun continuationAfterEmptyInterruptedRequestStillKeepsPrefix() {
        val blocks = AgentContinuationBlocks()
        blocks.beginRequest(false)
        blocks.map(1, ProviderEvent.BlockDelta(text, 0, "prefix "))
        blocks.beginRequest(true)
        blocks.map(1, ProviderEvent.RequestStarted)
        blocks.beginRequest(true)
        val end = blocks.map(1, ProviderEvent.BlockEnd(text, 0, content = "suffix", replaceContent = true)) as ProviderEvent.BlockEnd
        assertEquals("prefix suffix", end.content)
    }

    @Test fun toolBoundaryDoesNotJoinUnrelatedText() {
        val blocks = AgentContinuationBlocks()
        blocks.beginRequest(false)
        blocks.map(1, ProviderEvent.BlockDelta(text, 0, "old"))
        blocks.map(1, ProviderEvent.BlockStart(AssistantBlockKind.TOOL_CALL, 1, blockId = "tool-id"))
        blocks.beginRequest(true)
        val end = blocks.map(1, ProviderEvent.BlockEnd(text, 0, content = "new", replaceContent = true)) as ProviderEvent.BlockEnd
        assertEquals(2, end.index)
        assertEquals("new", end.content)
    }

    @Test fun retryRoundStartsNewBlockNamespaceWithoutChangingTurnIdentity() {
        val blocks = AgentContinuationBlocks()
        blocks.beginRequest(false)
        blocks.map(1, ProviderEvent.BlockDelta(text, 0, "old"))
        blocks.beginRequest(true)
        val end = blocks.map(2, ProviderEvent.BlockEnd(text, 0, content = "new", replaceContent = true)) as ProviderEvent.BlockEnd
        assertEquals(0, end.index)
        assertEquals("new", end.content)
    }

    @Test fun ordinaryRequestDoesNotCarryOldPrefixEvenInSameRound() {
        val blocks = AgentContinuationBlocks()
        blocks.beginRequest(false)
        blocks.map(1, ProviderEvent.BlockDelta(text, 0, "old"))
        blocks.beginRequest(false)
        val end = blocks.map(1, ProviderEvent.BlockEnd(text, 0, content = "new", replaceContent = true)) as ProviderEvent.BlockEnd
        assertEquals(1, end.index)
        assertEquals("new", end.content)
    }
}
