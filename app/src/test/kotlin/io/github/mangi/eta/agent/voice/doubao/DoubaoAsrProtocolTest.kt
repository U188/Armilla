package io.github.mangi.eta.agent.voice.doubao

import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [34])
class DoubaoAsrProtocolTest {
    @Test fun requestAndFinalAudioUseDocumentedHeaders() {
        val config = DoubaoAsrProtocol.config()
        assertEquals(0x11, config[0].toInt()); assertEquals(0x10, config[1].toInt())
        val text = config.copyOfRange(8, config.size).toString(Charsets.UTF_8)
        assertTrue(text.contains("enable_nonstream")); assertTrue(text.contains("full"))
        val final = DoubaoAsrProtocol.frame(2, byteArrayOf(1, 2), true)
        assertEquals(0x22, final[1].toInt()); assertEquals(2, ByteBuffer.wrap(final, 4, 4).int)
    }
    private fun response(text: String, gzip: Boolean = false, final: Boolean = false): ByteArray {
        var bytes = """{"result":{"text":"$text","utterances":[{"definite":true}]}}""".toByteArray()
        if (gzip) bytes = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
        return ByteBuffer.allocate(12 + bytes.size).put(0x11.toByte()).put(if (final) 0x93.toByte() else 0x91.toByte())
            .put(if (gzip) 0x11.toByte() else 0x10.toByte()).put(0.toByte()).putInt(if (final) -2 else 2)
            .putInt(bytes.size).put(bytes).array()
    }
    @Test fun fullHypothesesReplaceRatherThanAppend() {
        assertEquals("你好", DoubaoAsrProtocol.decode(response("你好")).text)
        val final = DoubaoAsrProtocol.decode(response("你好世界", gzip = true, final = true))
        assertEquals("你好世界", final.text); assertTrue(final.final); assertTrue(final.utteranceDone)
    }
    @Test(expected = IllegalArgumentException::class) fun truncatedPacketIsRejected() {
        DoubaoAsrProtocol.decode(response("hi").dropLast(1).toByteArray())
    }
    @Test fun personalVoiceCapabilityRequiresTrainedIcl2() {
        val base = PersonalVoices.Voice("etaClone123", "Test", "account", 1, setOf(4))
        assertFalse(base.tts); assertTrue(base.copy(status = 2).tts)
        assertFalse(base.copy(status = 2, models = setOf(1)).tts)
        assertNotEquals(PersonalVoices.account("a"), PersonalVoices.account("b"))
    }
}
