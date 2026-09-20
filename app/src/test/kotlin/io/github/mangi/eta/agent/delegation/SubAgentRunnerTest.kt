package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.AgentRunController
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SubAgentRunnerTest {
    @Test fun independentContextReadToolLoopAndFinalResult() {
        val config = AgentModelClient.ModelConfig(baseUrl = "https://example.com", apiKey = "test",
            model = "child", systemPrompt = "PRIVATE ASSISTANT INSTRUCTIONS", hostedWebSearchEnabled = true)
        val tools = JSONArray().put(AgentToolSchema.function("device_status", "read", JSONObject().put("type", "object")))
        tools.put(AgentToolSchema.function("write_file", "write", JSONObject().put("type", "object")))
        SubAgentTools.appendTo(tools, listOf("recursive worker"))
        var rounds = 0
        var executions = 0
        val provider = object : AgentProviderClient {
            override val id = "test"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, false, false, false, false, false, false)
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                assertFalse(request.messages.toString().contains("PRIVATE ASSISTANT INSTRUCTIONS"))
                assertFalse(request.config.hostedWebSearchEnabled)
                assertEquals(1, request.tools.length())
                if (++rounds == 1) {
                    assertEquals(2, request.messages.length())
                    assertEquals("inspect device", request.messages.getJSONObject(1).getString("content"))
                    return ProviderResponse(JSONObject().put("role", "assistant").put("content", "")
                        .put("finish_reason", "tool_calls").put("tool_calls", JSONArray().put(JSONObject()
                            .put("id", "read").put("type", "function").put("function", JSONObject()
                                .put("name", "device_status").put("arguments", "{}")))))
                }
                assertTrue(request.messages.toString().contains("battery evidence"))
                return ProviderResponse(JSONObject().put("role", "assistant").put("content", "reviewed evidence").put("finish_reason", "stop"))
            }
        }
        val result = SubAgentRunner.run(config, "inspect device", tools,
            { executions++; AgentModelClient.ToolResult("battery evidence") }, AgentRunController(), provider)
        assertEquals("reviewed evidence", result)
        assertEquals(2, rounds)
        assertEquals(1, executions)
    }
}
