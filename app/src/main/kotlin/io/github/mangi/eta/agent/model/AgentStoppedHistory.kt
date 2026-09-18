package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Seal interrupted tool batches without claiming success or replaying side effects. */
internal object AgentStoppedHistory {
    fun closePendingTools(messages: JSONArray, turnId: String) {
        val pending = linkedMapOf<String, AgentModelClient.ToolCall>()
        for (index in 0 until messages.length()) {
            val message = messages.optJSONObject(index) ?: continue
            if (message.optString(AgentTurnIdentity.JSON_KEY) != turnId) continue
            when (message.optString("role")) {
                "assistant" -> AgentConversationCodec.parseToolCalls(message).forEach { pending[it.id] = it }
                "tool" -> pending.remove(message.optString("tool_call_id"))
            }
        }
        pending.values.forEach { call ->
            messages.put(AgentConversationCodec.toolResultMessage(call, AgentModelClient.ToolResult(
                content = JSONObject().put("ok", false).put("code", "STOPPED_OUTCOME_UNKNOWN")
                    .put("message", "用户已停止本轮；此调用未取得可确认结果，可能未执行或已部分执行。不要自动重放，请先核验实际状态。")
                    .toString(),
            )).put(AgentTurnIdentity.JSON_KEY, turnId))
        }
    }
}
