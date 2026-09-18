package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.agent.model.ProviderUrls
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal class CloudSpeechSynthesizer(
    private val httpClient: OkHttpClient = AgentHttpClient.modelClient.newBuilder()
        .callTimeout(90, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) {
    private val doubaoClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .callTimeout(210, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    suspend fun synthesize(config: AgentModelClient.ModelConfig, text: String, voice: String): ByteArray {
        speechCheck(text.isNotBlank()) { "没有可朗读的文字" }
        speechCheck(voice.isNotBlank()) { "请填写该接口支持的音色 ID" }
        return if (DoubaoSpeech.matchesModel(config.model) || DoubaoSpeech.isOpenspeech(config.baseUrl)) {
            synthesizeDoubao(config, text, voice)
        } else {
            synthesizeOpenAi(config, text, voice)
        }
    }

    private suspend fun synthesizeOpenAi(
        config: AgentModelClient.ModelConfig,
        text: String,
        voice: String,
    ): ByteArray {
        val headers = Headers.Builder().add("Accept", "audio/mpeg")
            .apply { if (config.apiKey.isNotBlank()) add("Authorization", "Bearer ${config.apiKey}") }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }.build()
        val payload = JSONObject().put("model", config.model).put("input", text)
            .put("voice", voice).put("response_format", "mp3").toString()
        val request = Request.Builder().url(ProviderUrls.openAiAudioSpeechUrl(config.baseUrl))
            .headers(headers).post(payload.toRequestBody("application/json".toMediaType())).build()
        return executeAudio(httpClient, request, rawMp3 = true)
    }

    private suspend fun synthesizeDoubao(
        config: AgentModelClient.ModelConfig,
        text: String,
        voice: String,
    ): ByteArray {
        speechCheck(config.apiKey.isNotBlank()) { "请填写豆包语音 API Key" }
        val create = DoubaoSpeech.usesCreate(config.model)
        val headers = Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .add("X-Api-Key", config.apiKey)
            .add("X-Api-App-Key", "aGjiRDfUWi")
            .add("X-Api-Request-Id", DoubaoSpeech.requestId())
            .apply {
                if (!create) add("X-Api-Resource-Id", DoubaoSpeech.resourceId(config.model))
            }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }
            .build()
        val payload = if (create) {
            DoubaoSpeech.createBody(config.model, text, voice)
        } else {
            DoubaoSpeech.unidirectionalBody(text, voice)
        }
        val url = if (create) DoubaoSpeech.CREATE_URL else DoubaoSpeech.UNIDIRECTIONAL_URL
        val request = Request.Builder().url(url)
            .headers(headers).post(payload.toRequestBody("application/json".toMediaType())).build()
        return executeAudio(doubaoClient, request, rawMp3 = false)
    }

    private suspend fun executeAudio(client: OkHttpClient, request: Request, rawMp3: Boolean): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("朗读网络请求失败，请检查网络与接口"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            speechCheck(it.isSuccessful) { "朗读接口 HTTP ${it.code}，请核对 Speech 协议、模型与音色" }
                            val body = it.body
                            speechCheck(body.contentLength() <= MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
                            val out = ByteArrayOutputStream()
                            body.byteStream().use { stream ->
                                val buffer = ByteArray(8192)
                                while (true) {
                                    if (!continuation.isActive) throw IOException("cancelled")
                                    val n = stream.read(buffer)
                                    if (n < 0) break
                                    speechCheck(out.size() + n <= MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
                                    out.write(buffer, 0, n)
                                }
                            }
                            val payload = out.toByteArray()
                            if (rawMp3) {
                                validateAudio(it.header("Content-Type"), payload)
                                payload
                            } else {
                                decodeDoubaoAudio(it.header("Content-Type"), payload)
                            }
                        }
                        if (continuation.isActive) continuation.resume(bytes)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(
                            if (e is SpeechPlaybackFailure) e else SpeechPlaybackFailure("朗读音频读取失败"),
                        )
                    }
                }
            })
        }

    companion object {
        const val MAX_AUDIO_BYTES = 8 * 1024 * 1024
        internal fun validateAudio(contentType: String?, bytes: ByteArray) {
            val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
            speechCheck(type in setOf("audio/mpeg", "audio/mp3", "application/octet-stream")) { "朗读接口未返回 MP3 音频" }
            val id3 = bytes.size >= 10 && bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte()
            val frame = bytes.size >= 4 && (bytes[0].toInt() and 0xff) == 0xff &&
                (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() and 0x06) != 0
            speechCheck(id3 || frame) { "朗读接口返回了无效音频，可能是错误网页或 JSON" }
        }

        internal fun decodeDoubaoAudio(contentType: String?, bytes: ByteArray): ByteArray {
            if (looksLikeMp3(bytes)) {
                validateAudio("audio/mpeg", bytes)
                return bytes
            }
            val type = contentType.orEmpty().substringBefore(';').trim().lowercase()
            speechCheck(
                type.isEmpty() ||
                    type == "application/json" ||
                    type == "text/plain" ||
                    type == "text/event-stream" ||
                    type == "application/octet-stream",
            ) { "朗读接口未返回音频或 JSON" }
            val text = runCatching { bytes.decodeToString() }.getOrDefault("").trim()
            speechCheck(text.isNotBlank()) { "朗读接口返回了空响应" }
            val payloads = extractJsonPayloads(text)
            speechCheck(payloads.isNotEmpty()) { "朗读接口返回了无效音频，可能是错误网页或 JSON" }
            val out = ByteArrayOutputStream()
            var failed = false
            payloads.forEach { json ->
                val code = json.opt("code")
                if (code is Number && code.toInt() != 0 && code.toInt() != 20000000 && code.toInt() != 3000) {
                    failed = true
                    return@forEach
                }
                val audio = json.optString("audio").ifBlank { json.optString("data") }
                if (audio.isBlank()) return@forEach
                val decoded = runCatching {
                    Base64.getDecoder().decode(audio.filterNot { it.isWhitespace() })
                }.getOrNull() ?: return@forEach
                speechCheck(out.size() + decoded.size <= MAX_AUDIO_BYTES) { "朗读音频超过大小限制" }
                out.write(decoded)
            }
            val audioBytes = out.toByteArray()
            speechCheck(audioBytes.isNotEmpty()) {
                if (failed) "朗读接口返回了错误状态" else "朗读接口没有返回音频数据"
            }
            val decoded = DoubaoSpeech.decodeAudio(audioBytes)
            speechCheck(decoded.bytes.isNotEmpty()) { "朗读接口没有返回音频数据" }
            return decoded.bytes
        }

        internal fun extractJsonPayloads(text: String): List<JSONObject> {
            val stripped = text.replace(Regex("^data:", RegexOption.MULTILINE), "").trim()
            runCatching { JSONObject(stripped) }.getOrNull()?.let { return listOf(it) }
            val out = ArrayList<JSONObject>()
            val tokener = org.json.JSONTokener(stripped)
            while (tokener.more()) {
                val next = runCatching { tokener.nextValue() }.getOrNull() ?: break
                if (next is JSONObject) out += next
            }
            if (out.isNotEmpty()) return out
            stripped.lineSequence().forEach { raw ->
                val line = raw.trim().removePrefix("data:").trim()
                if (line.isEmpty() || line == "[DONE]") return@forEach
                runCatching { JSONObject(line) }.getOrNull()?.let(out::add)
            }
            return out
        }

        private fun looksLikeMp3(bytes: ByteArray): Boolean {
            if (bytes.size < 4) return false
            val id3 = bytes.size >= 10 && bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte()
            val frame = (bytes[0].toInt() and 0xff) == 0xff &&
                (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() and 0x06) != 0
            return id3 || frame
        }
    }
}
