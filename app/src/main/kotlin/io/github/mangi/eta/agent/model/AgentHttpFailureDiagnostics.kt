package io.github.mangi.eta.agent.model

import okhttp3.Headers
import org.json.JSONObject

/** Bounded, allowlisted error details; never persist a response body or request headers. */
internal object AgentHttpFailureDiagnostics {
    fun collect(status: Int, body: String, headers: Headers?, secrets: List<String>): String {
        val parts = mutableListOf("http=$status")
        fun add(key: String, value: String?) {
            if (!value.isNullOrBlank() && value != "null") parts += "$key=${safe(value, secrets, 600)}"
        }
        val error = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        add("status", error?.optString("status"))
        add("code", error?.optString("code"))
        add("type", error?.optString("type"))
        add("message", error?.optString("message"))
        val details = error?.optJSONArray("details")
        for (index in 0 until minOf(details?.length() ?: 0, 6)) {
            val detail = details?.optJSONObject(index) ?: continue
            val type = detail.optString("@type").substringAfterLast('/')
            when (type) {
                "google.rpc.ErrorInfo" -> {
                    add("reason", detail.optString("reason"))
                    add("domain", detail.optString("domain"))
                    val metadata = detail.optJSONObject("metadata")
                    for (key in listOf("service", "model", "quota_metric", "quota_limit", "quota_location", "quota_limit_value")) {
                        add(key, metadata?.optString(key))
                    }
                }
                "google.rpc.QuotaFailure" -> {
                    val violations = detail.optJSONArray("violations") ?: continue
                    for (v in 0 until minOf(violations.length(), 3)) {
                        val violation = violations.optJSONObject(v) ?: continue
                        // subject/consumer can identify the account or project; omit them.
                        for (key in listOf("description", "quotaMetric", "quotaId", "quotaValue")) {
                            add("quota.$key", violation.optString(key))
                        }
                    }
                }
                "google.rpc.RetryInfo" -> add("retryDelay", detail.optString("retryDelay"))
                else -> add("detailType", type)
            }
        }
        for (key in listOf("Retry-After", "x-request-id", "request-id", "x-goog-request-id", "x-cloud-trace-context")) {
            add(key, headers?.get(key))
        }
        if (error == null) parts += "bodyFormat=unstructured_or_empty"
        return parts.joinToString("; ").take(4000)
    }

    fun safe(value: String, secrets: List<String> = emptyList(), limit: Int = 800): String {
        var text = value
        secrets.filter { it.isNotBlank() }.sortedByDescending { it.length }.forEach {
            text = text.replace(it, "[redacted]")
        }
        text = text.replace(Regex("(?i)Bearer\\s+[^\\s,;\"<>]+"), "Bearer [redacted]")
            .replace(Regex("(?:ya29\\.[A-Za-z0-9._~-]+|AIza[A-Za-z0-9_-]+|sk-[A-Za-z0-9_-]+)"), "[redacted]")
            .replace(Regex("(?i)(access_token|refresh_token|api[_-]?key|authorization|cookie)\\s*[=:]\\s*[^\\s,;\"<>]+"), "$1=[redacted]")
            .replace(Regex("https?://[^\\s<>\"]+")) { match -> match.value.substringBefore('?').substringBefore('#') }
            .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
        return text.trim().take(limit)
    }
}
