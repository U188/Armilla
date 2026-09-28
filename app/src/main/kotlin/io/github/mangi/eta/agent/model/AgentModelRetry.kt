package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController

/** 重试只包围模型请求；完整响应返回前不提交历史或执行本地工具。 */
internal class AgentModelRetry(
    private val waitBeforeRetry: (AgentRunController, Long) -> Unit = { controller, delay ->
        controller.awaitRetryDelay(delay)
    },
) {
    data class Result(val round: Int, val response: ProviderResponse)

    fun complete(
        initialRound: Int,
        request: ProviderRequest,
        provider: AgentProviderClient,
        controller: AgentRunController,
        onEvent: (AgentEvent) -> Unit,
        onProviderEvent: (Int, ProviderEvent) -> Unit,
        discardAttemptReasoning: () -> Unit,
    ): Result {
        var round = initialRound
        var retries = 0
        var envelopeRetries = 0
        var attemptRequest = request
        while (true) {
            controller.throwIfCancelled()
            onEvent(AgentEvent.RoundStarted(round, request.messages.length()))
            var toolDeliveryPossible = false
            var callbackFailure: Exception? = null
            var sawCompleted = false
            var sawVisibleText = false
            // 每次 provider.complete 尝试独占一个投递闸门。SSE 的 finish() 先 countDown 再 cancel，
            // 收集线程可能在读线程仍处于回调中时就从 complete 返回；闸门保证被取代的旧尝试的迟到
            // 回调不再进入 onProviderEvent，避免覆盖后续请求记录的 usage。
            val deliveryGate = ProviderEventDeliveryGate()
            try {
                val response = try {
                    provider.complete(attemptRequest, controller) { event ->
                        deliveryGate.deliver {
                            // Mark before calling consumers: a throwing callback may already have
                            // delivered a tool. Starts, deltas, and orphan ends all forbid replay.
                            if (when (event) {
                                    is ProviderEvent.HostedToolStarted, is ProviderEvent.HostedToolFinished -> true
                                    is ProviderEvent.BlockStart -> event.kind == AssistantBlockKind.TOOL_CALL
                                    is ProviderEvent.BlockDelta -> event.kind == AssistantBlockKind.TOOL_CALL
                                    is ProviderEvent.BlockEnd -> event.kind == AssistantBlockKind.TOOL_CALL
                                    else -> false
                                }
                            ) toolDeliveryPossible = true
                            if (event is ProviderEvent.Completed) sawCompleted = true
                            if (
                                (event is ProviderEvent.BlockDelta &&
                                    event.kind == AssistantBlockKind.TEXT && event.delta.isNotBlank()) ||
                                (event is ProviderEvent.BlockEnd &&
                                    event.kind == AssistantBlockKind.TEXT && event.content.isNotBlank())
                            ) {
                                sawVisibleText = true
                            }
                            try {
                                onProviderEvent(round, event)
                            } catch (failure: Exception) {
                                callbackFailure = failure
                                throw failure
                            }
                        }
                    }
                } finally {
                    // 封闸并等到在途回调结束：只等回调本身，不等网络线程收尾，也不改动 SSE 取消时机。
                    deliveryGate.close()
                }
                callbackFailure?.let { throw it }
                if (
                    !sawVisibleText &&
                    !toolDeliveryPossible &&
                    !controller.hasPendingSteering &&
                    !controller.hasPausedInterrupt &&
                    isEmptyNaturalResponse(response)
                ) {
                    // finish_reason=stop 却没有正文/推理/工具调用：HTTP 成功但内容为空，
                    // provider 不会抛异常，故在此主动当作可重试失败，走同一套退避预算。
                    // 已流式吐出可见正文或启动 hosted 工具时不重试，避免重放副作用或覆盖已展示内容。
                    // pending steering / paused interrupt 下的空回合是设计内的合法空回合（由 Loop
                    // 继续同一 run 处理），不属于坏空回复，绝不在此重试。
                    if (retries == MAX_RETRIES) return Result(round, response)
                    retries += 1
                    val delayMs = BASE_DELAY_MS shl (retries - 1)
                    val finishReason = response.assistantMessage.optString("finish_reason").ifBlank { "stop" }
                    val reasonDetail = "模型返回为空（finish_reason=$finishReason）"
                    runCatching {
                        io.github.mangi.eta.core.AndroidAgentLogger.warn(
                            "Model empty response: provider=${AgentHttpFailureDiagnostics.safe(provider.id, limit = 80)}, " +
                                "model=${AgentHttpFailureDiagnostics.safe(request.config.model, limit = 120)}, " +
                                "round=$round, attempt=$retries, finish_reason=$finishReason",
                        )
                    }
                    onEvent(AgentEvent.ModelRetryScheduled(round, retries, MAX_RETRIES, delayMs.toInt(), "EMPTY_RESPONSE", reasonDetail))
                    waitBeforeRetry(controller, delayMs)
                    controller.throwIfCancelled()
                    discardAttemptReasoning()
                    round += 1
                    continue
                }
                return Result(round, response)
            } catch (failure: Exception) {
                callbackFailure?.let { throw it }
                controller.throwIfCancelled()
                // 还没吐出可见正文就被 steering/暂停打断：当作空助手回合，Loop 继续同一 run。
                // 已有可见正文时必须由 Provider 带回部分内容，这里不能用空消息盖掉。
                if (
                    (controller.hasPendingImmediateSteering || controller.hasPausedInterrupt) &&
                    !sawVisibleText &&
                    !toolDeliveryPossible &&
                    !sawCompleted &&
                    failure !is AgentModelFailure
                ) {
                    return Result(
                        round,
                        ProviderResponse(
                            org.json.JSONObject()
                                .put("role", "assistant")
                                .put("content", "")
                                .put("finish_reason", "stop"),
                        ),
                    )
                }
                if (Thread.currentThread().isInterrupted) throw failure
                val classified = AgentModelFailure.transport(failure) ?: throw failure
                val reasonDetail = AgentHttpFailureDiagnostics.safe(classified.message.orEmpty(), listOf(request.config.apiKey), 600)
                // Log the first failure, including terminal/non-retryable responses, before scheduling retries.
                if (classified.diagnostic.isNotBlank()) runCatching {
                    io.github.mangi.eta.core.AndroidAgentLogger.warn(
                        "Model HTTP failure: provider=${AgentHttpFailureDiagnostics.safe(provider.id, limit = 80)}, " +
                            "model=${AgentHttpFailureDiagnostics.safe(request.config.model, limit = 120)}, " +
                            "round=$round, attempt=${retries + 1}, " +
                            AgentHttpFailureDiagnostics.safe(classified.diagnostic, listOf(request.config.apiKey), 4000),
                    )
                }
                val envelopeRejected = classified.code == ResponsesToolEnvelopeRecovery.CODE
                val correctionAllowed = envelopeRejected && classified.envelopeCorrectionAllowed &&
                    provider.capabilities.endpoint == EndpointKind.RESPONSES
                if (toolDeliveryPossible || sawCompleted || sawVisibleText ||
                    (envelopeRejected && !correctionAllowed) ||
                    (!envelopeRejected && !classified.retryable)
                ) {
                    throw classified
                }
                if (envelopeRetries >= ResponsesToolEnvelopeRecovery.MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "工具封装 JSON 校验连续失败，已纠错重试 ${ResponsesToolEnvelopeRecovery.MAX_RETRIES} 次，停止自动重试；未执行被拒绝的工具调用。",
                        classified,
                        diagnostic = classified.diagnostic,
                    )
                }
                if (retries >= MAX_RETRIES) {
                    throw AgentModelFailure(
                        classified.code, false,
                        "${classified.message} 已重试 $MAX_RETRIES 次仍未恢复，已保留此前完成的工具结果。",
                        classified,
                        diagnostic = classified.diagnostic,
                    )
                }
                if (envelopeRejected) {
                    // Always start from the original history: one hint, never accumulated
                    // rejected generations, fabricated tool results, or guessed JSON fixes.
                    attemptRequest = ResponsesToolEnvelopeRecovery.corrected(request)
                }
                if (envelopeRejected || envelopeRetries > 0) envelopeRetries += 1
                retries += 1
                val delayMs = BASE_DELAY_MS shl (retries - 1)
                onEvent(AgentEvent.ModelRetryScheduled(round, if (envelopeRetries > 0) envelopeRetries else retries, if (envelopeRetries > 0) ResponsesToolEnvelopeRecovery.MAX_RETRIES else MAX_RETRIES, delayMs.toInt(), classified.code, reasonDetail))
                waitBeforeRetry(controller, delayMs)
                controller.throwIfCancelled()
                // 展示保留失败尝试，模型上下文与最终推理摘要只接纳成功尝试。
                discardAttemptReasoning()
                round += 1
            }
        }
    }

    /**
     * 是否为 finish_reason=stop/end_turn 但正文、推理、工具调用全空的“成功但空”响应。
     * 只覆盖自然终止（END_TURN），不动 TOOL_USE / OUTPUT_LIMIT / INTERRUPTED / CONTENT_FILTER，
     * 以免把正常的工具轮或截断轮误判为空。判定口径与 AgentLoop 的空回复检查保持一致。
     */
    private fun isEmptyNaturalResponse(response: ProviderResponse): Boolean {
        if (response.stopReason != AssistantStopReason.END_TURN) return false
        val message = response.assistantMessage
        val content = message.optString("content").trim()
        if (content.isNotBlank() && content != "null") return false
        val reasoning = message.optString("reasoning_content").trim()
        if (reasoning.isNotBlank() && reasoning != "null") return false
        val toolCalls = message.optJSONArray("tool_calls")?.length() ?: 0
        return toolCalls == 0
    }

    companion object {
        private const val MAX_RETRIES = 3
        private const val BASE_DELAY_MS = 2_000L
    }
}
