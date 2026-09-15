package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.media.AgentVideoCodec
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import org.json.JSONArray
import org.json.JSONObject

internal data class DecodedUserMessageImages(
    val previews: List<String>,
    val sources: List<String>,
    val videoFlags: List<Boolean> = emptyList(),
    val durationsMs: List<Long?> = emptyList(),
)

internal fun UserMessageUi.fullImageSourceAt(index: Int): String {
    val source = imageSources.getOrNull(index)?.trim().orEmpty()
    if (source.isNotEmpty()) return source
    return images.getOrNull(index).orEmpty()
}

internal fun UserMessageUi.isVideoAt(index: Int): Boolean =
    imageIsVideo.getOrNull(index) == true || AgentVideoCodec.isVideoSource(fullImageSourceAt(index))

internal fun UserMessageUi.durationMsAt(index: Int): Long? =
    imageDurationsMs.getOrNull(index)?.takeIf { it > 0L }

internal fun encodeUserMessageImages(
    previews: List<String>,
    sources: List<String> = emptyList(),
    videoFlags: List<Boolean> = emptyList(),
    durationsMs: List<Long?> = emptyList(),
): String {
    val array = JSONArray()
    previews.forEachIndexed { index, preview ->
        if (preview.isBlank()) return@forEachIndexed
        val source = sources.getOrNull(index)?.trim().orEmpty()
        val isVideo = videoFlags.getOrNull(index) == true || AgentVideoCodec.isVideoSource(source)
        val durationMs = durationsMs.getOrNull(index)?.takeIf { it > 0L }
        if (!isVideo && durationMs == null && (source.isEmpty() || source == preview)) {
            array.put(preview)
        } else {
            array.put(
                JSONObject()
                    .put("preview", preview)
                    .put("source", source.ifBlank { preview })
                    .also { obj ->
                        if (isVideo) obj.put("kind", "video")
                        if (durationMs != null) obj.put("durationMs", durationMs)
                    },
            )
        }
    }
    return array.toString()
}

internal fun decodeUserMessageImages(raw: String): DecodedUserMessageImages {
    if (raw.isBlank()) return DecodedUserMessageImages(emptyList(), emptyList())
    val array = runCatching { JSONArray(raw) }.getOrNull()
        ?: return DecodedUserMessageImages(emptyList(), emptyList())
    val previews = ArrayList<String>(array.length())
    val sources = ArrayList<String>(array.length())
    val videoFlags = ArrayList<Boolean>(array.length())
    val durations = ArrayList<Long?>(array.length())
    var hasDistinctSource = false
    var hasVideo = false
    var hasDuration = false
    for (index in 0 until array.length()) {
        val obj = array.optJSONObject(index)
        if (obj != null) {
            val preview = obj.optString("preview").ifBlank { obj.optString("dataUrl") }.ifBlank { obj.optString("source") }
            if (preview.isBlank()) continue
            val source = obj.optString("source").ifBlank { preview }
            val isVideo = obj.optString("kind").equals("video", ignoreCase = true) ||
                AgentVideoCodec.isVideoSource(source)
            val durationMs = obj.optLong("durationMs").takeIf { it > 0L }
            previews += preview
            sources += source
            videoFlags += isVideo
            durations += durationMs
            if (source != preview) hasDistinctSource = true
            if (isVideo) hasVideo = true
            if (durationMs != null) hasDuration = true
        } else {
            val preview = array.optString(index).trim()
            if (preview.isBlank()) continue
            previews += preview
            sources += preview
            videoFlags += false
            durations += null
        }
    }
    return DecodedUserMessageImages(
        previews = previews,
        sources = if (hasDistinctSource) sources else emptyList(),
        videoFlags = if (hasVideo) videoFlags else emptyList(),
        durationsMs = if (hasDuration) durations else emptyList(),
    )
}

internal fun attachUserImageSources(
    messages: List<AgentChatMessageUi>,
    history: List<AgentModelClient.ConversationMessage>,
): List<AgentChatMessageUi> {
    return messages.map { message ->
        if (message !is UserMessageUi || message.images.isEmpty() || message.imageSources.isNotEmpty()) {
            message
        } else {
            // Legacy histories lack attachment IDs. An ambiguous match must not be guessed.
            val matches = history.filter { it.role == "user" && it.content == message.content }
            val uniqueUiText = messages.count { it is UserMessageUi && it.content == message.content } == 1
            val sources = matches.singleOrNull()?.takeIf { uniqueUiText }?.let(AgentConversationCodec::persistedImageSources)
            if (sources != null && sources.size == message.images.size) {
                message.copy(imageSources = sources, imageIsVideo = sources.map(AgentVideoCodec::isVideoSource))
            } else message
        }
    }
}
