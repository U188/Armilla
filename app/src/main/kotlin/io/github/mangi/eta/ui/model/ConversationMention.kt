package io.github.mangi.eta.ui.model

import io.github.mangi.eta.agent.model.AgentFileReferencePromptCodec
import io.github.mangi.eta.agent.model.MentionedConversation

internal data class ConversationMentionQuery(
    val start: Int,
    val query: String,
)

internal object ConversationMention {
    const val MAX_TRANSCRIPT_CHARS = 240_000
    const val MAX_RESULTS = 8
    const val MAX_ATTACHED = 3
    const val MAX_TOTAL_CHARS = 480_000

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

    const val OMISSION_MARKER = "\n\n[已截取：中间记录已省略，保留开头与最近记录]\n\n"

    fun transcript(
        messages: List<AgentChatMessageUi>,
        maxChars: Int = MAX_TRANSCRIPT_CHARS,
    ): String {
        if (maxChars <= 0) return ""
        val chunks = messages.mapNotNull(::formatMessage)
        if (chunks.isEmpty()) return ""
        val joined = chunks.joinToString("\n\n")
        if (joined.length <= maxChars) return joined
        val keep = (maxChars - OMISSION_MARKER.length).coerceAtLeast(0)
        if (keep == 0) return OMISSION_MARKER.trim().take(maxChars)
        val headBudget = (keep / 2).coerceAtLeast(1)
        val tailBudget = (keep - headBudget).coerceAtLeast(1)
        return (joined.take(headBudget) + OMISSION_MARKER + joined.takeLast(tailBudget)).take(maxChars)
    }

    private fun formatToolActivity(message: ToolActivityMessageUi): String = buildString {
        append("Tool ${message.toolName}: ${message.status.name}")
        message.argumentsSummary.trim().takeIf { it.isNotEmpty() }?.let {
            append("\nArguments: ").append(it)
        }
        message.command?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("\nCommand: ").append(it)
        }
        message.resultSummary?.trim()?.takeIf { it.isNotEmpty() }?.let {
            append("\nResult: ").append(it)
        }
        if (message.imageCount > 0) {
            append("\nImages: ").append(message.imageCount)
        }
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
        is ThinkingMessageUi -> message.content.trim().takeIf { it.isNotEmpty() }?.let { "Thinking: $it" }
        is ToolSummaryMessageUi -> message.tools.takeIf { it.isNotEmpty() }?.let { "Tools: ${it.joinToString()}" }
        is ToolActivityMessageUi -> formatToolActivity(message)
        is ContextCompactedMessageUi -> {
            val summary = message.summary.trim()
            if (summary.isEmpty()) "Context compressed (${message.compactedCount} messages)"
            else "Context compressed (${message.compactedCount} messages): $summary"
        }
        is SystemNoticeMessageUi -> "系统状态：${message.code.name}"
        is RunTraceMessageUi, is SuggestionChipsMessageUi -> null
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
