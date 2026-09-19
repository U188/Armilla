package io.github.mangi.eta.agent.voice.doubao

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentHttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal object PersonalVoices {
    data class Voice(val id: String, val name: String, val account: String, val status: Int = -1,
        val models: Set<Int> = emptySet(), val demo: String = "", val error: String = "", val accepted: Boolean = false) {
        val ready get() = status == 2 || status == 4
        val tts get() = ready && models.any { it == 4 || it == 5 }
    }
    private val mutable = MutableStateFlow<List<Voice>>(emptyList())
    val state = mutable.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val running = mutableSetOf<String>()
    private var loaded = false
    private lateinit var root: File
    fun account(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    @Synchronized fun load(context: Context) {
        if (loaded) return
        root = File(context.filesDir, "personal-voices.json")
        val file = AtomicFile(root)
        mutable.value = runCatching {
            val array = JSONArray(file.openRead().bufferedReader().use { it.readText() })
            (0 until array.length()).map { decode(array.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        loaded = true
    }
    private fun decode(j: JSONObject) = Voice(j.getString("id"), j.getString("name"), j.getString("account"), j.optInt("status", -1),
        j.optJSONArray("models")?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }.orEmpty(),
        j.optString("demo"), j.optString("error"), j.optBoolean("accepted"))
    @Synchronized private fun save(voice: Voice) {
        check(loaded)
        val list = mutable.value.filterNot { it.id == voice.id && it.account == voice.account } + voice
        val array = JSONArray().also { a -> list.forEach { v -> a.put(JSONObject().put("id", v.id).put("name", v.name)
            .put("account", v.account).put("status", v.status).put("models", JSONArray(v.models.toList()))
            .put("demo", v.demo).put("error", v.error).put("accepted", v.accepted)) } }
        val file = AtomicFile(root); val out = file.startWrite()
        try { out.write(array.toString().toByteArray()); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
        mutable.value = list
    }
    fun accept(voice: Voice) { save(voice.copy(accepted = true)) }
    fun find(id: String, key: String) = state.value.firstOrNull { it.id == id && it.account == account(key) }
    fun selected(id: String, key: String): Voice? {
        if (!id.startsWith("etaClone")) return null
        return requireNotNull(find(id, key)?.takeIf { it.tts && it.accepted }) { "个人音色不可用，请检查训练状态、账户和首次使用确认" }
    }
    private fun identity(voice: Voice) = JSONObject().put("speaker_id", "custom_speaker_id").put("custom_speaker_id", voice.id)
    private fun request(path: String, key: String, body: JSONObject): JSONObject {
        val client = AgentHttpClient.modelClient.newBuilder().callTimeout(120, TimeUnit.SECONDS).retryOnConnectionFailure(false).build()
        val req = Request.Builder().url("https://openspeech.bytedance.com/api/v3/tts/$path")
            .header("X-Api-Key", key).header("X-Api-Request-Id", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody("application/json".toMediaType())).build()
        return client.newCall(req).execute().use { response ->
            val text = response.body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream(); val bytes = ByteArray(8192)
                while (true) { val n = input.read(bytes); if (n < 0) break; check(out.size() + n <= 1024 * 1024); out.write(bytes, 0, n) }
                out.toString("UTF-8")
            }
            val json = runCatching { JSONObject(text) }.getOrDefault(JSONObject())
            check(response.isSuccessful && json.optInt("code", 0) in listOf(0, 20000000)) {
                "豆包请求失败：HTTP ${response.code}，错误码 ${json.optInt("code", -1)}"
            }
            json
        }
    }
    private fun updated(v: Voice, j: JSONObject): Voice {
        val items = j.optJSONArray("speaker_status") ?: JSONArray()
        val models = (0 until items.length()).map { items.getJSONObject(it).optInt("model_type") }.toSet()
        return v.copy(status = j.optInt("status", -1), models = models,
            demo = (0 until items.length()).map { items.getJSONObject(it) }.firstOrNull { it.optInt("model_type") in setOf(4, 5) }?.optString("demo_audio").orEmpty(), error = "")
    }
    fun refresh(voice: Voice, key: String) {
        check(account(key) == voice.account) { "请选择创建此音色的账户" }
        synchronized(running) { if (!running.add(voice.id)) return }
        scope.launch {
            try { save(updated(voice, request("get_voice", key, identity(voice)))) }
            catch (e: Exception) { save(voice.copy(error = e.message ?: "查询失败")) }
            finally { synchronized(running) { running.remove(voice.id) } }
        }
    }
    suspend fun create(context: Context, uri: Uri, name: String, key: String): String = withContext(Dispatchers.IO) {
        load(context)
        require(key.isNotBlank() && name.isNotBlank())
        val format = when (context.contentResolver.getType(uri)) {
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/mpeg" -> "mp3"
            "audio/ogg" -> "ogg"
            "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/aac" -> "aac"
            else -> error("请选择 WAV、MP3、OGG、M4A 或 AAC 音频")
        }
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream(); val chunk = ByteArray(8192)
            while (true) { val n = input.read(chunk); if (n < 0) break; require(out.size() + n <= 10 * 1024 * 1024) { "样本不能超过 10 MiB" }; out.write(chunk, 0, n) }
            out.toByteArray()
        } ?: error("无法读取音频")
        require(bytes.isNotEmpty())
        val voice = Voice("etaClone" + UUID.randomUUID().toString().replace("-", ""), name.trim(), account(key))
        // Durable identity BEFORE upload. An interrupted upload is queried, never automatically retrained.
        save(voice)
        scope.launch {
            try {
                val response = request("voice_clone", key, identity(voice).put("audio", JSONObject()
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("format", format)).put("language", 0)
                    .put("extra_params", JSONObject().put("demo_text", "你好，这是我的个人音色试听。")))
                var current = updated(voice, response); save(current)
                repeat(30) {
                    if (current.status != 1) return@launch
                    delay(4000)
                    current = updated(current, request("get_voice", key, identity(current))); save(current)
                }
            } catch (e: Exception) { save(voice.copy(error = (e.message ?: "请求未确认") + "；请查询状态，不会自动重复训练")) }
            finally { bytes.fill(0) }
        }
        voice.id
    }
}
