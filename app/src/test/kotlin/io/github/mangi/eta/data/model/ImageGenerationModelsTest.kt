package io.github.mangi.eta.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageGenerationModelsTest {
    @Test
    fun detectsKnownImageGenerationIds() {
        listOf(
            "dall-e-3",
            "gpt-image-1",
            "black-forest-labs/FLUX.1-schnell",
            "qwen-image-2.0-pro-2026-06-22",
            "wanx-v1",
            "hidream-i1-full",
            "imagen-3.0-generate-001",
            "Kolors",
            "stable-diffusion-xl",
        ).forEach { id ->
            assertTrue(id, ImageGenerationModels.matches(id))
            assertTrue(id, model(id).supportsImageGeneration)
        }
    }

    @Test
    fun keepsChatAndVisionModelsOut() {
        listOf(
            "gpt-5.5",
            "qwen3-vl-plus",
            "qwen2.5-vl-72b",
            "llama-3.2-11b-vision",
            "claude-sonnet-4",
            "gemini-2.5-flash",
        ).forEach { id ->
            assertFalse(id, ImageGenerationModels.matches(id))
            assertFalse(id, model(id).supportsImageGeneration)
        }
    }

    @Test
    fun excludesVideoAndOtherSpecialties() {
        listOf(
            "wanx2.1-t2v-turbo",
            "veo-3.0-generate",
            "qwen-tts-2026-05-20",
            "text-embedding-v4",
            "qwen-ocr",
        ).forEach { id ->
            assertFalse(id, ImageGenerationModels.matches(id))
        }
    }

    @Test
    fun imageOnlyOutputIsImageGeneration() {
        val model = Model(
            id = "custom",
            modelId = "custom-model",
            displayName = "custom-model",
            outputModalities = listOf(Model.IMAGE_MODALITY),
        )
        assertTrue(model.supportsImageGeneration)
    }

    private fun model(id: String): Model = Model(id = id, modelId = id, displayName = id)
}
