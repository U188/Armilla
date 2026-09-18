package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ResponsesReasoningStateTest {
    private val config = AgentModelClient.ModelConfig(
        providerSourceType = "custom", baseUrl = "https://example.com/v1", apiKey = "not-persisted",
        model = "deepseek-v4.1-flash", systemPrompt = "test",
        openAiEndpointMode = OpenAiEndpointMode.RESPONSES, reasoningEffort = ReasoningEffort.HIGH,
    )
    private fun assistant() = JSONObject().put("role", "assistant").put("content", "先查一下").put(
        "tool_calls", JSONArray().put(JSONObject().put("id", "call_1").put("type", "function").put(
            "function", JSONObject().put("name", "test").put("arguments", "{\"secret\":\"private-argument\"}"),
        )),
    )
    private fun reasoning() = JSONObject().put("type", "reasoning").put("id", "rs_1").put(
        "content", JSONArray().put(JSONObject().put("type", "reasoning_text").put("text", "真实推理")),
    )
    private fun saved(message: JSONObject): JSONObject {
        val dto = AgentConversationCodec.durableMessage(message)
        val raw = AgentConversationCodec.encodeConversationCheckpoint(listOf(dto))
        return AgentConversationCodec.toJsonObject(AgentConversationCodec.decodeTranscript(raw).single())
    }
    private fun input(messages: JSONArray, model: AgentModelClient.ModelConfig = config) =
        ResponsesRequestBuilder.build(model, messages, JSONArray()).getJSONArray("input")

    @Test fun checkpointAndIpcReplayOriginalReasoningBeforeCalls() {
        val message = assistant()
        ResponsesEphemeralState.attachOutputItems(message, JSONArray().put(reasoning()))
        ResponsesReasoningState.capture(message, config)
        val history = AgentConversationCodec.assistantHistoryMessage(message, AgentConversationCodec.parseToolCalls(message))
        val durable = saved(history)
        val ipc = AgentConversationCodec.encodeTranscriptForIpc(listOf(AgentConversationCodec.durableMessage(durable)))
        val replayed = AgentConversationCodec.toJsonObject(AgentConversationCodec.decodeTranscript(ipc).single())
        assertNull(ResponsesEphemeralState.outputItems(replayed))
        val result = input(JSONArray().put(replayed))
        assertEquals(reasoning().toString(), result.getJSONObject(0).toString())
        assertEquals("function_call", result.getJSONObject(2).getString("type"))
        assertFalse(replayed.getJSONObject(ResponsesReasoningState.KEY).toString().contains("private-argument"))
        assertFalse(ipc.contains("not-persisted"))
    }

    @Test fun scopesPreventReasoningReplayToOtherProviderOrModel() {
        val message = assistant()
        ResponsesEphemeralState.attachOutputItems(message, JSONArray().put(reasoning()))
        ResponsesReasoningState.capture(message, config)
        val restored = saved(message)
        assertNull(ResponsesReasoningState.items(restored, config.copy(model = "other")))
        assertNull(ResponsesReasoningState.items(restored, config.copy(baseUrl = "https://other.example/v1")))
        assertNull(ResponsesReasoningState.items(restored, config.copy(providerId = "other")))
        assertNotNull(ResponsesReasoningState.items(restored, config.copy(apiKey = "rotated")))
    }

    @Test fun oldToolHistoryBecomesEvidenceNotFakeThinkingOrExecutableCalls() {
        val message = assistant().put("reasoning_content", "可能只是旧摘要")
        val result = input(JSONArray().put(message).put(
            JSONObject().put("role", "tool").put("tool_call_id", "call_1").put("content", "STOPPED_OUTCOME_UNKNOWN"),
        ))
        assertEquals(2, result.length())
        for (i in 0 until result.length()) assertEquals("message", result.getJSONObject(i).getString("type"))
        assertTrue(result.getJSONObject(0).getString("content").contains("不要自动重放"))
        assertTrue(result.getJSONObject(1).getString("content").contains("STOPPED_OUTCOME_UNKNOWN"))
        assertFalse(result.toString().contains("reasoning_text"))
        assertEquals("function_call", input(JSONArray().put(message), config.copy(model = "other"))
            .getJSONObject(1).getString("type"))
    }

    @Test fun otherProtocolsCannotLeakInternalReplayMetadata() {
        val message = assistant()
        ResponsesEphemeralState.attachOutputItems(message, JSONArray().put(reasoning()))
        ResponsesReasoningState.capture(message, config)
        val result = OpenAiRequestMessages.forChatCompletions(JSONArray().put(saved(message))).toString()
        assertFalse(result.contains(ResponsesReasoningState.KEY))
        assertFalse(result.contains("_eta_responses_output_items"))
    }

    @Test fun sensitiveToolRedactionIsNotBypassedByDurableReplay() {
        val message = assistant()
        ResponsesEphemeralState.attachOutputItems(message, JSONArray().put(reasoning()))
        ResponsesReasoningState.capture(message, config)
        val redacted = AgentConversationCodec.redactSensitiveMessages(
            listOf(AgentConversationCodec.durableMessage(message)), setOf("call_1"),
        ).single()
        val result = input(JSONArray().put(AgentConversationCodec.toJsonObject(redacted)))
        assertFalse(result.toString().contains("private-argument"))
        assertTrue(result.toString().contains("真实推理"))
        assertTrue(result.getJSONObject(2).getString("arguments").contains("redacted"))
    }

    @Test fun malformedDataIsRejectedAndLegacyDtoRemainsReadable() {
        assertEquals("", ResponsesReasoningState.sanitize("not json"))
        val legacy = AgentConversationCodec.decodeTranscript("[{\"role\":\"assistant\",\"content\":\"old\"}]").single()
        assertEquals("", legacy.responsesReasoningJson)
    }
}
