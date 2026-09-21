package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ImageRequestParametersTest {
    private val empty = AgentImageGenerationOptions()
    @Test fun directDirectiveIsSharedAndNotSentAsPrompt() {
        val p = ImagePromptOptions.parse("画猫\nimage_options: {\"aspect_ratio\":\"9:16\",\"resolution\":\"2k\"}")
        assertEquals("画猫", p.prompt)
        val r = ImageRequestParameters.prepare(JSONObject(), p.options, empty)
        assertEquals("9:16", r.body.getString("aspect_ratio"))
        assertEquals("2k", r.body.getString("resolution"))
    }
    @Test fun proseTimesExamplesAndNegationsAreNotGuessed() {
        for (text in listOf("9:16 开会", "不要2k方图", "例如1024x1024")) {
            assertTrue(ImagePromptOptions.parse(text).options.isEmpty)
        }
    }
    @Test fun unambiguousShapeClausesAreSharedWithDirectChat() {
        for (prompt in listOf("画猫，9:16，2k", "画猫；比例9:16；分辨率2k", "画猫\n9:16\n2K")) {
            val result = ImagePromptOptions.parse(prompt)
            assertEquals("9:16", result.options.aspectRatio)
            assertEquals("2k", result.options.resolution)
        }
    }
    @Test fun negatedOrExampleGeometryDoesNotSilentlyRevertToDefaults() {
        assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse("不要方图，9:16，2k") }
        assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse("例如，9:16，2k") }
    }
    @Test fun conflictingShapeClausesFailRatherThanChoosingOne() {
        assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse("画猫，9:16，1:1") }
        assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse("画猫，9:16，尺寸1024x1024") }
    }
    @Test fun duplicateOrFencedDirectiveFails() {
        for (text in listOf("image_options: {}\nimage_options: {}", "```\nimage_options: {}\n```", "image_options: nope")) {
            assertThrows(ImageGenerationParameterException::class.java) { ImagePromptOptions.parse(text) }
        }
    }
    @Test fun explicitWinsOverInlineAndDefaultsWithoutModifyingOtherFields() {
        val r = ImageRequestParameters.prepare(JSONObject("""{"size":"1024x1024","seed":42}"""),
            AgentImageGenerationOptions(aspectRatio = "1:1", resolution = "1k"),
            AgentImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        assertEquals("9:16", r.options.aspectRatio)
        assertEquals("2k", r.options.resolution)
        assertFalse(r.body.has("size"))
        assertEquals(42, r.body.getInt("seed"))
    }
    @Test fun nestedFieldsAndMappedDefaultsWorkWithoutLeakingPrivateConfig() {
        val body = JSONObject("""{"prompt":"secret prompt","generationConfig":{"imageConfig":{"aspectRatio":"1:1","other":true}},"eta_image_config":{"fields":{"aspect_ratio":"generationConfig.imageConfig.aspectRatio","resolution":"generationConfig.imageConfig.imageSize"}}}""")
        val r = ImageRequestParameters.prepare(body, empty, AgentImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        val image = r.body.getJSONObject("generationConfig").getJSONObject("imageConfig")
        assertEquals("9:16", image.getString("aspectRatio"))
        assertEquals("2k", image.getString("imageSize"))
        assertTrue(image.getBoolean("other"))
        assertFalse(r.body.has("eta_image_config")); assertFalse(r.body.has("aspect_ratio"))
        assertFalse(r.summary.contains("secret prompt"))
    }
    @Test fun sizeConversionRequiresExplicitExactMappingIncludingTier() {
        val body = JSONObject("""{"eta_image_config":{"protocol":"size","sizes":{"9:16@2k":"1152x2048"}}}""")
        val r = ImageRequestParameters.prepare(body, empty, AgentImageGenerationOptions(aspectRatio = "9:16", resolution = "2k"))
        assertEquals("1152x2048", r.body.getString("size"))
        assertFalse(r.body.has("aspect_ratio")); assertFalse(r.body.has("resolution"))
        assertTrue(r.options.dimensionReport(1024, 1024).contains("MISMATCH"))
    }
    @Test fun approximateOrMissingMappingFails() {
        for (sizes in listOf("{}", """{"9:16":"1024x1536"}""")) {
            assertThrows(ImageGenerationParameterException::class.java) {
                ImageRequestParameters.prepare(JSONObject("""{"eta_image_config":{"protocol":"size","sizes":$sizes}}"""),
                    empty, AgentImageGenerationOptions(aspectRatio = "9:16"))
            }
        }
    }
    @Test fun protectedAndCollidingPathsFail() {
        for (fields in listOf("""{"aspect_ratio":"prompt"}""", """{"aspect_ratio":"foo","size":"foo.bar"}""")) {
            assertThrows(ImageGenerationParameterException::class.java) {
                ImageRequestParameters.prepare(JSONObject("""{"eta_image_config":{"fields":$fields}}"""), empty, empty)
            }
        }
    }
}
