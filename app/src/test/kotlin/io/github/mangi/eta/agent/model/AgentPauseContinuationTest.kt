package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ReasoningEffort
import io.github.mangi.eta.ui.app.AgentRunMessageProjector
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentMessageUi
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentPauseContinuationTest {
    private fun config() = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test", systemPrompt = "",
        thinkingEnabled = true, reasoningEffort = ReasoningEffort.HIGH,
    )

    private fun project(events: List<AgentEvent>): List<AgentChatMessageUi> {
        val projector = AgentRunMessageProjector { 1_000L }
        var messages = emptyList<AgentChatMessageUi>()
        events.forEach { event ->
            messages = when (event) {
                is AgentEvent.AssistantBlockStart -> projector.startAssistantBlock("run", event, messages)
                is AgentEvent.AssistantBlockDelta -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT)
                    projector.appendTextDelta("run", event.round, event.index, event.delta, messages) else messages
                is AgentEvent.AssistantBlockEnd -> if (event.kind == AgentEvent.AssistantBlockKind.TEXT)
                    projector.finalizeTextBlock("run", event.round, event.index, event.replacementContent, messages) else messages
                is AgentEvent.RunFinished -> projector.finalizeRun("run", messages)
                else -> messages
            }
        }
        return messages
    }

    @Test fun threePausedSegmentsStayInOneRoundAndSurviveFinalResultAndReplay() {
        val controller = AgentRunController()
        val events = mutableListOf<AgentEvent>()
        val history = JSONArray().put(JSONObject().put("role", "user").put("content", "task"))
        val parts = listOf("first ", "second ", "third")
        var calls = 0
        val provider = object : AgentProviderClient {
            override val id = "pause-test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                val part = parts[calls++]
                assertEquals(if (calls == 1) ReasoningEffort.HIGH else ReasoningEffort.OFF, request.config.reasoningEffort)
                onEvent(ProviderEvent.RequestStarted)
                onEvent(ProviderEvent.BlockStart(AssistantBlockKind.TEXT, 0))
                onEvent(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "draft"))
                onEvent(ProviderEvent.BlockEnd(AssistantBlockKind.TEXT, 0, content = part, replaceContent = true))
                if (calls < parts.size) {
                    val binding = runController.register(interruptible = true) {}
                    runController.pause()
                    runController.resume()
                    binding.close()
                }
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", part).put("finish_reason", "stop"))
            }
        }
        val result = AgentLoop(config(), history, JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("no tools") }, controller, AgentTraceFormatter(),
            onEvent = events::add, turnId = "original-turn").run()
        assertEquals("first second third", result.content)
        assertEquals(listOf(1, 1, 1), events.filterIsInstance<AgentEvent.RoundStarted>().map { it.round })
        val projected = project(events).filterIsInstance<AgentMessageUi>()
        assertEquals(1, projected.size)
        assertEquals(result.content, projected.single().content)
        assertEquals(projected, project(events).filterIsInstance<AgentMessageUi>())
        val transcript = (0 until history.length()).map { AgentConversationCodec.fromJsonObject(history.getJSONObject(it)) }
        assertTrue(transcript.all { it.turnId == "original-turn" })
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(transcript, 1))
    }

    @Test fun pauseBeforeAnyTextAlsoDisablesOptionalReasoningOnResume() {
        var calls = 0
        val controller = AgentRunController()
        val provider = object : AgentProviderClient {
            override val id = "empty-pause"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                if (++calls == 1) {
                    val binding = runController.register(interruptible = true) {}
                    runController.pause()
                    runController.resume()
                    binding.close()
                } else assertEquals(ReasoningEffort.OFF, request.config.reasoningEffort)
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", if (calls == 1) "" else "done").put("finish_reason", "stop"))
            }
        }
        AgentLoop(config(), JSONArray().put(AgentConversationCodec.userTextMessage("task")), JSONArray(), provider,
            AgentModelClient.ToolExecutor { error("no tools") }, controller, AgentTraceFormatter(), onEvent = {}).run()
        assertEquals(2, calls)
    }

    @Test fun legacyResumeAndSteeringRecordsDoNotCreateNewCompressionTurns() {
        val history = AgentTurnIdentity.migrate(listOf(
            AgentModelClient.ConversationMessage("user", "task", turnId = "original"),
            AgentModelClient.ConversationMessage("assistant", "partial"),
            AgentModelClient.ConversationMessage("user", AgentContextCompactor.SEAMLESS_CONTINUE_PROMPT),
            AgentModelClient.ConversationMessage("user", AgentContextCompactor.steeringUserContent("more")),
            AgentModelClient.ConversationMessage("assistant", "stopped text"),
        ))
        assertTrue(history.all { it.turnId == "original" })
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(history, 1))
        assertEquals(0, AgentCompressionBoundary.protectedStart(history, 1, history.size))
    }
}
