package io.github.mangi.eta.agent.model

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Durable protocol data, not UI summaries. Never duplicate tool arguments/results here. */
internal object ResponsesReasoningState {
    const val KEY = "_eta_responses_reasoning"

    private fun scope(config: AgentModelClient.ModelConfig): String =
        MessageDigest.getInstance("SHA-256")
            .digest("${config.providerId}\n${config.baseUrl.trimEnd('/')}\n${config.model}".toByteArray())
            .joinToString("") { "%02x".format(it) }

    fun capture(message: JSONObject, config: AgentModelClient.ModelConfig) {
        val output = ResponsesEphemeralState.outputItems(message)
            ?: message.optJSONObject(KEY)?.optJSONArray("items") ?: return
        val items = JSONArray()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            if (item.optString("type") == "reasoning") items.put(JSONObject(item.toString()))
        }
        if (items.length() > 0) message.put(KEY, JSONObject().put("scope", scope(config)).put("items", items))
    }

    fun copy(source: JSONObject, target: JSONObject) {
        source.optJSONObject(KEY)?.let { target.put(KEY, JSONObject(it.toString())) }
    }

    fun items(message: JSONObject, config: AgentModelClient.ModelConfig): JSONArray? {
        val state = message.optJSONObject(KEY) ?: return null
        if (state.optString("scope") != scope(config)) return null
        return state.optJSONArray("items")?.takeIf { it.length() > 0 }
    }

    fun sanitize(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            val state = JSONObject(raw)
            require(state.getString("scope").matches(Regex("[0-9a-f]{64}")))
            val items = state.getJSONArray("items")
            require(items.length() > 0)
            for (i in 0 until items.length()) require(items.getJSONObject(i).getString("type") == "reasoning")
            JSONObject().put("scope", state.getString("scope")).put("items", items).toString()
        }.getOrDefault("")
    }
}
