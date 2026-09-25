package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLHandshakeException

class AgentModelRetryTest {
    @Test
    fun retriesAreBoundedAndBackoffIsPerModelRound() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        val failure = assertThrows(AgentModelFailure::class.java) {
            complete(retry, provider { _, _ -> calls++; throw SocketTimeoutException("timeout") })
        }
        assertEquals(4, calls)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertTrue(failure.message.orEmpty().contains("已重试 3 次"))
        delays.clear()
        calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) throw IOException("connection reset")
            response()
        })
        assertEquals(2, result.round)
        assertEquals(listOf(2_000L), delays)
    }

    @Test
    fun emptyNaturalResponseRetriesThenReturnsLastEmptyResponse() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        val result = complete(retry, provider { _, _ ->
            calls++
            emptyResponse()
        })
        // 首次 + 3 次重试都空，最终返回最后一次空响应交给 Loop 报错。
        assertEquals(4, calls)
        assertEquals(listOf(2_000L, 4_000L, 8_000L), delays)
        assertEquals("stop", result.response.assistantMessage.optString("finish_reason"))
        assertTrue(result.response.assistantMessage.optString("content").isBlank())
    }

    @Test
    fun emptyNaturalResponseRecoversWhenLaterAttemptHasContent() {
        val delays = mutableListOf<Long>()
        val retry = AgentModelRetry { _, delay -> delays += delay }
        var calls = 0
        val result = complete(retry, provider { _, _ ->
            if (calls++ == 0) emptyResponse() else response()
        })
        assertEquals(2, calls)
        assertEquals(listOf(2_000L), delays)
        assertEquals("完成", result.response.assistantMessage.optString("content"))
    }

    @Test
    fun reasoningOnlyResponseIsNotTreatedAsEmpty() {
        val retry = AgentModelRetry { _, _ -> fail("有推理内容不应重试") }
        var calls = 0
        val result = complete(retry, provider { _, _ ->
            calls++
            ProviderResponse(JSONObject().put("content", "").put("reasoning_content", "思考中").put("finish_reason", "stop"))
        })
        assertEquals(1, calls)
        assertEquals("思考中", result.response.assistantMessage.optString("reasoning_content"))
    }

    @Test
    fun emptyResponseWithToolCallsIsNotTreatedAsEmpty() {
        val retry = AgentModelRetry { _, _ -> fail("有工具调用不应重试") }
        var calls = 0
        val toolCall = JSONObject()
            .put("id", "call-1")
            .put("type", "function")
            .put("function", JSONObject().put("name", "ping").put("arguments", "{}"))
        val result = complete(retry, provider { _, _ ->
            calls++
            ProviderResponse(
                JSONObject()
                    .put("content", "")
                    .put("finish_reason", "tool_calls")
                    .put("tool_calls", JSONArray().put(toolCall)),
            )
        })
        assertEquals(1, calls)
        assertEquals(1, result.response.assistantMessage.optJSONArray("tool_calls")?.length())
    }

    @Test
    fun emptyResponseAfterVisibleTextIsNotRetried() {
        val retry = AgentModelRetry { _, _ -> fail("已吐出可见正文不应重试") }
        var calls = 0
        val result = complete(retry, provider { _, emit ->
            calls++
            emit(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "半截正文"))
            emptyResponse()
        })
        assertEquals(1, calls)
        assertEquals("stop", result.response.assistantMessage.optString("finish_reason"))
    }

    @Test
    fun emptyTurnDuringPendingSteeringIsNotRetried() {
        // 暂停/steering 导致的空回合是设计内的合法空回合，由 Loop 继续同一 run 处理，绝不重试。
        val retry = AgentModelRetry { _, _ -> fail("steering 空回合不应触发空回复重试") }
        val controller = AgentRunController()
        var calls = 0
        val result = complete(
            retry,
            provider { _, _ ->
                calls++
                val binding = controller.register(interruptible = true) {}
                controller.pause()
                controller.steer("补充指令")
                binding.close()
                emptyResponse()
            },
            controller,
        )
        assertEquals(1, calls)
        assertEquals("stop", result.response.assistantMessage.optString("finish_reason"))
    }

    @Test
    fun cancellationDuringBackoffStopsBeforeAnotherRequest() {
        val controller = AgentRunController()
        var calls = 0
        assertThrows(AgentRunCancelledException::class.java) {
            complete(
                AgentModelRetry { control, _ -> control.cancel() },
                provider { _, _ -> calls++; throw SocketTimeoutException() },
                controller,
            )
        }
        assertEquals(1, calls)
    }

    @Test
    fun callbackFailuresAndHostedToolFailuresDoNotReplayProvider() {
        val noRetry = AgentModelRetry { _, _ -> fail("不应重试") }
        val callbackFailure = IOException("checkpoint write failed")
        val thrown = assertThrows(IOException::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.RequestStarted)
                response()
            }, onProviderEvent = { _, _ -> throw callbackFailure })
        }
        assertSame(callbackFailure, thrown)
        assertThrows(AgentModelFailure::class.java) {
            complete(noRetry, provider { _, emit ->
                emit(ProviderEvent.HostedToolStarted("search-1", "web_search"))
                throw SocketTimeoutException()
            })
        }
    }

    @Test
    fun classifiesTransientFailuresWithoutRetryingPermanentFailures() {
        for (status in listOf(408, 429, 500, 502, 503, 504, 529)) {
            assertTrue(AgentModelFailure.http(status, "").retryable)
        }
        for (status in listOf(400, 401, 403, 404)) {
            assertFalse(AgentModelFailure.http(status, "").retryable)
        }
        assertFalse(AgentModelFailure.http(429, """{"error":{"code":"insufficient_quota"}}""").retryable)
        assertNull(AgentModelFailure.transport(SSLHandshakeException("certificate")))
        assertNull(AgentModelFailure.transport(org.json.JSONException("invalid JSON")))
        assertTrue(AgentModelFailure.stream(JSONObject().put("type", "overloaded_error"), "过载").retryable)
        assertFalse(AgentModelFailure.stream(JSONObject().put("type", "authentication_error"), "认证失败").retryable)
        assertFalse(AgentModelFailure.http(503, "secret request text").message.orEmpty().contains("secret"))
        assertTrue(
            AgentModelFailure.http(
                400,
                """{"error":{"message":"The `reasoning_content` in the thinking mode must be passed back to the API."}}""",
            ).message.orEmpty().contains("reasoning_content"),
        )
        assertEquals(
            "模型请求参数无效（HTTP 400），请检查模型配置。",
            AgentModelFailure.http(400, "").message,
        )
    }

    private fun complete(
        retry: AgentModelRetry,
        provider: AgentProviderClient,
        controller: AgentRunController = AgentRunController(),
        onProviderEvent: (Int, ProviderEvent) -> Unit = { _, _ -> },
    ) = retry.complete(
        initialRound = 1,
        request = ProviderRequest(
            AgentModelClient.ModelConfig(baseUrl = "https://example.invalid", apiKey = "test-key", model = "test-model", systemPrompt = ""),
            JSONArray(), JSONArray(),
        ),
        provider = provider,
        controller = controller,
        onEvent = {},
        onProviderEvent = onProviderEvent,
        discardAttemptReasoning = {},
    )

    private fun response() = ProviderResponse(JSONObject().put("content", "完成").put("finish_reason", "stop"))

    private fun emptyResponse() = ProviderResponse(JSONObject().put("content", "").put("finish_reason", "stop"))

    private fun provider(action: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) =
        object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, false, false, false)
            override fun complete(
                request: ProviderRequest,
                runController: AgentRunController,
                onEvent: (ProviderEvent) -> Unit,
            ) = action(request, onEvent)
        }
}
