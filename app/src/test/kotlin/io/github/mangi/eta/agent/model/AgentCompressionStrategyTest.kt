package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AgentCompressionStrategyTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun message(role: String, text: String = "", id: String = "", calls: String = "") =
        AgentModelClient.ConversationMessage(role, content = text, toolCallId = id, toolCallsJson = calls)

    @Test fun missingStrategyDefaultsToContinueTask() {
        assertEquals(AgentCompressionStrategy.CONTINUE_TASK, AgentCompressionStrategy.parse(null))
        assertEquals(AgentCompressionStrategy.CONTINUE_TASK, AgentCompressionStrategy.parse(""))
        assertEquals(AgentCompressionStrategy.CONTINUE_TASK, AgentCompressionStrategy.parse("continue_task"))
        assertEquals(AgentCompressionStrategy.PRESERVE_TURN, AgentCompressionStrategy.parse("preserve_turn"))
    }

    @Test fun unknownStrategyFailsClosedToPreserveTurn() {
        assertEquals(AgentCompressionStrategy.PRESERVE_TURN, AgentCompressionStrategy.parse("unexpected"))
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

    @Test fun continuationUsesTheSameTokenTailInIdleAndActiveRuns() {
        val history = listOf(message("user", "one long task"),
            message("assistant", calls = """[{"id":"old"}]"""),
            message("tool", "x".repeat(20_000), id = "old"),
            message("assistant", calls = """[{"id":"latest"}]"""),
            message("tool", "y".repeat(8_000), id = "latest"))
        val idle = AgentCompressionBoundary.selectStart(history, AgentCompressionStrategy.CONTINUE_TASK, 0, 10_000)
        val active = AgentCompressionBoundary.selectStart(history, AgentCompressionStrategy.CONTINUE_TASK, 0, 10_000, activeStart = 0)
        assertEquals(3, idle)
        assertEquals(idle, active)
        assertEquals(0, AgentCompressionBoundary.selectStart(history, AgentCompressionStrategy.PRESERVE_TURN, 1, 10_000, activeStart = 0))
        assertTrue(AgentContextCompactor.shouldCompress(history, 10_000, 0,
            estimatedTokens = 9500, strategy = AgentCompressionStrategy.CONTINUE_TASK))
        assertFalse(AgentContextCompactor.shouldCompress(history, 10_000, 1,
            estimatedTokens = 9500, strategy = AgentCompressionStrategy.PRESERVE_TURN))
    }

    @Test fun overflowCanReduceRetentionButNeverSplitsTheLatestParallelBatch() {
        val history = listOf(message("user", "task"), message("assistant", "x".repeat(8000)),
            message("assistant", calls = """[{"id":"a"},{"id":"b"}]"""),
            message("tool", "a", id = "a"), message("tool", "b", id = "b"))
        assertEquals(1, AgentCompressionBoundary.selectStart(history, AgentCompressionStrategy.CONTINUE_TASK, 0, 10_000))
        assertEquals(2, AgentCompressionBoundary.selectStart(history, AgentCompressionStrategy.CONTINUE_TASK, 0, 10_000, overflow = true))
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

    @Test fun inRunManualAutoTargetIsResolvedInsteadOfClampedTo500() {
        val controller = AgentRunController()
        controller.requestCompact(1, AgentContextCompactor.AUTO_TARGET_TOKENS)
        val model = config(128_000)
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("x".repeat(160_000)))
            .put(AgentConversationCodec.userTextMessage("protected"))
        var resolved = 0
        AgentLoop(model, source, JSONArray(), provider {
            assertEquals(4000, resolved)
            JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
        }, AgentModelClient.ToolExecutor { error("No tools") }, controller, AgentTraceFormatter(),
            onEvent = { if (it is AgentEvent.ContextCompacted) assertFalse(it.reason, it.blocked) },
            compactPolicy = AgentLoop.CompactPolicy(false, 128_000, 1, 500, model),
            compactHistory = { history, policy ->
                resolved = policy.targetTokens
                listOf(message("user", "[Conversation summary]\nverified old work")) + history.drop(1)
            },
        ).run()
        assertEquals(4000, resolved)
    }

    @Test fun firstRequestCompactsAtPressureBeforeSendingAnOtherwiseValidRequest() {
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("x".repeat(352_000)))
            .put(JSONObject().put("role", "assistant").put("content", "old result"))
            .put(AgentConversationCodec.userTextMessage("y".repeat(8000)))
        val model = config(100_000)
        var compacted = false
        var requests = 0
        AgentLoop(model, source, JSONArray(), provider { request ->
            requests++
            assertTrue(compacted)
            assertTrue(request.messages.getJSONObject(0).getString("content").contains("summary"))
            assertFalse(request.messages.toString().contains("x".repeat(100)))
            JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
        }, AgentModelClient.ToolExecutor { error("No tools") }, AgentRunController(), AgentTraceFormatter(),
            onEvent = { if (it is AgentEvent.ContextCompacted && it.applied) compacted = true },
            compactPolicy = AgentLoop.CompactPolicy(true, 100_000, 1, 500, model),
            compactHistory = { history, _ -> listOf(message("user", "[Conversation summary]\nold task")) + history.drop(2) },
        ).run()
        assertEquals(1, requests)
    }

    @Test fun manualContinuationPrunesOldStepsButPreservesLatestBatchWithinOneRun() {
        val controller = AgentRunController()
        val model = config(20_000)
        val events = mutableListOf<AgentEvent>()
        val tail = "L".repeat(16_000)
        var requests = 0
        var toolRuns = 0
        var summaries = 0
        val result = AgentLoop(model, JSONArray().put(AgentConversationCodec.userTextMessage("one long task")),
            JSONArray("""[{"type":"function","function":{"name":"get_current_context","parameters":{"type":"object","properties":{}}}}]"""), provider { request ->
                requests++
                if (requests <= 2) JSONObject().put("role", "assistant").put("content", "")
                    .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                        .put("id", "call-$requests").put("type", "function").put("function", JSONObject()
                            .put("name", "get_current_context").put("arguments", "{}"))))
                else {
                    assertEquals(1, summaries)
                    val liveTool = (0 until request.messages.length()).map { request.messages.getJSONObject(it) }
                        .single { it.optString("role") == "tool" }
                    assertEquals("call-2", liveTool.getString("tool_call_id"))
                    assertEquals(tail, liveTool.getString("content"))
                    JSONObject().put("role", "assistant").put("content", "done").put("finish_reason", "stop")
                }
            }, AgentModelClient.ToolExecutor {
                toolRuns++
                if (toolRuns == 2) controller.requestCompact(0, 500, strategy = AgentCompressionStrategy.CONTINUE_TASK)
                AgentModelClient.ToolResult(if (toolRuns == 1) "O".repeat(20_000) else tail)
            }, controller, AgentTraceFormatter(), onEvent = {
                if (it is AgentEvent.ContextCompacted) assertFalse(it.reason, it.blocked)
                events += it
            },
            compactPolicy = AgentLoop.CompactPolicy(false, 20_000, 0, 500, model, AgentCompressionStrategy.CONTINUE_TASK),
            compactionArchive = AgentCompactionArchive(temporary.root, "same-run"),
            compactHistory = { history, _ ->
                summaries++
                assertTrue(history[2].content.contains("[Eta tool output pruned;"))
                assertEquals(tail, history.last().content)
                val cut = AgentCompressionBoundary.selectStart(history, AgentCompressionStrategy.CONTINUE_TASK, 0, 20_000)
                assertEquals(3, cut)
                listOf(message("user", "[Conversation summary]\nfirst step verified")) + history.drop(cut)
            },
        ).run()
        assertEquals("done", result.content)
        assertEquals(3, requests)
        assertEquals(2, toolRuns)
        assertEquals(2, events.filterIsInstance<AgentEvent.ContextCompacted>().count { it.applied })
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

    @Test fun largeWindowDoesNotPauseAtHalfCapacityJustBecauseHistoryIsLong() {
        var calls = 0
        val controller = AgentRunController()
        val source = JSONArray().put(AgentConversationCodec.userTextMessage("x".repeat(900_000)))
        val loop = AgentLoop(
            config(500_000),
            source,
            JSONArray(),
            provider {
                calls++
                JSONObject().put("role", "assistant").put("content", "ok").put("finish_reason", "stop")
            },
            AgentModelClient.ToolExecutor { error("No tools") },
            controller,
            AgentTraceFormatter(),
            onEvent = {},
        )
        val result = loop.run()
        assertEquals("ok", result.content)
        assertEquals(1, calls)
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
