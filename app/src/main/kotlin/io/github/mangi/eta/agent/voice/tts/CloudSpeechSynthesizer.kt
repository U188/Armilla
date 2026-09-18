package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.ProviderRequestHeaders
import io.github.mangi.eta.agent.model.ProviderUrls
import java.io.ByteArrayOutputStream
import java.io.IOException
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
    suspend fun synthesize(config: AgentModelClient.ModelConfig, text: String, voice: String): ByteArray {
        speechCheck(text.isNotBlank()) { "没有可朗读的文字" }
        speechCheck(voice.isNotBlank()) { "请填写该接口支持的音色 ID" }
        val headers = Headers.Builder().add("Accept", "audio/mpeg")
            .apply { if (config.apiKey.isNotBlank()) add("Authorization", "Bearer ${config.apiKey}") }
            .also { ProviderRequestHeaders.mergeInto(it, config.baseUrl, config.customHeaders) }.build()
        val payload = JSONObject().put("model", config.model).put("input", text)
            .put("voice", voice).put("response_format", "mp3").toString()
        val request = Request.Builder().url(ProviderUrls.openAiAudioSpeechUrl(config.baseUrl))
            .headers(headers).post(payload.toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = httpClient.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException("朗读网络请求失败，请检查网络与接口"))
                }
                override fun onResponse(call: Call, response: Response) {
                    try {
                        val bytes = response.use {
                            // Never echo upstream error bodies: they can contain credentials or the user's prose.
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
                            out.toByteArray().also { bytes -> validateAudio(it.header("Content-Type"), bytes) }
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
    }
}
