package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentCompressionStrategyTest {
    private fun message(role: String, text: String = "", id: String = "", calls: String = "") =
        AgentModelClient.ConversationMessage(role, content = text, toolCallId = id, toolCallsJson = calls)

    @Test fun unknownOrMissingStrategyAlwaysPreservesTurns() {
        assertEquals(AgentCompressionStrategy.PRESERVE_TURN, AgentCompressionStrategy.parse(null))
        assertEquals(AgentCompressionStrategy.PRESERVE_TURN, AgentCompressionStrategy.parse("unexpected"))
        assertEquals(AgentCompressionStrategy.CONTINUE_TASK, AgentCompressionStrategy.parse("continue_task"))
    }

    @Test fun strictBoundaryIncludesEntireActiveTurnEvenIfANewUserMessageAppears() {
        val history = listOf(message("user", "old"), message("assistant", "old answer"),
            message("user", "active"), message("assistant", calls = "[{\"id\":\"a\"}]"),
            message("tool", "tool output", id = "a"), message("user", "injected user-shaped record"))
        assertEquals(2, AgentCompressionBoundary.protectedStart(history, 1, 2))
        assertEquals(0, AgentCompressionBoundary.protectedStart(history, 10, 2))
    }

    @Test fun parallelToolResultsCannotBeSplit() {
        val history = listOf(message("user", "old"),
            message("assistant", calls = "[{\"id\":\"a\"},{\"id\":\"b\"}]"),
            message("tool", "one", id = "a"), message("tool", "two", id = "b"), message("assistant", "done"))
        assertEquals(listOf(0, 1, 4, 5), AgentCompressionBoundary.balancedCuts(history))
        assertEquals(4, AgentCompressionBoundary.continuationStart(history, 1))
        assertEquals(1, AgentCompressionBoundary.continuationStart(history.dropLast(1), 1))
    }

    @Test fun orphanedOrUnfinishedToolsCannotBeCompacted() {
        assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(listOf(message("tool", "orphan", id = "a"))) }
        assertThrows(IllegalArgumentException::class.java) { AgentCompressionBoundary.balancedCuts(listOf(message("assistant", calls = "[{\"id\":\"a\"}]"))) }
    }

    @Test fun budgetsReserveOutputAndSafetyMargin() {
        assertEquals(4392, AgentCompressionBoundary.inputLimit(9000))
        assertEquals(0, AgentCompressionBoundary.inputLimit(100))
        assertEquals(6000, AgentCompressionBoundary.outputReserve(config().copy(extraBodyJson = "{\"max_tokens\":6000}")))
    }

    @Test fun summaryInputKeepsToolArgumentsCorrelationAndStructuredTextNotHiddenReasoning() {
        val input = message("assistant", calls = "[{\"id\":\"c1\",\"function\":{\"name\":\"terminal\",\"arguments\":\"exact command\"}}]")
            .copy(contentJson = "[{\"type\":\"text\",\"text\":\"structured evidence\"}]", reasoningContent = "PRIVATE_REASONING")
        val text = AgentContextCompactor.messageToSummaryText(input)
        assertTrue(text.contains("exact command"))
        assertTrue(text.contains("structured evidence"))
        assertFalse(text.contains("PRIVATE_REASONING"))
        assertTrue(AgentContextCompactor.messageToSummaryText(message("tool", "exit_code=0", "c1")).contains("c1"))
    }

    @Test fun oneRunConsentIsNotInheritedByAnotherController() {
        val controller = AgentRunController()
        controller.requestCompact(allowCurrentTurn = true)
        controller.takePendingCompact()
        assertTrue(controller.allowCurrentTurnCompaction)
        assertFalse(AgentRunController().allowCurrentTurnCompaction)
    }

    @Test fun strictOverflowPausesWithoutSendingOrDeletingHistory() {
        val controller = AgentRunController()
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("x".repeat(40_000)))
        val original = source.toString()
        val provider = provider { error("Over-budget request must never be sent") }
        var paused = false
        val loop = AgentLoop(config(9000), source, JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            onEvent = { if (it is AgentEvent.ContextCompacted && it.blocked) { paused = true; controller.cancel() } })
        assertThrows(AgentRunCancelledException::class.java) { loop.run() }
        assertTrue(paused)
        source.getJSONObject(0).remove(AgentTurnIdentity.JSON_KEY)
        assertEquals(original, source.toString())
    }

    @Test fun forcedCompressionPreservesLongLiveToolBodyAndProviderFields() {
        val controller = AgentRunController()
        val toolBody = "Z".repeat(70_000)
        val currentUser = AgentConversationCodec.userTextMessage("active request").put("provider_private", JSONObject().put("opaque", "keep"))
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("old question " + "x".repeat(8000)))
            .put(JSONObject().put("role", "assistant").put("content", "old answer"))
            .put(currentUser)
        var calls = 0
        var compressed = false
        val provider = provider { request ->
            if (calls++ == 0) JSONObject().put("role", "assistant").put("content", "")
                .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                    .put("id", "tool-1").put("type", "function").put("function", JSONObject()
                        .put("name", "get_current_context").put("arguments", "{}"))))
            else {
                val user = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                    .single { it.optString("content") == "active request" }
                assertEquals(JSONObject(currentUser.toString()).also { it.remove(AgentTurnIdentity.JSON_KEY) }.toString(), user.toString())
                val result = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                    .single { it.optString("role") == "tool" }
                assertEquals(toolBody, result.getString("content"))
                JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
            }
        }
        val model = config(200_000)
        AgentLoop(model, source, AgentToolCatalog.build(terminalTools = false, browserTools = false), provider,
            AgentModelClient.ToolExecutor { controller.requestCompact(1, 500); AgentModelClient.ToolResult(toolBody) },
            controller, AgentTraceFormatter(), onEvent = { if (it is AgentEvent.ContextCompacted && it.applied) compressed = true },
            compactPolicy = AgentLoop.CompactPolicy(false, 200_000, 1, 500, model),
            compactHistory = { history, _ -> listOf(message("user", "[Conversation summary]\nold work")) + history.drop(2) },
        ).run()
        assertTrue(compressed)
        assertEquals(2, calls)
    }

    @Test fun providerOverflowDoesNotBlindlyRetryAnUnchangedRequest() {
        var calls = 0
        val controller = AgentRunController()
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("active"))
        val loop = AgentLoop(config(9000), source, JSONArray(), provider {
            calls++
            throw AgentModelFailure("CONTEXT_WINDOW_EXCEEDED", false, "too large")
        }, AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            onEvent = { if (it is AgentEvent.ContextCompacted && it.blocked) controller.cancel() })
        assertThrows(AgentRunCancelledException::class.java) { loop.run() }
        assertEquals(1, calls)
        assertEquals("active", source.getJSONObject(0).getString("content"))
    }

    @Test fun overflowClassificationDoesNotTreatAllBadRequestsAsContextErrors() {
        assertEquals("CONTEXT_WINDOW_EXCEEDED", AgentModelFailure.http(400, "{\"error\":{\"code\":\"context_length_exceeded\"}}").code)
        assertEquals("HTTP_400", AgentModelFailure.http(400, "{\"error\":{\"message\":\"unknown parameter\"}}").code)
        assertEquals("HTTP_401", AgentModelFailure.http(401, "{\"error\":{\"code\":\"context_length_exceeded\"}}").code)
    }

    private fun config(window: Int = 9000) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test", systemPrompt = "", contextWindow = window,
    )

    private fun provider(block: (ProviderRequest) -> JSONObject) = object : AgentProviderClient {
        override val id = "test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            ProviderResponse(block(request))
    }
}
