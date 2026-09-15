package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentSummaryPipelineTest {
    private fun validSummary() = "[Conversation summary]\n" + AgentContextCompactor.SUMMARY_SECTIONS.joinToString("\n") { "## $it\n- (none)" }
    private fun config() = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test",
        systemPrompt = "", contextWindow = 200_000)
    private fun history() = listOf(AgentModelClient.ConversationMessage("user", "OLD " + "a".repeat(10_000)),
        AgentModelClient.ConversationMessage("assistant", "verified old result"), AgentModelClient.ConversationMessage("user", "protected"))
    private fun provider(block: (ProviderRequest) -> JSONObject) = object : AgentProviderClient {
        override val id = "summary-test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = ProviderResponse(block(request))
    }
    private fun response(text: String, finish: String = "stop") = JSONObject().put("role", "assistant").put("content", text).put("finish_reason", finish)

    @Test fun summaryCallIsOneShotCappedAndNeverExecutesTools() {
        var calls = 0
        val result = AgentContextCompactor.compress(history(), AgentContextCompactor.Config(500, 1, config(), provider {
            calls++
            assertEquals(1024, it.config.summaryOutputLimit)
            assertEquals(0, it.tools.length())
            assertFalse(it.config.hostedWebSearchEnabled)
            assertTrue(it.messages.toString().contains("historical"))
            response(validSummary())
        }), toolExecutor = AgentModelClient.ToolExecutor { error("Must never execute") })
        assertEquals(1, calls)
        assertEquals("user", result.first().role)
        assertEquals(history().last(), result.last())
        assertTrue(result.first().content.contains("## Verified evidence"))
    }

    @Test fun truncatedEmptyMalformedAndToolCallingSummariesAreRejected() {
        val bad = listOf(response(validSummary(), "length"), response(""), response("not structured"),
            response(validSummary()).put("tool_calls", JSONArray().put(JSONObject().put("id", "bad"))))
        for (output in bad) {
            val source = history()
            val original = source.toList()
            assertThrows(RuntimeException::class.java) {
                AgentContextCompactor.compress(source, AgentContextCompactor.Config(500, 1, config(), provider { output }))
            }
            assertEquals(original, source)
        }
    }

    @Test fun cancellationIsPropagatedWithoutReplacingTheHistory() {
        val controller = AgentRunController()
        val source = history()
        assertThrows(AgentRunCancelledException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(500, 1, config(), provider {
                controller.cancel()
                response(validSummary())
            }), controller = controller)
        }
        assertEquals("protected", source.last().content)
    }

    @Test fun sameModelReplayRetainsPrefixToolsAndSessionButDoesNotRunAgentLoop() {
        val source = history()
        val system = JSONObject().put("role", "system").put("content", "original instructions")
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "test")))
        val replay = AgentContextCompactor.ReplayContext(JSONArray().put(system),
            JSONArray().put(AgentConversationCodec.toJsonObject(source[0])).put(AgentConversationCodec.toJsonObject(source[1])), tools, "stable-session")
        var calls = 0
        AgentContextCompactor.compress(source, AgentContextCompactor.Config(500, 1, config(), provider {
            calls++
            assertEquals("stable-session", it.sessionId)
            assertEquals(system.toString(), it.messages.getJSONObject(0).toString())
            assertEquals(source[0].content, it.messages.getJSONObject(1).getString("content"))
            assertEquals(tools.toString(), it.tools.toString())
            response(validSummary())
        }), replay = replay)
        assertEquals(1, calls)
    }

    @Test fun duplicateMissingAndReorderedHeadingsFailAcceptance() {
        AgentContextCompactor.validateSummary(validSummary())
        assertThrows(IllegalArgumentException::class.java) { AgentContextCompactor.validateSummary(validSummary() + "\n## Goal\nagain") }
        assertThrows(IllegalArgumentException::class.java) { AgentContextCompactor.validateSummary(validSummary().replace("## Pending work", "## Tasks")) }
    }

    @Test fun turnIdsPersistButNeverLeakToProviderMessages() {
        val original = AgentModelClient.ConversationMessage("user", "question", turnId = "run-1")
        val json = AgentConversationCodec.toJsonObject(original)
        assertEquals(original, AgentConversationCodec.fromJsonObject(json))
        assertFalse(AgentRequestMediaPolicy.filter(JSONArray().put(json), false, false).toString().contains("eta_turn_id"))
        assertEquals("run-1", json.getString(AgentTurnIdentity.JSON_KEY))
        val messages = listOf(original, AgentModelClient.ConversationMessage("assistant", "step", turnId = "run-1"),
            AgentModelClient.ConversationMessage("user", "supplement", turnId = "run-1"))
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(messages, 1))
    }
}
