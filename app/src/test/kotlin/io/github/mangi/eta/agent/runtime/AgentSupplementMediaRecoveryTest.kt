package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentSupplementMedia
import io.github.mangi.eta.ui.app.AgentPendingResultRecovery
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.*
import org.junit.Test

class AgentSupplementMediaRecoveryTest {
    @Test fun handoffRoundTripRetainsRequestIdentityAndPreview() {
        val raw = """[{"preview":"/cache/preview.jpg","source":"/cache/video.mp4","kind":"video","durationMs":1200}]"""
        val supplement = AgentUiHandoffPayload.Supplement(1, "look", 123, "request-1", raw)
        val result = AgentUiHandoffPayload.from(AgentUiHandoffPayload("conv", supplements = listOf(supplement)).toJson())
        assertEquals(supplement, result.supplements.single())
        val messages = AgentPendingResultRecovery.mergeSupplements("run", result.supplements, emptyList())
        val user = messages.single() as UserMessageUi
        assertEquals(listOf("/cache/preview.jpg"), user.images)
        assertEquals(listOf("/cache/video.mp4"), user.imageSources)
        assertEquals(listOf(true), user.imageIsVideo)
    }

    @Test fun identicalTextWithDifferentIndexesIsNotDroppedAndReplayDoesNotDuplicate() {
        val supplements = listOf(AgentUiHandoffPayload.Supplement(1, "same", 1), AgentUiHandoffPayload.Supplement(2, "same", 2))
        val messages = AgentPendingResultRecovery.mergeSupplements("run", supplements, emptyList())
        assertEquals(2, messages.size)
        assertEquals(messages, AgentPendingResultRecovery.mergeSupplements("run", supplements, messages))
    }

    @Test fun steeringQueueRetainsExactMediaWithEachInputAndSeals() {
        val controller = AgentRunController()
        val first = AgentRunController.SteeringInput("same", "[\"/cache/a.jpg\"]")
        val second = AgentRunController.SteeringInput("same", "[\"/cache/b.jpg\"]")
        assertTrue(controller.steer(first))
        assertTrue(controller.steer(second))
        assertEquals(first, controller.pollSteeringInput())
        assertEquals(second, controller.pollSteeringInputOrSeal())
        assertNull(controller.pollSteeringInputOrSeal())
        assertFalse(controller.steer(first))
    }

    @Test fun rejectsInlineAndOversizedSupplementPayloads() {
        assertTrue(runCatching { AgentSupplementMedia.persistedImages("[\"data:image/jpeg;base64,AA==\"]") }.isFailure)
        assertTrue(runCatching { AgentSupplementMedia.persistedImages(" ".repeat(64_001)) }.isFailure)
        val images = AgentSupplementMedia.persistedImages("[\"/cache/a.jpg\"]")
        assertEquals("/cache/a.jpg", images.single().path)
    }
}
