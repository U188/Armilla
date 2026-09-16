package io.github.mangi.eta.agent.model.oauth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAntigravityOAuthTest {
    @Test
    fun cloudcodeHostIsAntigravityEndpoint() {
        assertTrue(GoogleAntigravityOAuth.isAntigravityEndpoint("https://cloudcode-pa.googleapis.com"))
        assertTrue(GoogleAntigravityOAuth.isAntigravityEndpoint("https://daily-cloudcode-pa.googleapis.com"))
        assertTrue(GoogleAntigravityOAuth.isAntigravityEndpoint("https://cloudcode-pa.googleapis.com/"))
        assertFalse(GoogleAntigravityOAuth.isAntigravityEndpoint("https://chatgpt.com/backend-api/codex"))
        assertFalse(GoogleAntigravityOAuth.isAntigravityEndpoint("https://api.openai.com/v1"))
    }

    @Test
    fun parseModelsReadsObjectCatalog() {
        val models = GoogleAntigravityOAuth.parseModels(
            """{"models":{"gemini-3-flash":{"displayName":"Gemini 3 Flash","model":"gemini-3-flash"},"claude-sonnet-4-6":{"displayName":"Claude Sonnet 4.6","model":"claude-sonnet-4-6"}}}"""
        )
        assertEquals(listOf("gemini-3-flash", "claude-sonnet-4-6"), models.map { it.modelId })
        assertEquals("Gemini 3 Flash", models.first().displayName)
    }

    @Test
    fun filtersPlaceholderAndImageModels() {
        val models = GoogleAntigravityOAuth.parseModels(
            """{"models":{
              "gemini-3-flash":{"displayName":"Gemini 3 Flash","model":"gemini-3-flash"},
              "MODEL_PLACEHOLDER_1":{"displayName":"MODEL_PLACEHOLDER_1","model":"MODEL_PLACEHOLDER_1"},
              "gemini-3.1-flash-image":{"displayName":"Gemini 3.1 Flash Image","model":"gemini-3.1-flash-image"}
            }}"""
        )
        assertEquals(listOf("gemini-3-flash"), models.map { it.modelId })
        assertFalse(GoogleAntigravityOAuth.isUsableModel("MODEL_PLACEHOLDER_foo"))
        assertTrue(GoogleAntigravityOAuth.isUsableModel("gemini-3-pro-high", "Gemini 3 Pro High"))
    }

    @Test
    fun defaultModelsCoverCurrentLineup() {
        val ids = GoogleAntigravityOAuth.defaultModels().map { it.modelId }
        assertTrue(ids.contains("gemini-3.1-flash") || ids.contains("gemini-3-flash"))
        assertTrue(ids.contains("claude-sonnet-4-6"))
        assertTrue(ids.contains("gemini-3.1-pro") || ids.contains("gemini-3-pro-high"))
        assertTrue(GoogleAntigravityOAuth.defaultModels().all { it.toolCall == true })
    }
}
