package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoSpeechTest {
    private val mp3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 0, 0, 0, 0)
    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3",
        apiKey = "volc-key",
        model = "seed-audio-1.0",
        systemPrompt = "never send",
    )

    private fun client(code: Int = 200, type: String, bytes: ByteArray, inspect: (okhttp3.Request) -> Unit = {}): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .header("Content-Type", type).body(bytes.toResponseBody(type.toMediaType())).build()
        }.build()

    @Test
    fun extraModelsAppearForVolcengineHost() {
        val provider = OpenAiCompatibleProviderSetting(
            id = "p", name = "火山", baseUrl = "https://ark.cn-beijing.volces.com/api/coding/v3", apiKey = "k",
        )
        val ids = DoubaoSpeech.extraModels(provider).map { it.modelId }
        assertTrue(ids.contains("seed-tts-2.0"))
        assertTrue(ids.contains("seed-audio-1.0"))
        assertTrue(DoubaoSpeech.extraModels(provider.copy(baseUrl = "https://example.com/v1")).isEmpty())
    }

    @Test
    fun createUsesOfficialUrlAndVoiceKey() = runBlocking {
        val json = JSONObject().put("audio", Base64.getEncoder().encodeToString(mp3)).toString()
        val http = client(type = "application/json", bytes = json.toByteArray()) { request ->
            assertEquals(DoubaoSpeech.CREATE_URL, request.url.toString())
            assertEquals("volc-key", request.header("X-Api-Key"))
            assertEquals(null, request.header("Authorization"))
            assertFalse(request.header("X-Api-Request-Id").isNullOrBlank())
            val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
            val payload = JSONObject(body)
            assertEquals("seed-audio-1.0", payload.getString("model"))
            assertEquals("你好", payload.getString("text_prompt"))
            assertEquals("zh_female_vv_uranus_bigtts", payload.getJSONArray("references").getJSONObject(0).getString("speaker"))
            assertEquals("mp3", payload.getJSONObject("audio_config").getString("format"))
            assertFalse(body.contains("never send"))
        }
        assertArrayEquals(mp3, CloudSpeechSynthesizer(http).synthesize(config, "你好", "zh_female_vv_uranus_bigtts"))
    }

    @Test
    fun seedTtsUsesUnidirectionalResource() = runBlocking {
        val http = client(type = "audio/mpeg", bytes = mp3) { request ->
            assertEquals(DoubaoSpeech.UNIDIRECTIONAL_URL, request.url.toString())
            assertEquals("seed-tts-2.0", request.header("X-Api-Resource-Id"))
            val body = JSONObject(Buffer().also { request.body!!.writeTo(it) }.readUtf8())
            assertEquals("你好", body.getJSONObject("req_params").getString("text"))
            assertEquals("zh_male_yunzhou_jupiter_bigtts", body.getJSONObject("req_params").getString("speaker"))
        }
        val tts = config.copy(model = "seed-tts-2.0")
        assertArrayEquals(mp3, CloudSpeechSynthesizer(http).synthesize(tts, "你好", "zh_male_yunzhou_jupiter_bigtts"))
    }

    @Test
    fun createErrorJsonDoesNotLeakSecret() = runBlocking {
        val http = client(401, "application/json", "{\"error\":\"volc-key/private\"}".toByteArray())
        val message = try {
            CloudSpeechSynthesizer(http).synthesize(config, "你好", "a")
            throw AssertionError("expected failure")
        } catch (e: SpeechPlaybackFailure) {
            e.message.orEmpty()
        }
        assertTrue(message.contains("401"))
        assertFalse(message.contains("volc-key"))
        assertFalse(message.contains("private"))
    }
}
