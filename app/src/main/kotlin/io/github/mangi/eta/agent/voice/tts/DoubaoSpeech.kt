package io.github.mangi.eta.agent.voice.tts

import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import java.util.UUID
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** 豆包语音 HTTP：音频生成 create，以及 seed-tts 单向流。不走 OpenAI /audio/speech。 */
internal object DoubaoSpeech {
    const val CREATE_URL = "https://openspeech.bytedance.com/api/v3/tts/create"
    const val UNIDIRECTIONAL_URL = "https://openspeech.bytedance.com/api/v3/tts/unidirectional"

    fun matchesModel(modelId: String): Boolean {
        val id = modelId.lowercase()
        return "seed-audio" in id || "seed-tts" in id
    }

    fun isOpenspeech(baseUrl: String): Boolean =
        baseUrl.trim().toHttpUrlOrNull()?.host.equals("openspeech.bytedance.com", ignoreCase = true)

    fun matchesEndpoint(baseUrl: String): Boolean {
        val host = baseUrl.trim().toHttpUrlOrNull()?.host.orEmpty().lowercase()
        return isOpenspeech(baseUrl) ||
            host.endsWith(".volces.com") ||
            host == "volces.com" ||
            host.endsWith(".volcengine.com") ||
            host == "volcengine.com"
    }

    fun extraModels(provider: ProviderSetting): List<Model> =
        SpeechSynthesisModels.catalogModels(provider)

    fun usesCreate(modelId: String): Boolean = "seed-audio" in modelId.lowercase()

    fun resourceId(modelId: String): String {
        val id = modelId.lowercase()
        return when {
            "seed-tts-1.0-concurr" in id -> "seed-tts-1.0-concurr"
            "seed-tts-1.0" in id -> "seed-tts-1.0"
            else -> "seed-tts-2.0"
        }
    }

    fun requestId(): String = UUID.randomUUID().toString()

    fun createBody(model: String, text: String, speaker: String): String =
        JSONObject()
            .put("model", model.ifBlank { "seed-audio-1.0" })
            .put("text_prompt", text)
            .put(
                "references",
                JSONArray().put(JSONObject().put("speaker", speaker)),
            )
            .put(
                "audio_config",
                JSONObject()
                    .put("format", "mp3")
                    .put("sample_rate", 24000),
            )
            .toString()

    fun unidirectionalBody(text: String, speaker: String): String =
        JSONObject()
            .put("user", JSONObject().put("uid", "daiyu"))
            .put(
                "req_params",
                JSONObject()
                    .put("text", text)
                    .put("speaker", speaker)
                    .put(
                        "audio_params",
                        JSONObject().put("format", "mp3").put("sample_rate", 24000),
                    ),
            )
            .toString()

    data class AudioPayload(val bytes: ByteArray, val extension: String)

    fun decodeAudio(bytes: ByteArray): AudioPayload {
        val stripped = stripId3(bytes)
        return when {
            looksLikeMp3(stripped) || looksLikeMp3(bytes) -> AudioPayload(bytes, "mp3")
            looksLikeWav(bytes) -> AudioPayload(bytes, "wav")
            looksLikeOgg(bytes) -> AudioPayload(bytes, "ogg")
            else -> AudioPayload(pcmToWav(bytes), "wav")
        }
    }

    private fun looksLikeMp3(bytes: ByteArray): Boolean {
        if (bytes.size < 4) return false
        val id3 = bytes.size >= 10 && bytes[0] == 73.toByte() && bytes[1] == 68.toByte() && bytes[2] == 51.toByte()
        val frame = (bytes[0].toInt() and 0xff) == 0xff &&
            (bytes[1].toInt() and 0xe0) == 0xe0 && (bytes[1].toInt() and 0x06) != 0
        return id3 || frame
    }

    private fun looksLikeWav(bytes: ByteArray): Boolean =
        bytes.size >= 12 &&
            bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
            bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun looksLikeOgg(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
            bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()

    private fun stripId3(bytes: ByteArray): ByteArray {
        if (bytes.size < 10 || bytes[0] != 73.toByte() || bytes[1] != 68.toByte() || bytes[2] != 51.toByte()) return bytes
        val size = ((bytes[6].toInt() and 0x7f) shl 21) or
            ((bytes[7].toInt() and 0x7f) shl 14) or
            ((bytes[8].toInt() and 0x7f) shl 7) or
            (bytes[9].toInt() and 0x7f)
        val start = 10 + size
        return if (start in 1 until bytes.size) bytes.copyOfRange(start, bytes.size) else bytes
    }

    fun pcmToWav(pcm: ByteArray, sampleRate: Int = 24000, channels: Int = 1, bits: Int = 16): ByteArray {
        val byteRate = sampleRate * channels * bits / 8
        val dataSize = pcm.size
        val out = java.io.ByteArrayOutputStream(44 + dataSize)
        fun writeString(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
        fun writeInt(value: Int) {
            out.write(value and 0xff)
            out.write(value shr 8 and 0xff)
            out.write(value shr 16 and 0xff)
            out.write(value shr 24 and 0xff)
        }
        fun writeShort(value: Int) {
            out.write(value and 0xff)
            out.write(value shr 8 and 0xff)
        }
        writeString("RIFF")
        writeInt(36 + dataSize)
        writeString("WAVE")
        writeString("fmt ")
        writeInt(16)
        writeShort(1)
        writeShort(channels)
        writeInt(sampleRate)
        writeInt(byteRate)
        writeShort(channels * bits / 8)
        writeShort(bits)
        writeString("data")
        writeInt(dataSize)
        out.write(pcm)
        return out.toByteArray()
    }
}
