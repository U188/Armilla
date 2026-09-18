package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.model.ProviderSourceTypes
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEngineTest {
    @Test fun mimoHostUsesMimoEngineAndVoices() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "m",
            name = "mimo",
            baseUrl = "https://api.xiaomimimo.com/v1",
            apiKey = "k",
            sourceType = ProviderSourceTypes.MIMO,
        )
        assertEquals(SpeechEngine.MIMO, SpeechEngineResolver.resolve(provider, "mimo-v2.5-tts-voiceclone"))
        assertEquals("mimo_default", SpeechVoices.catalog(SpeechEngine.MIMO).first().id)
        assertTrue(SpeechVoices.catalog(SpeechEngine.MIMO).none { it.id == "alloy" })
    }

    @Test fun mimoSseConcatenatesPcm() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val encoded = Base64.getEncoder().encodeToString(pcm)
        val sse = """
            data: {"choices":[{"delta":{"audio":{"data":"$encoded"}}}]}

            data: [DONE]

        """.trimIndent()
        assertTrue(SpeechProtocols.decodeMimoSse(sse).contentEquals(pcm))
    }
}
