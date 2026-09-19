package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import org.junit.Assert.*
import org.junit.Test

class VoiceEntryPolicyTest {
    @Test fun allEightCombinationsExposeOnlyEnabledModes() {
        for (bits in 0..7) {
            val config = DoubaoVoiceConfig.Config(
                inputEnabled = bits and 1 != 0,
                conversationEnabled = bits and 2 != 0,
                duplexEnabled = bits and 4 != 0,
            )
            val expected = buildList {
                if (bits and 1 != 0) add(VoiceEntryMode.DICTATION)
                if (bits and 2 != 0) add(VoiceEntryMode.UNIVERSAL)
                if (bits and 4 != 0) add(VoiceEntryMode.DOUBAO_DUPLEX)
            }
            assertEquals(expected, VoiceEntryPolicy.modes(config))
            assertEquals(expected.singleOrNull(), VoiceEntryPolicy.directMode(config))
            assertEquals(expected.size > 1, VoiceEntryPolicy.canChoose(config))
            VoiceEntryMode.entries.forEach {
                assertEquals(it in expected, VoiceEntryPolicy.enabled(config, it))
            }
        }
    }
}
