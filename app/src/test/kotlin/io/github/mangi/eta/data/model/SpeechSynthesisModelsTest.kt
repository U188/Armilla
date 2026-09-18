package io.github.mangi.eta.data.model

import org.junit.Assert.*
import org.junit.Test

class SpeechSynthesisModelsTest {
    @Test fun dedicatedModelsAreNotChatModels() {
        listOf("tts-1", "gpt-4o-mini-tts", "cosyvoice-v2", "speech-01-hd").forEach {
            assertTrue(it, SpeechSynthesisModels.matches(it))
        }
    }
    @Test fun audioOutputAloneDoesNotImplySpeechEndpoint() {
        assertFalse(SpeechSynthesisModels.matches("gpt-4o-audio", listOf("audio")))
        assertFalse(SpeechSynthesisModels.matches("custom", listOf("audio")))
        assertFalse(SpeechSynthesisModels.matches("whisper-tts"))
        assertFalse(SpeechSynthesisModels.matches("gpt-5"))
    }
    @Test fun oauthAndAnthropicAreNotSpeechProviders() {
        assertFalse(SpeechSynthesisModels.allowsSpeechEndpoint(AnthropicProviderSetting("a", "a", "https://example.com")))
        assertFalse(SpeechSynthesisModels.allowsSpeechEndpoint(OpenAiCompatibleProviderSetting("a", "a", "https://example.com", authMode = ProviderAuthMode.OAUTH)))
        assertTrue(SpeechSynthesisModels.allowsSpeechEndpoint(CustomProviderSetting("a", "a", "https://example.com/v1")))
    }

    @Test fun openspeechIsSpeechOnly() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "d", name = "豆包语音", baseUrl = "https://openspeech.bytedance.com", apiKey = "k",
        )
        assertTrue(SpeechSynthesisModels.isSpeechOnlyProvider(provider))
        assertFalse(
            SpeechSynthesisModels.isSpeechOnlyProvider(
                provider.copy(baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3"),
            ),
        )
    }
}
