package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

/** Keep tool arguments when a terminal event replaces a fuller streamed object. */
internal object ToolArguments {
    fun merge(existing: String, incoming: Any?): String {
        val next = text(incoming)
        if (next.isBlank()) return existing
        val previous = runCatching { JSONObject(existing) }.getOrNull()
        val replacement = runCatching { JSONObject(next) }.getOrNull()
        if (previous != null && replacement != null) {
            val merged = JSONObject(replacement.toString())
            for (key in previous.keys()) {
                if (!merged.has(key) || merged.isNull(key)) merged.put(key, previous.get(key))
            }
            return merged.toString()
        }
        if (previous != null) return existing
        return next
    }

    private fun text(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is String -> value
        is JSONObject, is JSONArray -> value.toString()
        else -> value.toString()
    }
}
