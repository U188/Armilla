package io.github.mangi.eta.agent.voice.offline

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class OfflineSpeechPackTest {
    @get:Rule val folder = TemporaryFolder()
    private val asset = SpeechModelManifest.Asset("test", 3,
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

    @Test fun disabledByDefaultIncludingAfterDownload() {
        assertFalse(OfflineSpeechPack.State().enabled)
        assertFalse(OfflineSpeechPack.State(checking = false, ready = true).enabled)
    }
    @Test fun onlyExactLengthAndHashPassValidation() {
        val file = folder.newFile("test")
        file.writeText("abc")
        assertTrue(OfflineSpeechPack.valid(file, asset))
        file.writeText("abd")
        assertFalse(OfflineSpeechPack.valid(file, asset))
        file.writeText("ab")
        assertFalse(OfflineSpeechPack.valid(file, asset))
        file.delete()
        assertFalse(OfflineSpeechPack.valid(file, asset))
    }
}
