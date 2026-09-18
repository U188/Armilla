package io.github.mangi.eta.agent.voice.tts

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechVoicesTest {
    private val mimo = SpeechVoices.catalog(SpeechEngine.MIMO).map { it.id }

    @Test fun keepsSavedVoiceWhileProviderCatalogIsStillLoading() {
        assertFalse(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = true,
                providerId = "mimo",
                providerReady = false,
                storedVoice = "冰糖",
                catalogIds = SpeechVoices.catalog(SpeechEngine.OPENAI).map { it.id },
            ),
        )
    }

    @Test fun keepsSavedMimoVoiceAfterCatalogLoads() {
        assertFalse(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = true,
                providerId = "mimo",
                providerReady = true,
                storedVoice = "冰糖",
                catalogIds = mimo,
            ),
        )
    }

    @Test fun fillsBlankVoiceFromCatalog() {
        assertTrue(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = true,
                providerId = "mimo",
                providerReady = true,
                storedVoice = "",
                catalogIds = mimo,
            ),
        )
    }

    @Test fun replacesVoiceThatDoesNotBelongToTheLoadedCatalog() {
        assertTrue(
            SpeechVoices.shouldReplaceStoredVoice(
                cloud = true,
                providerId = "mimo",
                providerReady = true,
                storedVoice = "alloy",
                catalogIds = mimo,
            ),
        )
    }
}
