package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimePolicy
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 单次 Agent run 的纯编排循环。
 *
 * 一次 assistant 响应及其完整工具批次构成一个 turn。
 * 流式正文中途的 steering 会打断当前模型请求、保留已写出的内容，再注入补充指令开下一轮；
 * 工具批次仍跑完，不取消正在执行的工具。循环不设置本地轮次上限，由模型自然结束、取消或错误终止。
 */
internal class AgentLoop(
    private val config: AgentModelClient.ModelConfig,
    private val messages: JSONArray,
    private val tools: JSONArray,
    private val provider: AgentProviderClient,
    private val toolExecutor: AgentModelClient.ToolExecutor,
    private val runController: AgentRunController,
    private val traceFormatter: AgentTraceFormatter,
    private val onEvent: (AgentEvent) -> Unit,
    private val toolsForRound: (() -> JSONArray)? = null,
    private val modelRetry: AgentModelRetry = AgentModelRetry(),
    private val sessionId: String = java.util.UUID.randomUUID().toString(),
    private val compactPolicy: CompactPolicy = CompactPolicy.Disabled,
    private val systemCount: Int = 0,
    private val compactionArchive: AgentCompactionArchive? = null,
    private val turnId: String = java.util.UUID.randomUUID().toString(),
    private val onHistoryCompacted: () -> Unit = {},
    private val compactHistory: ((
        List<AgentModelClient.ConversationMessage>,
        CompactPolicy,
    ) -> List<AgentModelClient.ConversationMessage>)? = null,
) {
    data class CompactPolicy(
        val enabled: Boolean,
        val contextWindow: Int,
        val keepRecentMessages: Int,
        val targetTokens: Int,
        val compressModelConfig: AgentModelClient.ModelConfig?,
        val strategy: AgentCompressionStrategy = AgentCompressionStrategy.PRESERVE_TURN,
    ) {
        companion object {
            val Disabled = CompactPolicy(
                enabled = false,
                contextWindow = 128_000,
                keepRecentMessages = AgentContextCompactor.DEFAULT_KEEP_RECENT,
                targetTokens = AgentContextCompactor.DEFAULT_TARGET_TOKENS,
                compressModelConfig = null,
            )
        }
    }
    data class Result(
        val content: String,
        val reasoningContent: String,
        val sensitiveToolCallIds: Set<String>,
    )

    private data class ToolOutcome(
        val call: AgentModelClient.ToolCall,
        val result: AgentModelClient.ToolResult,
    )

    private var toolCallValidator = AgentToolCallValidator(tools)
    private val accumulatedReasoning = StringBuilder()
    private val sensitiveToolCallIds = linkedSetOf<String>()
    private var pendingToolImageMessage: JSONObject? = null
    private var lastUsage: AgentTokenUsage? = null
    private var lastUsageMessageCount: Int = 0
    private var suppressThinkingForNextRequest = false
    private var activeTurnStart = (messages.length() - systemCount - 1).coerceAtLeast(0)
    private var compactionFailure = ""
    private var currentRoundTools = tools
    private var manualBudgetAttempt = false
    private var budgetKeepRecent = compactPolicy.keepRecentMessages
    private var budgetStrategy = compactPolicy.strategy
    private var overflowPending = false
    private var overflowRecoveryAttempts = 0
    private var lastFailedCompaction: Pair<String, Int>? = null
    private var skipIneffectiveAutoCompact = false

    fun reasoningSnapshot(): String = accumulatedReasoning.toString().trim()

    fun sensitiveToolCallIdsSnapshot(): Set<String> = sensitiveToolCallIds.toSet()

    fun run(): Result {
        // Only annotate messages created by this run. The current user entry is initially last.
        messages.optJSONObject(messages.length() - 1)?.put(AgentTurnIdentity.JSON_KEY, turnId)
        var round = 1

        while (true) {
            runController.throwIfCancelled()
            appendPendingSteeringMessage()
            currentRoundTools = toolsForRound?.invoke() ?: tools
            maybeCompactBeforeRound(round)
            var reductions = 0
            while (requestOverBudget() || overflowPending) {
                if (reductions++ < 3 && overflowRecoveryAttempts <= 1 && tryBudgetCompaction(round)) {
                    overflowPending = false
                    continue
                }
                runController.pause()
                onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(),
                    blocked = true, reason = compactionFailure.ifBlank {
                        "上下文空间不足；受保护历史未删除。可保持暂停、仅本次允许压缩较早步骤，或停止后选择更大窗口模型。"
                    }))
                runController.throwIfCancelled()
                reductions = 0
                appendPendingSteeringMessage()
                currentRoundTools = toolsForRound?.invoke() ?: tools
                maybeCompactBeforeRound(round)
                if (manualBudgetAttempt) overflowRecoveryAttempts = 0
            }
            emitProjectedPrompt(round)

            manualBudgetAttempt = false
            val roundTools = currentRoundTools
            toolCallValidator = AgentToolCallValidator(roundTools)
            val reasoningLengthBeforeRound = accumulatedReasoning.length
            val completedRound = try {
                modelRetry.complete(
                    initialRound = round,
                    request = ProviderRequest(requestConfigForRound(),
                        AgentRequestMediaPolicy.filter(messages, config.supportsVision, config.supportsVideo),
                        roundTools, sessionId),
                    provider = provider,
                    controller = runController,
                    onEvent = onEvent,
                    onProviderEvent = { attemptRound, providerEvent ->
                        if (providerEvent is ProviderEvent.Usage) {
                            lastUsage = providerEvent.usage
                            lastUsageMessageCount = messages.length()
                        }
                        if (providerEvent is ProviderEvent.BlockDelta &&
                            providerEvent.kind == AssistantBlockKind.THINKING
                        ) {
                            accumulatedReasoning.append(providerEvent.delta)
                        }
                        providerEvent.toAgentEvent(attemptRound)?.let(onEvent)
                    },
                    discardAttemptReasoning = { accumulatedReasoning.setLength(reasoningLengthBeforeRound) },
                )
            } catch (failure: AgentModelFailure) {
                if (failure.code != "CONTEXT_WINDOW_EXCEEDED") throw failure
                overflowPending = true
                overflowRecoveryAttempts++
                compactionFailure = "提供方确认上下文超限。只在缩减成功后有限重试；否则保持暂停，不删除受保护历史。"
                continue
            }
            // Failed/overflowed requests keep their image observation until a successful request.
            discardPendingToolImageMessage()
            overflowRecoveryAttempts = 0
            round = completedRound.round
            val providerResponse = completedRound.response

            runController.throwIfCancelled()
            val assistantMessage = providerResponse.assistantMessage
            val toolCalls = AgentConversationCodec.parseToolCalls(assistantMessage)
            val assistantReasoning = assistantMessage.optString("reasoning_content")
            val content = assistantMessage.optString("content").trim()
            val hasAssistantPayload = (content.isNotBlank() && content != "null") ||
                assistantReasoning.isNotBlank() ||
                toolCalls.isNotEmpty()
            if (
                assistantReasoning.isNotBlank() &&
                accumulatedReasoning.length == reasoningLengthBeforeRound
            ) {
                accumulatedReasoning.append(assistantReasoning)
            }

            val pausedInterrupt = runController.consumePausedInterrupt()
            if (pausedInterrupt) {
                // 暂停打断的是当前模型流，半截正文已在 UI 上，不能写入历史。
                // 否则继续时会多一条助手消息，压缩也会把未完成轮次当成已提交 transcript。
                appendPendingSteeringMessage()
                continue
            }
            if (hasAssistantPayload) {
                messages.put(
                    AgentConversationCodec.assistantHistoryMessage(
                        source = assistantMessage,
                        toolCalls = toolCalls,
                    ).put(AgentTurnIdentity.JSON_KEY, turnId)
                )
                onEvent(
                    AgentEvent.AssistantReceived(
                        round = round,
                        contentChars = assistantMessage.optString("content").length,
                        reasoningContent = assistantReasoning,
                        toolNames = toolCalls.map { it.name },
                    )
                )
            } else if (runController.hasPendingSteering || runController.hasPendingCompact) {
                appendPendingSteeringMessage()
                round += 1
                continue
            }

            if (toolCalls.isNotEmpty()) {
                val finishedContent = assistantMessage.optString("content").trim()
                val finishedNaturally = providerResponse.stopReason != AssistantStopReason.TOOL_USE &&
                    providerResponse.stopReason != AssistantStopReason.OUTPUT_LIMIT &&
                    finishedContent.isNotBlank() &&
                    finishedContent != "null"
                if (finishedNaturally) {
                    onEvent(AgentEvent.RunFinished(round = round, contentChars = finishedContent.length))
                    return Result(
                        content = finishedContent,
                        reasoningContent = reasoningSnapshot(),
                        sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
                    )
                }
                val outcomes = when (providerResponse.stopReason) {
                    AssistantStopReason.TOOL_USE ->
                        toolCalls.map { call -> executeTool(round, call) }
                    AssistantStopReason.OUTPUT_LIMIT ->
                        toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "TRUNCATED_TOOL_CALL",
                                message = "模型输出达到长度上限，工具参数可能不完整；本次调用未执行，请重新提交完整参数。",
                            )
                        }
                    else ->
                        toolCalls.map { call ->
                            rejectedToolOutcome(
                                round = round,
                                toolCall = call,
                                code = "UNEXPECTED_TOOL_CALL",
                                message = "模型在 ${providerResponse.stopReason.name} 终止状态下返回了工具调用；" +
                                    "本批调用未执行，请重新规划。",
                            )
                        }
                }
                appendToolOutcomes(round, outcomes)
                emitProjectedPrompt(round)
                round += 1
                continue
            }

            // 压缩优先于自然结束：打断后仍留在同一 run，下一次请求前压缩。
            if (runController.hasPendingCompact) {
                appendCompactContinueIfNeeded()
                round += 1
                continue
            }

            // 正文回合结束后再注入 steering：已写出的内容留在历史里，下一轮带上补充指令。
            if (appendPendingSteeringOrSeal()) {
                round += 1
                continue
            }

            if (content.isBlank() || content == "null") {
                val finishReason = assistantMessage.optString("finish_reason")
                error("模型接口第 $round 轮返回为空${finishReason.takeIf { it.isNotBlank() }?.let { "：$it" }.orEmpty()}")
            }

            onEvent(AgentEvent.RunFinished(round = round, contentChars = content.length))
            return Result(
                content = content,
                reasoningContent = reasoningSnapshot(),
                sensitiveToolCallIds = sensitiveToolCallIds.toSet(),
            )
        }
    }

    /**
     * 下一次模型请求前压缩。
     * 自动压缩从第二轮开始按阈值判断；手动/强制压缩可在第一轮立刻执行。
     * 工具批次跑完后才会回到这里，因此不会拆掉当前工具循环。
     */
    private fun historyForCompaction() = (systemCount.coerceIn(0, messages.length()) until messages.length())
        .map { AgentConversationCodec.fromJsonObject(messages.getJSONObject(it)) }

    private fun estimatedRequestTokens(): Int {
        val local = AgentContextBudget.estimate(messages) +
            AgentContextBudget.countTokens(currentRoundTools.toString())
        val projected = projectedPromptTokens()
        // 有账单时以接口占用为准。本地启发式会把工具 JSON / 代码按拉丁字符放大，
        // 500k 窗口时往往在真实用量一半就把任务暂停。
        return projected ?: local
    }

    private fun storedHistoryChars(): Long {
        val safeHistory = AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds)
        return safeHistory.sumOf { AgentConversationCodec.toJsonObject(it).toString().length.toLong() }
    }

    private fun persistenceCharLimit(): Long {
        val window = (config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow).toLong()
        val roomBudget = AgentConversationCodec.MAX_CONVERSATION_CHECKPOINT_CHARS.toLong() - 128_000L
        // 按窗口放大：1MB Room 上限大约只相当于 20 万拉丁 token，500k 窗口会在一半就误暂停。
        return maxOf(roomBudget, window * 6)
    }

    private fun requestOverBudget(): Boolean {
        val storedChars = storedHistoryChars()
        val charLimit = persistenceCharLimit()
        if (storedChars > charLimit) {
            compactionFailure = compactionFailure.ifBlank {
                "本轮历史接近本机持久化容量上限；已暂停，未截断受保护原文。可允许压缩较早步骤或停止任务。"
            }
            return true
        }
        val window = config.contextWindow?.takeIf { it > 0 } ?: return false
        return estimatedRequestTokens() > AgentCompressionBoundary.inputLimit(window, AgentCompressionBoundary.outputReserve(config))
    }

    private fun maybeCompactBeforeRound(round: Int) {
        val override = runController.takePendingCompact()
        val forced = override != null
        if (forced) { manualBudgetAttempt = true; lastFailedCompaction = null }
        if (!forced && (!compactPolicy.enabled || overflowPending)) return
        val window = config.contextWindow?.takeIf { it > 0 } ?: compactPolicy.contextWindow
        val charPressure = storedHistoryChars() > persistenceCharLimit() * 7 / 10
        if (!forced && skipIneffectiveAutoCompact && !charPressure && !requestOverBudget()) return
        if (!forced && !charPressure && (round <= 1 || estimatedRequestTokens() < window * 0.9)) return
        val strategy = override?.strategy ?: compactPolicy.strategy
        val keep = AgentContextCompactor.coerceKeepRecent(
            override?.keepRecentMessages ?: compactPolicy.keepRecentMessages,
            strategy,
        )
        budgetKeepRecent = keep
        budgetStrategy = strategy
        val history = historyForCompaction()
        val cut = runCatching { AgentCompressionBoundary.protectedStart(history, keep, activeTurnStart) }.getOrDefault(0)
        if (cut <= 0) {
            if (forced) onEvent(AgentEvent.ContextCompacted(round, false, messages.length(), messages.length(),
                reason = "当前历史均在保护范围内，没有可压缩的旧历史。"))
            return
        }
        val reduced = applyCompaction(round, history, cut, override?.targetTokens ?: compactPolicy.targetTokens)
        if (reduced) {
            overflowPending = false
            skipIneffectiveAutoCompact = false
        } else if (!forced) {
            skipIneffectiveAutoCompact = true
        }
    }

    private fun tryBudgetCompaction(round: Int): Boolean {
        if (!compactPolicy.enabled && !manualBudgetAttempt && !runController.allowCurrentTurnCompaction) return false
        val history = historyForCompaction()
        val normalCut = runCatching { AgentCompressionBoundary.protectedStart(history, budgetKeepRecent, activeTurnStart) }.getOrDefault(0)
        if (normalCut > 0 && pruneOversizedToolResults(round, systemCount + normalCut)) return true
        if (normalCut > 0 && applyCompaction(round, history, normalCut, compactPolicy.targetTokens)) return true
        val mayRelax = budgetStrategy == AgentCompressionStrategy.CONTINUE_TASK || runController.allowCurrentTurnCompaction
        if (!mayRelax) return false
        if (compactionArchive == null) {
            compactionFailure = "未提供原文存档与回读能力，不能压缩受保护轮次。"
            return false
        }
        if (pruneOversizedToolResults(round)) return true
        val cut = runCatching { AgentCompressionBoundary.continuationStart(history,
            if (overflowPending) 1 else maxOf(1024, (config.contextWindow ?: 0) / 6)) }.getOrDefault(0)
        if (cut <= 0) return false
        return applyCompaction(round, history, cut, compactPolicy.targetTokens)
    }

    /** Lossy pruning is permitted only after the caller has opted into continuation. */
    private fun pruneOversizedToolResults(round: Int, endExclusive: Int = messages.length()): Boolean {
        val archive = compactionArchive ?: return false
        val replacements = mutableListOf<Pair<Int, JSONObject>>()
        val checkpoints = mutableListOf<String>()
        try {
            for (index in systemCount until endExclusive) {
                val original = messages.getJSONObject(index)
                if (original.optString("role") != "tool" || original.optString("tool_call_id") in sensitiveToolCallIds) continue
                val text = original.opt("content") as? String ?: continue
                if (text.codePointCount(0, text.length) <= 8192 || text.contains("[Eta tool output pruned;")) continue
                val id = archive.save(listOf(AgentConversationCodec.fromJsonObject(original)))
                archive.record(id, "started")
                val head = text.offsetByCodePoints(0, 4096)
                val tail = text.offsetByCodePoints(text.length, -1024)
                val shorter = text.substring(0, head) + "\n[Eta tool output pruned; original: context-checkpoint:$id; read_compacted_history]\n" + text.substring(tail)
                val copy = JSONObject(original.toString()).put("content", shorter)
                if (AgentContextBudget.countTokens(shorter) >= AgentContextBudget.countTokens(text)) continue
                archive.record(id, "ready")
                replacements += index to copy
                checkpoints += id
            }
        } catch (failure: Exception) {
            compactionFailure = failure.message ?: "工具原文存档失败，未应用修剪"
            return false
        }
        if (replacements.isEmpty()) return false
        runController.throwIfCancelled()
        replacements.forEach { (index, message) -> messages.put(index, message) }
        lastUsage = null
        lastUsageMessageCount = 0
        onHistoryCompacted()
        onEvent(AgentEvent.ContextCompacted(round, true, messages.length(), messages.length(),
            history = AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds),
            compressorLabel = "工具输出预算修剪（原文可回读）"))
        checkpoints.forEach { runCatching { archive.record(it, "committed") } }
        return true
    }

    private fun applyCompaction(round: Int, history: List<AgentModelClient.ConversationMessage>, cut: Int, target: Int): Boolean {
        val compressConfig = compactPolicy.compressModelConfig?.let { model ->
            val window = model.contextWindow?.takeIf { it > 0 }
                ?: config.contextWindow?.takeIf { it > 0 }
                ?: compactPolicy.contextWindow.takeIf { it > 0 }
            if (window == null) model else model.copy(contextWindow = window)
        } ?: return false
        val original = messages.toString()
        val originalCount = messages.length()
        if (lastFailedCompaction == (original to cut)) return false
        onEvent(AgentEvent.ContextCompactionStarted(round))
        var savedCheckpoint: String? = null
        val rewritten = try {
            val prefix = history.take(cut)
            require(prefix.none { message ->
                message.toolCallId in sensitiveToolCallIds || sensitiveToolCallIds.any { id -> message.toolCallsJson.contains(id) }
            }) { "待压缩范围包含不允许持久化的工具内容；已保留原文，请先完成或结束本轮。" }
            savedCheckpoint = compactionArchive?.save(prefix)
            savedCheckpoint?.let { compactionArchive?.record(it, "started") }
            val compressed = if (compactHistory != null) {
                compactHistory.invoke(history, compactPolicy.copy(keepRecentMessages = budgetKeepRecent, targetTokens = target.coerceIn(500, 4000)))
            } else AgentContextCompactor.compress(
                history, AgentContextCompactor.Config(target.coerceIn(500, 4000), compactPolicy.keepRecentMessages, compressConfig),
                keepStartOverride = cut, controller = runController,
                replay = if (compressConfig.providerType == config.providerType && compressConfig.baseUrl == config.baseUrl &&
                    compressConfig.model == config.model && compressConfig.openAiEndpointMode == config.openAiEndpointMode)
                    AgentContextCompactor.ReplayContext(
                        JSONArray().also { a -> for (i in 0 until systemCount) a.put(messages.getJSONObject(i)) },
                        JSONArray().also { a -> for (i in systemCount until systemCount + cut) a.put(messages.getJSONObject(i)) },
                        currentRoundTools, sessionId,
                    ) else null,
            )
            val tail = history.drop(cut)
            require(compressed.size >= tail.size && compressed.takeLast(tail.size) == tail) { "摘要后受保护尾部发生变化" }
            require(compressed != history) { "没有可压缩历史" }
            runController.throwIfCancelled()
            require(messages.toString() == original) { "摘要生成期间上下文已变化，未应用摘要" }
            val withPointers = savedCheckpoint?.let {
                requireNotNull(compactionArchive).attachReferences(prefix, it, compressed, tail.size)
            } ?: compressed
            require(withPointers.sumOf { AgentContextBudget.countMessage(it).toLong() } < history.sumOf { AgentContextBudget.countMessage(it).toLong() }) {
                "摘要及索引未减少上下文，已保留原文"
            }
            savedCheckpoint?.let { compactionArchive?.record(it, "ready") }
            withPointers
        } catch (failure: Exception) {
            savedCheckpoint?.let { runCatching { compactionArchive?.record(it, "failed") } }
            if (runController.isCancelled || failure is io.github.mangi.eta.agent.runtime.AgentRunCancelledException) throw failure
            lastFailedCompaction = original to cut
            compactionFailure = failure.message ?: "压缩失败，原文保留"
            onEvent(AgentEvent.ContextCompacted(round, false, originalCount, originalCount, reason = compactionFailure))
            return false
        }
        val removed = history.size - rewritten.size
        activeTurnStart = if (cut <= activeTurnStart) (activeTurnStart - removed).coerceAtLeast(0) else 0
        // Never reserialize the kept live tail through the persistence DTO. That would strip
        // provider-only response items, images, and long tool bodies even in protected mode.
        val keptJson = (systemCount + cut until messages.length()).map { messages.getJSONObject(it) }
        val prefixJson = (0 until systemCount).map { messages.getJSONObject(it) }
        val newPrefix = rewritten.dropLast(history.size - cut)
        while (messages.length() > 0) messages.remove(messages.length() - 1)
        prefixJson.forEach(messages::put)
        newPrefix.forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        keptJson.forEach(messages::put)
        lastUsage = null
        lastUsageMessageCount = 0
        compactionFailure = ""
        onHistoryCompacted()
        onEvent(AgentEvent.ContextCompacted(round, true, originalCount, messages.length(),
            history = AgentConversationCodec.transcript(messages, systemCount, sensitiveToolCallIds),
            compressorLabel = compressorLabel(compressConfig)))
        savedCheckpoint?.let { runCatching { compactionArchive?.record(it, "committed") } }
        return true
    }

    private fun requestConfigForRound(): AgentModelClient.ModelConfig {
        if (!suppressThinkingForNextRequest) return config
        suppressThinkingForNextRequest = false
        return AgentRuntimePolicy.withoutOptionalThinking(config)
    }

    private fun compressorLabel(config: AgentModelClient.ModelConfig): String {
        val provider = config.providerName.trim()
        val model = config.modelDisplayName.trim().ifBlank { config.model.trim() }
        return when {
            provider.isNotBlank() && model.isNotBlank() -> "$provider · $model"
            model.isNotBlank() -> model
            else -> provider
        }
    }

    private fun appendPendingSteeringMessage(): Boolean {
        val supplement = runController.pollSteeringInput() ?: return false
        messages.put(AgentSupplementMedia.userMessage(steeringPrompt(supplement.text), supplement.imagesJson).put(AgentTurnIdentity.JSON_KEY, turnId))
        return true
    }

    private fun appendPendingSteeringOrSeal(): Boolean {
        val supplement = runController.pollSteeringInputOrSeal() ?: return false
        messages.put(AgentSupplementMedia.userMessage(steeringPrompt(supplement.text), supplement.imagesJson).put(AgentTurnIdentity.JSON_KEY, turnId))
        return true
    }

    private fun steeringPrompt(supplement: String): String =
        AgentContextCompactor.steeringUserContent(supplement)

    private fun appendCompactContinueIfNeeded() {
        val last = messages.optJSONObject(messages.length() - 1) ?: return
        if (!last.optString("role").equals("assistant", ignoreCase = true)) return
        messages.put(
            AgentConversationCodec.userTextMessage(
                AgentContextCompactor.steeringUserContent(
                    "请从上次中断的地方继续，不要重复已经写过的内容，也不要从头开始。",
                ),
            ).put(AgentTurnIdentity.JSON_KEY, turnId),
        )
        suppressThinkingForNextRequest = true
    }

    private fun executeTool(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
    ): ToolOutcome {
        runController.throwIfCancelled()
        toolCallValidator.validate(toolCall)?.let { validationError ->
            return rejectedToolOutcome(
                round = round,
                toolCall = toolCall,
                code = "INVALID_TOOL_ARGUMENTS",
                message = validationError,
            )
        }
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )

        val result = try {
            if (toolCall.name == AgentCompactionArchive.TOOL && compactionArchive != null) {
                compactionArchive.read(toolCall.argumentsJson)
            } else toolExecutor.execute(toolCall)
        } catch (throwable: Exception) {
            runController.throwIfCancelled()
            AgentModelClient.ToolResult(
                content = JSONObject()
                    .put("ok", false)
                    .put("code", "TOOL_ERROR")
                    .put("message", throwable.message ?: throwable.javaClass.simpleName)
                    .toString(),
            )
        }
        if (result.sensitive || AgentSensitiveToolPolicy.isSensitive(toolCall.name)) {
            sensitiveToolCallIds += toolCall.id
        }

        runController.throwIfCancelled()
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun rejectedToolOutcome(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        code: String,
        message: String,
    ): ToolOutcome {
        onEvent(
            AgentEvent.ToolStarted(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                argsPreview = traceFormatter.summarizeArguments(toolCall),
                command = traceFormatter.displayCommand(toolCall),
            )
        )
        val result = AgentModelClient.ToolResult(
            content = JSONObject()
                .put("ok", false)
                .put("code", code)
                .put("message", message)
                .toString(),
            sensitive = AgentSensitiveToolPolicy.isSensitive(toolCall.name),
        )
        if (result.sensitive) sensitiveToolCallIds += toolCall.id
        emitToolFinished(round, toolCall, result)
        return ToolOutcome(toolCall, result)
    }

    private fun emitToolFinished(
        round: Int,
        toolCall: AgentModelClient.ToolCall,
        result: AgentModelClient.ToolResult,
    ) {
        onEvent(
            AgentEvent.ToolFinished(
                round = round,
                toolCallId = toolCall.id,
                name = toolCall.name,
                resultSummary = traceFormatter.summarizeResult(toolCall.name, result),
                imageCount = result.images.size,
                imageBytes = result.images.sumOf { it.bytes },
                success = traceFormatter.isSuccessResult(result),
            )
        )
    }

    private fun projectedPromptTokens(): Int? {
        val billedInput = lastUsage?.occupancyTokens() ?: return null
        if (lastUsageMessageCount <= 0) return billedInput
        var added = 0
        for (index in lastUsageMessageCount until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            added += AgentContextBudget.countMessage(AgentConversationCodec.fromJsonObject(message))
        }
        return billedInput + added
    }

    private fun emitProjectedPrompt(round: Int) {
        val projected = projectedPromptTokens() ?: return
        if (projected <= 0) return
        onEvent(
            AgentEvent.UsageReceived(
                round = round,
                usage = AgentTokenUsage(inputTokens = projected),
                projected = true,
            ),
        )
    }

    private fun appendToolOutcomes(
        round: Int,
        outcomes: List<ToolOutcome>,
    ) {
        // Provider 要求同一 assistant 批次的全部 tool result 连续出现；图片观察统一放在批次之后。
        outcomes.forEach { outcome ->
            messages.put(AgentConversationCodec.toolResultMessage(outcome.call, outcome.result).put(AgentTurnIdentity.JSON_KEY, turnId))
        }

        val imageOutcomes = outcomes.filter { outcome -> outcome.result.images.isNotEmpty() }
        if (imageOutcomes.isEmpty()) return

        // 工具截图是瞬时观察，不是会话资产。下一次推理消费后立即删除。
        discardPendingToolImageMessage()
        val images = imageOutcomes.flatMap { outcome -> outcome.result.images }
        val toolNames = imageOutcomes
            .map { outcome -> outcome.call.name }
            .distinct()
            .joinToString(", ")
        pendingToolImageMessage = AgentConversationCodec.userMessage(
            text = "Latest observation image(s) returned by tool(s): $toolNames.",
            images = images,
        ).put(AgentTurnIdentity.JSON_KEY, turnId).also(messages::put)

        imageOutcomes.forEach { outcome ->
            onEvent(
                AgentEvent.ToolImagesAttached(
                    round = round,
                    toolName = outcome.call.name,
                    imageCount = outcome.result.images.size,
                    imageBytes = outcome.result.images.sumOf { it.bytes },
                )
            )
        }
    }

    private fun discardPendingToolImageMessage() {
        val pending = pendingToolImageMessage ?: return
        pendingToolImageMessage = null
        for (index in messages.length() - 1 downTo 0) {
            if (messages.optJSONObject(index) === pending) {
                messages.remove(index)
                return
            }
        }
    }

    private fun ProviderEvent.toAgentEvent(round: Int): AgentEvent? =
        when (this) {
            ProviderEvent.RequestStarted -> AgentEvent.ProviderRequestStarted(round)
            is ProviderEvent.ResponseHeaders -> AgentEvent.ProviderResponseStarted(round, httpCode)
            is ProviderEvent.BlockStart -> AgentEvent.AssistantBlockStart(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
            )
            is ProviderEvent.BlockDelta -> AgentEvent.AssistantBlockDelta(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                deltaChars = delta.length,
                delta = delta,
            )
            is ProviderEvent.BlockEnd -> AgentEvent.AssistantBlockEnd(
                round = round,
                kind = kind.toRuntimeKind(),
                index = index,
                blockId = blockId,
                name = name,
                contentChars = content.length,
                replacementContent = content.takeIf { replaceContent },
            )
            is ProviderEvent.Usage -> AgentEvent.UsageReceived(round = round, usage = usage)
            is ProviderEvent.HostedToolStarted -> AgentEvent.HostedToolStarted(
                round = round,
                toolCallId = id,
                name = name,
            )
            is ProviderEvent.HostedToolFinished -> AgentEvent.HostedToolFinished(
                round = round,
                toolCallId = id,
                name = name,
                success = success,
            )
            is ProviderEvent.Completed -> null
        }

    private fun AssistantBlockKind.toRuntimeKind(): AgentEvent.AssistantBlockKind =
        when (this) {
            AssistantBlockKind.TEXT -> AgentEvent.AssistantBlockKind.TEXT
            AssistantBlockKind.THINKING -> AgentEvent.AssistantBlockKind.THINKING
            AssistantBlockKind.TOOL_CALL -> AgentEvent.AssistantBlockKind.TOOL_CALL
        }

}
