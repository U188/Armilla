package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONObject

/** Small telemetry only: never contains prompts, tool bodies or credentials. */
internal data class SubAgentContextStats(
    val taskId: String,
    val worker: Int,
    val role: String,
    val model: String,
    val modelName: String,
    val providerName: String,
    val contextWindow: Int?,
    val contextTokens: Int? = null,
    val projected: Boolean = true,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val isCompacting: Boolean = false,
    val compactionCount: Int = 0,
    val beforeCompactionTokens: Int? = null,
    val afterCompactionTokens: Int? = null,
    val status: String = "running",
) {
    fun toJson(): JSONObject = JSONObject().put("task_id", taskId).put("worker", worker)
        .put("role", role).put("model", model).put("model_name", modelName).put("provider_name", providerName)
        .put("context_window", contextWindow ?: JSONObject.NULL).put("context_tokens", contextTokens ?: JSONObject.NULL)
        .put("context_percent", if (contextTokens != null && contextWindow != null && contextWindow > 0)
            contextTokens.toDouble() / contextWindow * 100 else JSONObject.NULL)
        .put("projected", projected).put("input_tokens", inputTokens).put("output_tokens", outputTokens)
        .put("is_compacting", isCompacting).put("compaction_count", compactionCount)
        .put("before_compaction_tokens", beforeCompactionTokens ?: JSONObject.NULL)
        .put("after_compaction_tokens", afterCompactionTokens ?: JSONObject.NULL).put("status", status)

    companion object {
        fun fromJson(j: JSONObject) = SubAgentContextStats(
            j.getString("task_id"), j.getInt("worker"), j.getString("role"), j.getString("model"),
            j.getString("model_name"), j.getString("provider_name"), j.intOrNull("context_window"),
            j.intOrNull("context_tokens"), j.optBoolean("projected", true), j.optLong("input_tokens"),
            j.optLong("output_tokens"), j.optBoolean("is_compacting"), j.optInt("compaction_count"),
            j.intOrNull("before_compaction_tokens"), j.intOrNull("after_compaction_tokens"), j.optString("status", "running"))
        private fun JSONObject.intOrNull(key: String): Int? = if (isNull(key) || !has(key)) null else getInt(key)
    }
}

internal class SubAgentContextTracker(initial: SubAgentContextStats) {
    var value: SubAgentContextStats = initial
        private set
    private val billedRounds = mutableMapOf<Int, AgentTokenUsage>()
    private var awaitingCompactedUsage = false

    @Synchronized fun accept(event: AgentEvent): SubAgentContextStats? {
        if (value.status != "running") return null
        value = when (event) {
            is AgentEvent.UsageReceived -> {
                if (!event.projected) billedRounds[event.round] = event.usage
                val tokens = event.usage.occupancyTokens()
                value.copy(contextTokens = tokens ?: value.contextTokens, projected = event.projected,
                    inputTokens = billedRounds.values.sumOf { (it.inputTokens ?: 0).toLong() },
                    outputTokens = billedRounds.values.sumOf { (it.outputTokens ?: 0).toLong() },
                    afterCompactionTokens = if (awaitingCompactedUsage) tokens else value.afterCompactionTokens)
                    .also { if (tokens != null) awaitingCompactedUsage = false }
            }
            is AgentEvent.ContextCompactionStarted -> value.copy(isCompacting = true,
                beforeCompactionTokens = if (value.isCompacting) value.beforeCompactionTokens else value.contextTokens,
                afterCompactionTokens = null)
            is AgentEvent.ContextCompacted -> {
                awaitingCompactedUsage = event.applied
                value.copy(isCompacting = false, compactionCount = value.compactionCount + if (event.applied) 1 else 0,
                    contextTokens = if (event.applied) null else value.contextTokens)
            }
            else -> return null
        }
        return value
    }

    @Synchronized fun finish(status: String): SubAgentContextStats {
        value = value.copy(status = status, isCompacting = false)
        return value
    }
}
