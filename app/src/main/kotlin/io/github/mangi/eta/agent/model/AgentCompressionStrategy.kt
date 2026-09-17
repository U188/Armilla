package io.github.mangi.eta.agent.model

/** Persisted values must remain stable. Unknown values fail closed to full-turn protection. */
internal enum class AgentCompressionStrategy(val wireValue: String) {
    CONTINUE_TASK("continue_task"),
    PRESERVE_TURN("preserve_turn");

    companion object {
        val DEFAULT = CONTINUE_TASK

        fun parse(value: String?): AgentCompressionStrategy {
            if (value.isNullOrBlank()) return DEFAULT
            return entries.firstOrNull { it.wireValue == value } ?: PRESERVE_TURN
        }
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
     * Strict protection continues to honor both recent turns and the active run.
     */
    fun selectStart(
        history: List<AgentModelClient.ConversationMessage>,
        strategy: AgentCompressionStrategy,
        keep: Int,
        contextWindow: Int,
        activeStart: Int = history.size,
        overflow: Boolean = false,
    ): Int = if (strategy == AgentCompressionStrategy.CONTINUE_TASK && contextWindow > 0) {
        continuationStart(history, continuationRetentionBudget(contextWindow, overflow))
    } else {
        protectedStart(history, keep, activeStart)
    }

    /**
     * Keep a bounded working tail for CONTINUE_TASK. The previous 16% rule made
     * a 500k window retain about 80k tokens before the summary, which is too much
     * for a compacted context. Use an internal 4k–12k tail for larger windows, rather than reserving 80k
     * in a 500k window. Small windows retain 10% (at least 1k).
     */
    internal fun continuationRetentionBudget(contextWindow: Int, overflow: Boolean = false): Int {
        if (overflow) return 1
        return when {
            contextWindow <= 0 -> 1
            contextWindow <= 32_000 -> maxOf(1_000, contextWindow / 10)
            else -> minOf(12_000, maxOf(4_000, contextWindow / 16))
        }
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
