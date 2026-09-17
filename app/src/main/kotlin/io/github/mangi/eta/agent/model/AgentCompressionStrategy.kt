package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.oauth.GoogleAntigravityOAuth
import io.github.mangi.eta.agent.model.oauth.OpenAiCodexOAuth
import io.github.mangi.eta.data.model.OpenAiEndpointMode

/** Persisted values must remain stable. Unknown values map to continue-task. */
internal enum class AgentCompressionStrategy(val wireValue: String) {
    CONTINUE_TASK("continue_task");

    companion object {
        val DEFAULT = CONTINUE_TASK

        fun parse(@Suppress("UNUSED_PARAMETER") value: String?): AgentCompressionStrategy = DEFAULT
    }
}

/** Compression-only Chat Completions / Responses override. Independent of the session provider. */
internal object AgentCompressionEndpoint {
    fun parse(value: String?): String =
        if (value == OpenAiEndpointMode.RESPONSES) OpenAiEndpointMode.RESPONSES
        else OpenAiEndpointMode.CHAT_COMPLETIONS

    fun canOverride(config: AgentModelClient.ModelConfig): Boolean {
        if (OpenAiCodexOAuth.isCodexEndpoint(config.baseUrl)) return false
        if (GoogleAntigravityOAuth.isAntigravityEndpoint(config.baseUrl)) return false
        return config.openAiEndpointMode == OpenAiEndpointMode.CHAT_COMPLETIONS ||
            config.openAiEndpointMode == OpenAiEndpointMode.RESPONSES
    }

    fun apply(config: AgentModelClient.ModelConfig, endpointMode: String?): AgentModelClient.ModelConfig {
        if (!canOverride(config)) return config
        val resolved = parse(endpointMode)
        return if (config.openAiEndpointMode == resolved) config
        else config.copy(openAiEndpointMode = resolved)
    }
}

internal object AgentCompressionBoundary {
    /** Every cut is between complete tool batches; malformed/orphaned results are not compactable. */
    fun balancedCuts(history: List<AgentModelClient.ConversationMessage>): List<Int> {
        val cuts = collectCuts(history, strict = true)
        require(cuts.lastOrNull() == history.size) { "工具批次尚未完成" }
        return cuts
    }

    /** Complete-batch cut points even if the newest tool batch is still running. */
    fun availableCuts(history: List<AgentModelClient.ConversationMessage>): List<Int> =
        collectCuts(history, strict = false)

    private fun collectCuts(
        history: List<AgentModelClient.ConversationMessage>,
        strict: Boolean,
    ): List<Int> {
        val pending = mutableSetOf<String>()
        val cuts = mutableListOf(0)
        history.forEachIndexed { index, message ->
            if (message.toolCallsJson.isNotBlank()) {
                val calls = runCatching { org.json.JSONArray(message.toolCallsJson) }.getOrNull()
                if (calls == null) {
                    if (strict) require(false) { "工具调用 ID 缺失或重复" }
                } else {
                    for (i in 0 until calls.length()) {
                        val id = calls.optJSONObject(i)?.optString("id").orEmpty()
                        if (strict) {
                            require(id.isNotBlank() && pending.add(id)) { "工具调用 ID 缺失或重复" }
                        } else if (id.isNotBlank()) {
                            pending.add(id)
                        }
                    }
                }
            }
            if (message.role == "tool") {
                val id = message.toolCallId
                if (strict) {
                    require(id.isNotBlank() && pending.remove(id)) { "工具结果缺少对应调用" }
                } else if (id.isNotBlank()) {
                    pending.remove(id)
                } else {
                    pending.lastOrNull()?.let(pending::remove)
                }
            }
            if (pending.isEmpty()) cuts += index + 1
        }
        return cuts
    }

    fun protectedStart(history: List<AgentModelClient.ConversationMessage>, keep: Int, activeStart: Int): Int {
        val requested = minOf(AgentContextCompactor.recentKeepStartIndex(history, keep), activeStart.coerceIn(0, history.size))
        return availableCuts(history).lastOrNull { it <= requested && it < history.size } ?: 0
    }

    /** Shared selection for idle/manual, pre-request pressure and overflow recovery.
     * Continuation retains a token-priced tail, not the entire latest user turn.
     */
    @Suppress("UNUSED_PARAMETER")
    fun selectStart(
        history: List<AgentModelClient.ConversationMessage>,
        strategy: AgentCompressionStrategy,
        keep: Int,
        contextWindow: Int,
        activeStart: Int = history.size,
        overflow: Boolean = false,
    ): Int = if (contextWindow > 0) {
        continuationStart(history, continuationRetentionBudget(contextWindow, overflow))
    } else {
        protectedStart(history, keep, activeStart)
    }

    /**
     * Keep a priced recent tail for CONTINUE_TASK, matching DeepSeek harness
     * retainRatio=0.16. Overflow recovery may shrink this to a single token so
     * the newest complete tool batch can still be selected.
     */
    internal fun continuationRetentionBudget(contextWindow: Int, overflow: Boolean = false): Int {
        if (overflow) return 1
        if (contextWindow <= 0) return 1
        return maxOf(1, (contextWindow.toLong() * 16 / 100).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    /** Never summarize the newest complete unit; walk backward to the next balanced cut. */
    fun continuationStart(history: List<AgentModelClient.ConversationMessage>, retainTokens: Int): Int {
        if (history.size < 2) return 0
        var tokens = 0L
        var start = history.lastIndex
        for (i in history.indices.reversed()) {
            tokens += AgentContextBudget.countMessage(history[i])
            start = i
            if (tokens >= retainTokens.coerceAtLeast(1)) break
        }
        return availableCuts(history).lastOrNull { it <= start && it < history.size } ?: 0
    }

    fun outputReserve(config: AgentModelClient.ModelConfig): Int {
        val body = org.json.JSONObject(config.extraBodyJson.ifBlank { "{}" })
        RequestBodyMerge.mergeCustomBody(body, config.customBody)
        return listOf("max_tokens", "max_completion_tokens", "max_output_tokens")
            .mapNotNull { key -> body.optLong(key, -1).takeIf { it > 0 } }
            .maxOrNull()?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 4096
    }

    fun inputLimit(window: Int, outputReserve: Int = 4096): Int =
        (window.toLong() - outputReserve - maxOf(512, window / 20)).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
}
