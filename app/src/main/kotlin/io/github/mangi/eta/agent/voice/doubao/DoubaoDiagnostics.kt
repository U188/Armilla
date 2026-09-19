package io.github.mangi.eta.agent.voice.doubao

import io.github.mangi.eta.core.AndroidAgentLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Interceptor
import okhttp3.Response
import org.json.JSONObject

/** Bounded metadata only. Never reads request bodies, successful audio, or transcripts. */
internal object DoubaoDiagnostics : Interceptor {
    private val mutable = MutableStateFlow<List<String>>(emptyList())
    val state = mutable.asStateFlow()
    internal fun sanitize(text: String, secrets: List<String> = emptyList()): String {
        var safe = text
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach { safe = safe.replace(it, "[redacted]") }
        return safe.replace(Regex("https?://[^\\s\"<>]+"), "[url]")
            .replace(Regex("(?i)(bearer|api[_-]?key|token|secret|authorization|text|transcript|audio|data)\\s*[:=]\\s*[^,;\\n]+"), "$1=[redacted]")
            .replace(Regex("[A-Za-z0-9_+/=-]{24,}"), "[identifier]")
            .replace(Regex("[\\r\\n\\t]+"), " ").take(500)
    }
    private fun id(value: String?) = value.orEmpty().take(96).replace(Regex("[^A-Za-z0-9_-]"), "_")
    @Synchronized fun mark(stage: String, detail: String = "") {
        val line = "${System.currentTimeMillis()} ${stage.take(64)} ${detail.take(1200)}"
        mutable.value = (mutable.value + line).takeLast(100)
        AndroidAgentLogger.info("DoubaoDiag $line")
    }
    @Synchronized fun clear() { mutable.value = emptyList() }
    fun business(stage: String, json: JSONObject, secrets: List<String>) {
        mark(stage, "code=${json.optInt("code", -1)} status=${json.optInt("status", -1)} message=${sanitize(json.optString("message"), secrets)}")
    }
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val operation = request.url.pathSegments.lastOrNull().orEmpty().take(50)
        val requestId = id(request.header("X-Api-Request-Id") ?: request.header("X-Api-Connect-Id"))
        val secrets = listOfNotNull(request.header("X-Api-Key"), request.header("X-Api-Access-Key"), request.header("Authorization"))
        val resource = sanitize(request.header("X-Api-Resource-Id").orEmpty(), secrets)
        val start = System.nanoTime()
        mark("http.begin", "operation=$operation request=$requestId resource=$resource auth=${if (request.header("X-Api-Key") != null) "api-key" else "signed"}")
        try {
            val response = chain.proceed(request)
            mark("http.response", "operation=$operation request=$requestId http=${response.code} elapsedMs=${(System.nanoTime()-start)/1000000} logid=${id(response.header("X-Tt-Logid"))}")
            if (!response.isSuccessful && response.code != 101) {
                runCatching {
                    val json = JSONObject(response.peekBody(16384).string())
                    business("http.rejected", json, secrets)
                }.onFailure { mark("http.rejected", "error_body=non-json-or-unreadable") }
            }
            return response
        } catch (e: Exception) {
            mark("http.failed", "operation=$operation request=$requestId elapsedMs=${(System.nanoTime()-start)/1000000} type=${e.javaClass.simpleName}")
            throw e
        }
    }
}
