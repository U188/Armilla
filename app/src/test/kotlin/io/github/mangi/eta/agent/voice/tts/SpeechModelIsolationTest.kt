package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import org.junit.Assert.*
import org.junit.Test

class SpeechModelIsolationTest {
    private val provider = OpenAiCompatibleProviderSetting(
        id = "p", name = "p", baseUrl = "https://example.com/v1", apiKey = "key",
        models = listOf(Model("chat", "gpt-chat", "chat"), Model("voice", "tts-1", "voice")),
    )
    @Test fun chatPickerExcludesDedicatedTtsEvenForOldSavedSelection() {
        val state = AgentModelPickerProjector.project(listOf(provider), "p", "voice")
        assertNull(state.selectedModel)
        assertEquals(listOf("chat"), state.providerGroups.single().models.map { it.id })
    }
    @Test fun speechPickerCanExplicitlySelectVoiceModel() {
        val state = AgentModelPickerProjector.project(listOf(provider), "p", "voice", includeSpeechModels = true)
        assertEquals("voice", state.selectedModel?.id)
    }
    @Test fun directAgentClientCannotBypassThePicker() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://example.com/v1", apiKey = "", model = "tts-1", systemPrompt = "")
        assertThrows(IllegalArgumentException::class.java) { ProviderClientFactory.getClient(config) }
    }
}
