package io.github.mangi.eta.agent.model

import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * 压缩模型试出来的思考档。进程内先记一份，并写入本地配置，避免每次压缩都从 off/minimal 重试。
 */
internal object CompressionReasoningStore {
    const val PREFS_KEY = "agent_compress_reasoning_effort_json"

    private val memory = ConcurrentHashMap<String, ReasoningEffort>()

    fun effortFor(config: AgentModelClient.ModelConfig): ReasoningEffort? {
        val key = key(config)
        memory[key]?.let { return it }
        val stored = JSONObject(Prefs.getString(PREFS_KEY, "{}")).optString(key)
        val effort = ReasoningEffort.fromWireValue(stored)
        if (effort != null) memory[key] = effort
        return effort
    }

    fun remember(config: AgentModelClient.ModelConfig, effort: ReasoningEffort) {
        val key = key(config)
        memory[key] = effort
        val json = JSONObject(Prefs.getString(PREFS_KEY, "{}"))
        json.put(key, effort.wireValue)
        Prefs.putString(PREFS_KEY, json.toString())
    }

    fun clearForTests() {
        memory.clear()
    }

    internal fun key(config: AgentModelClient.ModelConfig): String {
        val identity = config.providerId.ifBlank { config.baseUrl.trim() }
        return "$identity|${config.model.trim()}"
    }
}
