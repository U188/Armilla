package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionChatModelsTest {
    @Test fun aliasesDoNotOverrideMissingOrTextOnlyMetadata() {
        listOf("gpt-4o", "gpt-5-alias", "grok-4.6", "qwen-vl", "unknown").forEach { id ->
            assertFalse(id, VisionChatModels.matches(id))
            assertFalse(id, model(id).supportsVision)
        }
    }

    @Test fun declaredImageModalityIsTrustedWithoutNameWhitelist() {
        listOf("deepseek-chat", "custom-gateway-alias", "qwen3.7-plus").forEach { id ->
            assertTrue(model(id).copy(inputModalities = listOf("text", "image")).supportsVision)
        }
    }

    @Test fun explicitRemoteFalseWinsOverModalities() {
        assertFalse(model().copy(inputModalities = listOf("image"), attachment = false).supportsVision)
    }

    @Test fun overrideCanBeResetWithoutDestroyingRemoteMetadata() {
        val remote = model().copy(inputModalities = listOf("text", "image"), attachment = true)
        val disabled = remote.copy(visionOverride = false)
        assertFalse(disabled.supportsVision)
        assertEquals(remote.inputModalities, disabled.inputModalities)
        assertTrue(disabled.copy(visionOverride = null).supportsVision)
    }

    @Test fun manualTrueCanOverrideTextOnlyAndFalseSurvivesRemoteRefreshCopy() {
        assertTrue(model().copy(attachment = false, visionOverride = true).supportsVision)
        val refreshed = model().copy(visionOverride = false).copy(attachment = true, inputModalities = listOf("image"))
        assertFalse(refreshed.supportsVision)
    }

    private fun model(id: String = "gateway") = Model(id = id, modelId = id, displayName = id)
}
