package io.github.mangi.eta.agent.model

import io.github.mangi.eta.data.model.OpenAiEndpointMode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AntigravityRequestBuilderTest {
    @Test
    fun convertsChatMessagesAndTools() {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", "你是助手"))
            .put(JSONObject().put("role", "user").put("content", "你好"))
        val tools = JSONArray().put(
            JSONObject().put("type", "function").put(
                "function",
                JSONObject().put("name", "device_info").put("description", "设备").put("parameters", JSONObject().put("type", "object")),
            ),
        )
        val request = AntigravityRequestBuilder.build(
            config = AgentModelClient.ModelConfig(
                baseUrl = "https://daily-cloudcode-pa.googleapis.com",
                apiKey = "token",
                model = "gemini-3-flash",
                systemPrompt = "系统",
                openAiEndpointMode = OpenAiEndpointMode.ANTIGRAVITY,
            ),
            messages = messages,
            tools = tools,
            projectId = "proj-1",
        )
        assertEquals("proj-1", request.getString("project"))
        assertEquals("gemini-3-flash", request.getString("model"))
        val inner = request.getJSONObject("request")
        assertEquals("user", inner.getJSONArray("contents").getJSONObject(0).getString("role"))
        assertEquals("device_info", inner.getJSONArray("tools").getJSONObject(0)
            .getJSONArray("functionDeclarations").getJSONObject(0).getString("name"))
        assertTrue(inner.getJSONObject("systemInstruction").getJSONArray("parts").length() >= 1)
    }
}
