package io.github.mangi.eta.data.model

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
            val model = model(id, listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY))
            assertFalse(id, VisionChatModels.matches(model))
            assertFalse(id, model.supportsVision)
        }
    }

    @Test
    fun trustsImageModalityForUnknownChatModels() {
        val model = model("qwen3.7-plus", listOf(Model.TEXT_MODALITY, Model.IMAGE_MODALITY))
        assertTrue(VisionChatModels.matches(model))
        assertTrue(model.supportsVision)
    }

    @Test
    fun manualAttachmentOverridesAutomatic() {
        val enabled = model("deepseek-chat").copy(attachment = true)
        val disabled = model("gpt-4o").copy(attachment = false)
        assertTrue(enabled.supportsVision)
        assertFalse(disabled.supportsVision)
    }

    private fun model(
        id: String,
        inputModalities: List<String> = listOf(Model.TEXT_MODALITY),
    ) = Model(
        id = id,
        modelId = id,
        displayName = id,
        inputModalities = inputModalities,
    )
}
