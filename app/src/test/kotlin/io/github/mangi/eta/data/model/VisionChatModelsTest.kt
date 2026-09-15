package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionChatModelsTest {
    @Test
    fun detectsKnownVisionChatIds() {
        listOf(
            "qwen3-vl-plus",
            "gpt-4o",
            "gpt-4.1",
            "claude-sonnet-4",
            "gemini-2.5-flash",
            "llama-3.2-11b-vision",
            "kimi-vl",
            "grok-4",
            "grok-4.6",
            "gpt-6",
            "gpt-6-astra",
            "gpt6-astra",
            "openai/gpt-6-astra",
        ).forEach { id ->
            assertTrue(id, VisionChatModels.matches(id))
            assertTrue(id, model(id).supportsVision)
        }
    }

    @Test
    fun keepsTextOnlyModelsOutEvenWithImageModality() {
        listOf(
            "deepseek-chat",
            "deepseek-reasoner",
            "deepseek-r1",
            "qwen3-coder",
            "qwq-32b",
        ).forEach { id ->
            val model = model(id).copy(inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY))
            assertFalse(id, VisionChatModels.matches(model))
            assertFalse(id, model.supportsVision)
        }
    }

    @Test
    fun trustsImageModalityForUnknownChatModels() {
        val model = model("qwen3.7-plus").copy(inputModalities = listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY))
        assertTrue(VisionChatModels.matches(model))
        assertTrue(model.supportsVision)
    }

    @Test
    fun unknownTextOnlyModelsStayClosed() {
        assertFalse(VisionChatModels.matches("unknown-chat"))
        assertFalse(model("unknown-chat").supportsVision)
    }

    @Test
    fun explicitRemoteFalseWinsOverAutomaticIds() {
        assertFalse(model("gpt-4o").copy(attachment = false).supportsVision)
        assertFalse(model().copy(inputModalities = listOf("image"), attachment = false).supportsVision)
    }

    @Test
    fun overrideCanBeResetWithoutDestroyingRemoteMetadata() {
        val remote = model("gpt-4o").copy(inputModalities = listOf("text", "image"), attachment = true)
        val disabled = remote.copy(visionOverride = false)
        assertFalse(disabled.supportsVision)
        assertEquals(remote.inputModalities, disabled.inputModalities)
        assertTrue(disabled.copy(visionOverride = null).supportsVision)
    }

    @Test
    fun manualTrueCanOverrideTextOnlyAndFalseSurvivesRemoteRefreshCopy() {
        assertTrue(model("deepseek-chat").copy(visionOverride = true).supportsVision)
        val refreshed = model("gpt-4o").copy(visionOverride = false).copy(attachment = true, inputModalities = listOf("image"))
        assertFalse(refreshed.supportsVision)
    }

    private fun model(id: String = "gateway") = Model(id = id, modelId = id, displayName = id)
}
