package io.github.mangi.eta.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoGenerationModelsTest {
    @Test
    fun detectsKnownVideoGenerationIds() {
        listOf(
            "wanx2.1-t2v-turbo",
            "wan2.2-t2v-plus",
            "veo-3.0-generate",
            "sora-2",
            "sora",
            "kling-v1",
            "kwaivgi/kling-v2-master",
            "seedance-1.0",
            "vidu-q1",
        ).forEach { id ->
            assertTrue(id, VideoGenerationModels.matches(id))
            assertTrue(id, model(id).supportsVideoGeneration)
            assertFalse(id, model(id).supportsImageGeneration)
        }
    }

    @Test
    fun keepsChatAndImageModelsOut() {
        listOf(
            "gpt-5.5",
            "qwen3-vl-plus",
            "gemini-2.5-flash",
            "dall-e-3",
            "qwen-image-2.0-pro-2026-06-22",
            "sparkling-v1",
        ).forEach { id ->
            assertFalse(id, VideoGenerationModels.matches(id))
            assertFalse(id, model(id).supportsVideoGeneration)
        }
    }

    @Test
    fun videoOnlyOutputIsVideoGeneration() {
        val model = Model(
            id = "custom",
            modelId = "custom-model",
            displayName = "custom-model",
            outputModalities = listOf(Model.VIDEO_MODALITY),
        )
        assertTrue(model.supportsVideoGeneration)
        assertFalse(model.supportsImageGeneration)
    }

    private fun model(id: String): Model = Model(id = id, modelId = id, displayName = id)
}
