package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentRequestMediaPolicyTest {
    private fun message(type: String) = JSONObject().put("role", "user").put("content", JSONArray()
        .put(JSONObject().put("type", "text").put("text", "question"))
        .put(JSONObject().put("type", type).put(type, JSONObject().put("url", "data:image/png;base64,aGVsbG8="))))

    @Test fun removesMediaAcrossHistorySteeringAndToolImageUserMessagesWithoutMutatingHistory() {
        val history = JSONArray().put(message("image_url")).put(message("input_image"))
            .put(message("video_url"))
        val original = history.toString()
        val result = AgentRequestMediaPolicy.filter(history, false, false)
        assertEquals(original, history.toString())
        assertFalse(result.toString().contains("data:image"))
        for (i in 0 until result.length()) {
            val parts = result.getJSONObject(i).getJSONArray("content")
            for (j in 0 until parts.length()) assertEquals("text", parts.getJSONObject(j).getString("type"))
        }
    }

    @Test fun preservesToolCallResultTextAndVisionWhenEnabled() {
        val tool = JSONObject().put("role", "tool").put("tool_call_id", "call-1").put("content", "done")
        val original = JSONArray().put(tool).put(message("image_url"))
        val result = AgentRequestMediaPolicy.filter(original, true, false)
        assertEquals("call-1", result.getJSONObject(0).getString("tool_call_id"))
        assertTrue(result.getJSONObject(1).toString().contains("image_url"))
    }

    @Test fun videoCapabilityDoesNotImplyImageCapability() {
        val result = AgentRequestMediaPolicy.filter(JSONArray().put(message("image_url")).put(message("video_url")), false, true)
        assertFalse(result.getJSONObject(0).toString().contains("image_url"))
        assertTrue(result.getJSONObject(1).toString().contains("video_url"))
    }
}
