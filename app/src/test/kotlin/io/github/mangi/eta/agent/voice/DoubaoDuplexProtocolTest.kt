package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class DoubaoDuplexProtocolTest {
    @Test fun realtimeVoiceUsesIndependentDefault() {
        assertEquals("zh_female_xiaohe_jupiter_bigtts", DoubaoDuplexProtocol.resolveVoice(""))
        assertEquals("zh_female_xiaohe_jupiter_bigtts", DoubaoDuplexProtocol.resolveVoice("  "))
        assertEquals("zh_female_xiaohe_jupiter_bigtts", DoubaoDuplexProtocol.resolveVoice(" custom_voice_id "))
        assertEquals("zh_male_yunzhou_jupiter_bigtts", DoubaoDuplexProtocol.resolveVoice(" zh_male_yunzhou_jupiter_bigtts "))
    }

    @Test fun audioFieldTakesPriorityWithLegacyDeltaFallback() {
        assertEquals("AAABAA==", DoubaoDuplexProtocol.audioPayload(
            org.json.JSONObject().put("audio", "AAABAA==").put("delta", "ignored")))
        assertEquals("AAABAA==", DoubaoDuplexProtocol.audioPayload(
            org.json.JSONObject().put("delta", "AAABAA==")))
    }

    @Test fun repeatedAsrHypothesesReplaceRatherThanAccumulate() {
        var transcript = ""
        for (hypothesis in listOf("你", "你好", "你好。", "你好", "你好。")) {
            transcript = DoubaoDuplexProtocol.eventText(org.json.JSONObject().put("delta", hypothesis))
        }
        assertEquals("你好。", transcript)
        assertEquals("你好。", DoubaoDuplexProtocol.eventText(
            org.json.JSONObject().put("text", "你好。").put("delta", "你好")))
    }

    @Test fun sessionEnablesSecondPassRecognitionAtTopLevel() {
        val event = DoubaoDuplexProtocol.sessionCreate("voice", "instructions")
        assertTrue(event.getJSONObject("extension").getJSONObject("asr")
            .getJSONObject("extra").getBoolean("enable_asr_twopass"))
        assertFalse(event.getJSONObject("session").has("extension"))
    }

    @Test fun apiKeyAuthDoesNotSendLegacyHeaders() {
        val request = DoubaoDuplexProtocol.request("test-key")
        assertEquals("test-key", request.header("X-Api-Key"))
        assertNull(request.header("X-Api-App-Key"))
        assertNull(request.header("X-Api-Access-Key"))
        assertNull(request.header("X-Api-Resource-Id"))
    }

    @Test fun newSessionRequestsSigned16BitOutputAnd16BitMicrophoneInput() {
        val session = DoubaoDuplexProtocol.sessionCreate("voice", "instructions").getJSONObject("session")
        assertFalse(session.has("id"))
        val audio = session.getJSONObject("audio")
        assertEquals("pcm_s16le", audio.getJSONObject("output").getJSONObject("format").getString("type"))
        assertEquals(24000, audio.getJSONObject("output").getJSONObject("format").getInt("rate"))
        assertEquals("pcm", audio.getJSONObject("input").getJSONObject("format").getString("type"))
        assertEquals(16000, audio.getJSONObject("input").getJSONObject("format").getInt("rate"))
    }
}
