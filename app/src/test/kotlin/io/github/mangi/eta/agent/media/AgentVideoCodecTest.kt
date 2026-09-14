package io.github.mangi.eta.agent.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVideoCodecTest {
    @Test
    fun formatDurationUsesMinutesAndHours() {
        assertEquals("0:00", AgentVideoCodec.formatDuration(0))
        assertEquals("0:05", AgentVideoCodec.formatDuration(5_000))
        assertEquals("1:01", AgentVideoCodec.formatDuration(61_000))
        assertEquals("1:02:03", AgentVideoCodec.formatDuration(3_723_000))
    }

    @Test
    fun detectsVideoMimeAndCachedPaths() {
        assertTrue(AgentVideoCodec.isVideoMime("video/mp4"))
        assertFalse(AgentVideoCodec.isVideoMime("image/jpeg"))
        assertTrue(AgentVideoCodec.isVideoSource("/cache/eta-chat-images/c1/clip.mp4"))
        assertTrue(AgentVideoCodec.isVideoSource("file:///cache/clip.webm"))
        assertFalse(AgentVideoCodec.isVideoSource("/cache/eta-chat-images/c1/photo.jpg"))
        assertEquals("mov", AgentVideoCodec.extensionForMime("video/mp4", "clip.MOV"))
        assertEquals("mp4", AgentVideoCodec.extensionForMime("video/mp4"))
        assertEquals("webm", AgentVideoCodec.extensionForMime("video/webm"))
    }

    @Test
    fun sniffsMp4AndWebmMagic() {
        val mp4 = byteArrayOf(0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d)
        val webm = byteArrayOf(0x1A.toByte(), 0x45, 0xDF.toByte(), 0xA3.toByte())
        assertEquals("video/mp4", AgentVideoCodec.sniffMime(mp4))
        assertEquals("video/webm", AgentVideoCodec.sniffMime(webm))
        assertEquals(null, AgentVideoCodec.sniffMime(byteArrayOf(1, 2, 3)))
    }
}
