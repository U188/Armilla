package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentRequestMediaPolicy {
    fun filter(messages: JSONArray, supportsVision: Boolean, supportsVideo: Boolean): JSONArray {
        val copy = JSONArray(messages.toString())
        for (i in 0 until copy.length()) {
            val message = copy.optJSONObject(i) ?: continue
            message.remove(AgentTurnIdentity.JSON_KEY)
            val original = message.optJSONArray("content") ?: continue
            val hasPersisted = (0 until original.length()).any {
                original.optJSONObject(it)?.optString("type") in setOf("image_file", "video_file")
            }
            val content = if (hasPersisted) {
                val hydrated = io.github.mangi.eta.agent.media.AgentHistoryImageHydrator.hydrate(
                    AgentModelClient.ConversationMessage(role = message.optString("role"),
                        content = "", contentJson = original.toString()), supportsVision, supportsVideo)
                JSONArray(hydrated.contentJson)
            } else original
            val allowed = JSONArray()
            var omitted = false
            for (j in 0 until content.length()) {
                val part = content.optJSONObject(j)
                val keep = when (part?.optString("type")) {
                    "image_url", "input_image", "image", "image_file" -> supportsVision
                    "video_url", "input_video", "video", "video_file" -> supportsVideo
                    else -> true
                }
                if (keep) allowed.put(content.get(j)) else omitted = true
            }
            if (omitted) allowed.put(JSONObject().put("type", "text").put("text",
                "[Media omitted: this model is not configured to accept it. Do not claim to have seen it. Choose a vision-capable model to inspect images.]"))
            message.put("content", allowed)
        }
        return copy
    }
}
