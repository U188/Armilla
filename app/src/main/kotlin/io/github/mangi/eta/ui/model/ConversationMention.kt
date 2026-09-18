package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.MentionedConversation

internal data class ConversationMentionQuery(
    val start: Int,
    val query: String,
)

internal object ConversationMention {
    const val MAX_TRANSCRIPT_CHARS = 8_000
    const val MAX_RESULTS = 8
    const val MAX_ATTACHED = 3
    const val MAX_TOTAL_CHARS = 16_000

    fun queryAtCursor(text: String, cursor: Int): ConversationMentionQuery? {
        val index = cursor.coerceIn(0, text.length)
        val before = text.substring(0, index)
        val at = before.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && (before[at - 1] in 'a'..'z' || before[at - 1] in 'A'..'Z' ||
            before[at - 1].isDigit() || before[at - 1] in "._%+-/@")) return null
        val query = before.substring(at + 1)
        if (query.any { it == '\n' }) return null
        return ConversationMentionQuery(start = at, query = query)
    }

    fun candidates(
        conversations: List<ConversationSummaryUi>,
        query: String,
        excludeId: String?,
        alreadyAttached: Set<String>,
        limit: Int = MAX_RESULTS,
    ): List<ConversationSummaryUi> {
        val needle = query.trim()
        return conversations.asSequence()
            .filter { it.id != excludeId && it.id !in alreadyAttached }
            .filter { needle.isEmpty() || matches(it, needle) }
            .sortedByDescending { it.updatedAtMillis }
            .take(limit.coerceAtLeast(0))
            .toList()
    }

    fun matches(conversation: ConversationSummaryUi, query: String): Boolean {
        val needle = query.trim()
        if (needle.isEmpty()) return true
        return conversation.title.contains(needle, ignoreCase = true) ||
            conversation.preview.contains(needle, ignoreCase = true)
    }

    fun transcript(
        messages: List<AgentChatMessageUi>,
        maxChars: Int = MAX_TRANSCRIPT_CHARS,
    ): String {
        if (maxChars <= 0) return ""
        val marker = "[已截取：仅保留最近记录，较早内容已省略]\n\n"
        val chunks = ArrayList<String>()
        var size = 0
        var omitted = false
        for (message in messages.asReversed()) {
            val chunk = formatMessage(message) ?: continue
            val extra = chunk.length + if (chunks.isEmpty()) 0 else 2
            if (size + extra > maxChars) {
                if (chunks.isEmpty()) chunks.add(chunk.takeLast(maxChars))
                omitted = true
                break
            }
            chunks.add(chunk)
            size += extra
        }
        val text = chunks.asReversed().joinToString("\n\n")
        return if (omitted) marker.take(maxChars) + text.takeLast((maxChars - marker.length).coerceAtLeast(0)) else text
    }

    fun remainingTranscriptBudget(already: List<PendingConversationMentionUi>): Int =
        (MAX_TOTAL_CHARS - already.sumOf { it.transcript.length }).coerceAtLeast(0)

    private fun formatMessage(message: AgentChatMessageUi): String? {
        return when (message) {
        is UserMessageUi -> {
            if (message.isResumeAfterCompress()) return null
            val parsed = AgentFileReferencePromptCodec.parse(message.content)
            buildString {
                if (parsed.conversations.isNotEmpty()) {
                    append("User mentioned conversations: ")
                    append(parsed.conversations.joinToString { it.title })
                    append('\n')
                }
                if (parsed.references.isNotEmpty()) {
                    append("User attached files: ")
                    append(parsed.references.joinToString { it.displayName })
                    append('\n')
                }
                val body = parsed.request.trim()
                if (body.isEmpty() && parsed.conversations.isEmpty() && parsed.references.isEmpty()) return null
                append("User: ")
                append(body.ifBlank { "(attachment only)" })
            }
        }
        is AgentMessageUi -> message.content.trim().takeIf { it.isNotEmpty() }?.let { "Assistant: $it" }
        // Raw tool results may be sensitive. Refer only to tool name and outcome.
        is ToolActivityMessageUi -> "Tool ${message.toolName}: ${message.status.name}（不含原始参数或结果）"
        is ContextCompactedMessageUi -> {
            val summary = message.summary.trim()
            if (summary.isEmpty()) "Context compressed (${message.compactedCount} messages)"
            else "Context compressed (${message.compactedCount} messages): $summary"
        }
        is SystemNoticeMessageUi -> "系统状态：${message.code.name}"
        is ThinkingMessageUi, is RunTraceMessageUi,
        is ToolSummaryMessageUi, is SuggestionChipsMessageUi,
        -> null
    }
    }
}

internal fun List<PendingConversationMentionUi>.toMentionedConversations(): List<MentionedConversation> =
    map { MentionedConversation(id = it.conversationId, title = it.title, transcript = it.transcript) }

/** One composer-scoped controller, passed explicitly through both Home and Chat screens. */
internal data class ConversationMentionInputUi(
    val conversations: List<ConversationSummaryUi> = emptyList(),
    val currentConversationId: String? = null,
    val pending: List<PendingConversationMentionUi> = emptyList(),
    val onAttach: (String) -> Boolean = { false },
    val onRemove: (String) -> Unit = {},
)
