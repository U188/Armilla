package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationReasoningPolicyTest {
    private val capabilities = ModelReasoningCapabilities(
        supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH),
        defaultEffort = ReasoningEffort.HIGH, canDisable = true,
    )
    @Test fun absentDefaultAndExplicitOffRemainOff() {
        assertEquals(ReasoningEffort.OFF, ConversationReasoningPolicy.resolve(null, capabilities))
        assertEquals(ReasoningEffort.OFF, ConversationReasoningPolicy.resolve(ReasoningEffort.OFF,
            capabilities.copy(canDisable = false, mandatory = true)))
    }
    @Test fun modelSwitchPreservesSupportedConversationChoice() {
        assertEquals(ReasoningEffort.LOW, ConversationReasoningPolicy.resolve(ReasoningEffort.LOW, capabilities))
        assertEquals(ReasoningEffort.LOW, ConversationReasoningPolicy.resolve(ReasoningEffort.HIGH,
            capabilities.copy(supportedEfforts = listOf(ReasoningEffort.LOW))))
        assertEquals(ReasoningEffort.OFF, ConversationReasoningPolicy.resolve(ReasoningEffort.HIGH, null))
    }
}
