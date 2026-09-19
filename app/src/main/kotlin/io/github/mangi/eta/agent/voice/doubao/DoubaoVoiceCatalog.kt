package io.github.mangi.eta.agent.voice.doubao

import io.github.mangi.eta.agent.model.AgentHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Control-plane credentials are used only for this explicitly requested catalog refresh. */
internal object DoubaoVoiceCatalog {
    private const val QUERY = "Action=BatchListMegaTTSTrainStatus&Version=2025-05-21"
    private const val HOST = "open.volcengineapi.com"
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hash(text: String) = hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray()))
    private fun hmac(key: ByteArray, text: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(text.toByteArray())
    }
    internal fun signedRequest(ak: String, sk: String, body: String, now: Instant): Request {
        val date = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(now)
        val day = date.take(8); val digest = hash(body)
        val headers = "host;x-content-sha256;x-date"
        val scope = "$day/cn-beijing/speech_saas_prod/request"
        val canonical = "POST\n/\n$QUERY\nhost:$HOST\nx-content-sha256:$digest\nx-date:$date\n\n$headers\n$digest"
        var key = hmac(sk.toByteArray(), day)
        key = hmac(key, "cn-beijing"); key = hmac(key, "speech_saas_prod"); key = hmac(key, "request")
        val signature = hex(hmac(key, "HMAC-SHA256\n$date\n$scope\n${hash(canonical)}"))
        return Request.Builder().url("https://$HOST/?$QUERY").header("X-Date", date).header("X-Content-Sha256", digest)
            .header("Authorization", "HMAC-SHA256 Credential=$ak/$scope, SignedHeaders=$headers, Signature=$signature")
            .post(body.toRequestBody("application/json; charset=UTF-8".toMediaType())).build()
    }
    fun list(ak: String, sk: String, project: String): List<Pair<String, String>> {
        require(ak.isNotBlank() && sk.isNotBlank() && project.isNotBlank()) { "请填写 AK、SK 和项目名" }
        val result = linkedMapOf<String, String>()
        for (state in listOf("Success", "Active")) {
            for (page in 1..20) {
                val body = JSONObject().put("ProjectName", project).put("State", state).put("PageNumber", page).put("PageSize", 100).toString()
                val root = AgentHttpClient.modelClient.newCall(signedRequest(ak, sk, body, Instant.now())).execute().use { response ->
                    check(response.isSuccessful) { "音色列表 HTTP ${response.code}" }
                    val source = response.body.source()
                    check(!source.request(2L * 1024 * 1024 + 1)) { "音色列表响应过大" }
                    JSONObject(source.readUtf8())
                }
                val error = root.optJSONObject("ResponseMetadata")?.optJSONObject("Error")
                check(error == null) { "音色列表错误：${error?.optString("Code")}" }
                val data = root.getJSONObject("Result"); val items = data.optJSONArray("Statuses") ?: break
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i); val id = item.optString("SpeakerID")
                    if (id.startsWith("S_")) result[id] = item.optString("Alias").ifBlank { id }
                }
                if (page * 100 >= data.optInt("TotalCount", items.length()) || items.length() == 0) break
                check(page < 20) { "音色超过 2000 条，请按项目缩小查询范围" }
            }
        }
        return result.map { it.key to it.value }
    }
}
