package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentContextBudget
import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.*
import org.junit.Test

class AgentContextMeterPolicyTest {
    @Test fun emptyFirstDraftIncludesSystemAndToolOverhead() {
        val usage = liveContextUsage(emptyList(), "", emptyList(), null, requestOverheadTokens = 12000)
        assertEquals(12000, usage.contextTokens)
        assertTrue(usage.estimated)
        assertTrue(formatContextUsage(usage).startsWith("≈12K"))
    }

    @Test fun cloudRingStaysFixedWhileSilentBudgetAddsHistoryAndToolDeltas() {
        val ring = liveContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 1200, billedContextTokens = 10000, requestOverheadTokens = 700)
        val budget = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 1200, billedContextTokens = 10000, requestOverheadTokens = 700,
            billedHistoryTokens = 1000, billedOverheadTokens = 500)
        assertEquals(10000, ring.contextTokens)
        assertFalse(ring.estimated)
        assertEquals(10400, budget.contextTokens)
        assertTrue(budget.estimated)
    }

    @Test fun legacyCloudReceiptDoesNotDisableSilentLocalPressure() {
        val ring = liveContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 20000, billedContextTokens = 10000, requestOverheadTokens = 3000)
        val budget = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 20000, billedContextTokens = 10000, requestOverheadTokens = 3000)
        assertEquals(10000, ring.contextTokens)
        assertEquals(23000, budget.contextTokens)
        val conservative = compressionContextUsage(emptyList(), "", emptyList(), null,
            historyTokenCount = 1000, billedContextTokens = 10000, requestOverheadTokens = 500)
        assertEquals(10000, conservative.contextTokens)
    }

    @Test fun summaryWithoutNewCloudUsageUsesNewHistoryNotOldBill() {
        val history = listOf(AgentModelClient.ConversationMessage("system", "short summary"))
        val usage = liveContextUsage(history, "", emptyList(), null, requestOverheadTokens = 500)
        assertEquals(500 + AgentContextBudget.countMessage(history.single()), usage.contextTokens)
        assertTrue(usage.estimated)
    }

    @Test fun validReceiptPersistsCalibrationAndInvalidHistoryCannotRestoreIt() {
        val raw = CloudUsageReceiptCodec.encode("c", "p", "m", "history", 12345, 6000, 700)
        val receipt = requireNotNull(CloudUsageReceiptCodec.decodeReceipt(raw, "c", "p", "m", "history"))
        assertEquals(12345, receipt.inputTokens)
        assertEquals(6000, receipt.historyTokens)
        assertEquals(700, receipt.overheadTokens)
        assertNull(CloudUsageReceiptCodec.decodeReceipt(raw, "c", "p", "other", "history"))
        assertNull(CloudUsageReceiptCodec.decodeReceipt(raw, "c", "p", "m", "edited"))
        assertEquals("", CloudUsageReceiptCodec.encode("c", "p", "m", "history", null, 6000, 700))
    }
}
