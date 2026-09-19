package io.github.mangi.eta.agent.voice.doubao

import java.nio.ByteBuffer
import java.util.zip.GZIPInputStream
import org.json.JSONObject

internal object DoubaoAsrProtocol {
    const val URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"
    val resources = listOf("volc.seedasr.sauc.duration", "volc.seedasr.sauc.concurrent", "volc.bigasr.sauc.duration", "volc.bigasr.sauc.concurrent")
    fun config(): ByteArray = frame(1, JSONObject()
        .put("user", JSONObject().put("uid", "daiyu"))
        .put("audio", JSONObject().put("format", "pcm").put("codec", "raw").put("rate", 16000).put("bits", 16).put("channel", 1))
        .put("request", JSONObject().put("model_name", "bigmodel").put("enable_nonstream", true)
            .put("enable_itn", true).put("enable_punc", true).put("show_utterances", true)
            .put("result_type", "full").put("end_window_size", 1000)).toString().toByteArray())
    fun frame(type: Int, payload: ByteArray, last: Boolean = false): ByteArray =
        ByteBuffer.allocate(8 + payload.size).put(0x11.toByte()).put(((type shl 4) or if (last) 2 else 0).toByte())
            .put(if (type == 1) 0x10.toByte() else 0.toByte()).put(0.toByte()).putInt(payload.size).put(payload).array()
    data class Result(val text: String, val final: Boolean, val utteranceDone: Boolean)
    fun decode(bytes: ByteArray): Result {
        require(bytes.size in 8..1_048_576) { "ASR 响应长度无效" }
        val buffer = ByteBuffer.wrap(bytes)
        val header = buffer.get().toInt() and 255
        require(header shr 4 == 1 && (header and 15) >= 1)
        val kind = buffer.get().toInt() and 255
        val codec = buffer.get().toInt() and 255
        buffer.position((header and 15) * 4)
        if (kind shr 4 == 15) error("豆包 ASR 错误码 ${buffer.int}")
        require(kind shr 4 == 9) { "未知 ASR 响应类型" }
        if (kind and 1 != 0) buffer.int
        val size = buffer.int
        require(size >= 0 && size == buffer.remaining())
        var data = ByteArray(size).also(buffer::get)
        require(codec and 15 in 0..1 && codec shr 4 == 1)
        if (codec and 15 == 1) data = GZIPInputStream(data.inputStream()).use {
            val out = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) { val n = it.read(chunk); if (n < 0) break; require(out.size() + n <= 1_048_576); out.write(chunk, 0, n) }
            out.toByteArray()
        }
        val root = JSONObject(data.toString(Charsets.UTF_8))
        val code = root.optInt("code", 1000)
        check(code == 1000 || code == 0 || code == 20000000) { "豆包 ASR 错误码 $code" }
        val result = root.optJSONObject("result")
        val utterances = result?.optJSONArray("utterances")
        return Result(result?.optString("text").orEmpty(), kind and 2 != 0,
            utterances != null && utterances.length() > 0 && utterances.optJSONObject(utterances.length() - 1)?.optBoolean("definite") == true)
    }
}
