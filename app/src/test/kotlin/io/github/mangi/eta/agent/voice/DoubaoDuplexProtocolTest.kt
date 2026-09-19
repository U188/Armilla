package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class DoubaoDuplexProtocolTest {
    @Test fun apiKeyAuthDoesNotSendLegacyHeaders() {
        val request = DoubaoDuplexProtocol.request("test-key")
        assertEquals("test-key", request.header("X-Api-Key"))
        assertNull(request.header("X-Api-App-Key"))
        assertNull(request.header("X-Api-Access-Key"))
        assertNull(request.header("X-Api-Resource-Id"))
    }

    @Test fun newSessionUsesPcmWithoutInventingAHistoryId() {
        val session = DoubaoDuplexProtocol.sessionCreate("voice", "instructions").getJSONObject("session")
        assertFalse(session.has("id"))
        val audio = session.getJSONObject("audio")
        assertEquals("pcm", audio.getJSONObject("output").getJSONObject("format").getString("type"))
        assertEquals(24000, audio.getJSONObject("output").getJSONObject("format").getInt("rate"))
        assertEquals(16000, audio.getJSONObject("input").getJSONObject("format").getInt("rate"))
    }
}
