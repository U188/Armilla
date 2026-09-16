package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.model.AgentChatMessageUi
import io.github.mangi.eta.ui.model.AgentChatUiState
import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ContextCompactedMessageUi
import io.github.mangi.eta.ui.model.RunTraceMessageUi
import io.github.mangi.eta.ui.model.SuggestionChipsMessageUi
import io.github.mangi.eta.ui.model.SystemNoticeMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolSummaryMessageUi
import io.github.mangi.eta.ui.model.UserMessageUi
import io.github.mangi.eta.ui.model.isSteerSupplement

/** 以用户轮次为边界同步裁剪展示消息与模型上下文。 */
internal object AgentConversationRevisionReducer {
    data class Boundary(
        val userMessage: UserMessageUi,
        val userMessageIndex: Int,
        val historyPrefix: List<AgentModelClient.ConversationMessage>,
        val laterTurnCount: Int,
        val contextWasCompacted: Boolean,
    )

    data class BranchPrefix(
        val messages: List<AgentChatMessageUi>,
        val history: List<AgentModelClient.ConversationMessage>,
    )

    fun boundary(state: AgentChatUiState, targetMessageId: String): Boundary? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val userMessage = state.messages[userMessageIndex] as UserMessageUi
        val userMessageIndices = state.messages.indices.filter { state.messages[it] is UserMessageUi }
        val targetUserOrdinal = userMessageIndices.indexOf(userMessageIndex)
        if (targetUserOrdinal < 0) return null

        val historyUserIndices = state.history.indices.filter { state.history[it].role == "user" }
        // checkpoint 超限时只保留末尾上下文，因此展示轮次和 history 必须从尾部对齐。
        val retainedUserOrdinal = historyUserIndices.size - (userMessageIndices.size - targetUserOrdinal)
        val historyIndex = historyUserIndices.getOrNull(retainedUserOrdinal)
        val compacted = historyIndex == null

        return Boundary(
            userMessage = userMessage,
            userMessageIndex = userMessageIndex,
            historyPrefix = historyIndex?.let(state.history::take).orEmpty(),
            laterTurnCount = userMessageIndices.size - targetUserOrdinal - 1,
            contextWasCompacted = compacted,
        )
    }

    fun deleteFromTurn(state: AgentChatUiState, targetMessageId: String): AgentChatUiState? {
        val boundary = boundary(state, targetMessageId) ?: return null
        return state.copy(
            messages = state.messages.take(boundary.userMessageIndex),
            history = boundary.historyPrefix,
            messageEdit = null,
        )
    }

    /**
     * 从目标消息分出一条独立会话：保留该消息及之前的展示内容，
     * 模型上下文截到同一轮结束（助手消息含本轮回复；用户消息只含该条提问）。
     */
    fun branchPrefix(state: AgentChatUiState, targetMessageId: String): BranchPrefix? {
        val targetIndex = state.messages.indexOfFirst { it.id == targetMessageId }
        if (targetIndex < 0) return null
        val userMessageIndex = (targetIndex downTo 0).firstOrNull { index ->
            state.messages[index] is UserMessageUi
        } ?: return null
        val userMessageIndices = state.messages.indices.filter { state.messages[it] is UserMessageUi }
        val targetUserOrdinal = userMessageIndices.indexOf(userMessageIndex)
        if (targetUserOrdinal < 0) return null

        val historyUserIndices = state.history.indices.filter { state.history[it].role == "user" }
        val retainedUserOrdinal = historyUserIndices.size - (userMessageIndices.size - targetUserOrdinal)
        val historyUserIndex = historyUserIndices.getOrNull(retainedUserOrdinal)
        val messages = state.messages.take(targetIndex + 1)
        val history = if (historyUserIndex == null) {
            reconstructHistory(messages)
        } else if (state.messages[targetIndex] is UserMessageUi) {
            state.history.take(historyUserIndex + 1)
        } else {
            val nextUser = historyUserIndices.getOrNull(retainedUserOrdinal + 1)
            if (nextUser != null) state.history.take(nextUser) else state.history
        }
        return BranchPrefix(messages = messages, history = history)
    }

    fun outboundHistory(state: AgentChatUiState): List<AgentModelClient.ConversationMessage> {
        val targetId = state.messageEdit?.targetMessageId ?: return state.history
        return boundary(state, targetId)?.historyPrefix ?: state.history
    }

    fun visibleMessagesForEdit(
        messages: List<AgentChatMessageUi>,
        targetMessageId: String?,
    ): List<AgentChatMessageUi> {
        if (targetMessageId == null) return messages
        val targetIndex = messages.indexOfFirst { it.id == targetMessageId }
        return if (targetIndex < 0) messages else messages.take(targetIndex + 1)
    }

    private fun reconstructHistory(
        messages: List<AgentChatMessageUi>,
    ): List<AgentModelClient.ConversationMessage> = messages.mapNotNull { message ->
        when (message) {
            is UserMessageUi -> AgentModelClient.ConversationMessage(
                role = "user",
                content = message.content,
            )
            is AgentMessageUi -> message.content.takeIf { it.isNotBlank() }?.let { content ->
                AgentModelClient.ConversationMessage(role = "assistant", content = content)
            }
            else -> null
        }
    }

    /**
     * 暂停/结束任务后，屏幕上已写出的助手正文必须进模型历史。
     * 否则下一轮请求看不到刚才的完整回答。
     */
    fun commitVisibleAssistantIntoHistory(
        history: List<AgentModelClient.ConversationMessage>,
        messages: List<AgentChatMessageUi>,
    ): List<AgentModelClient.ConversationMessage> {
        val lastUserIndex = messages.indexOfLast { message ->
            message is UserMessageUi && !message.isSteerSupplement()
        }
        val partial = messages
            .drop((lastUserIndex + 1).coerceAtLeast(0))
            .filterIsInstance<AgentMessageUi>()
            .lastOrNull { it.content.isNotBlank() }
            ?: return history
        return historyWithTrailingPartial(history, partial)
    }

    fun historyWithTrailingPartial(
        history: List<AgentModelClient.ConversationMessage>,
        partial: AgentMessageUi,
    ): List<AgentModelClient.ConversationMessage> {
        val partialMessage = AgentModelClient.ConversationMessage(
            role = "assistant",
            content = partial.content,
        )
        val last = history.lastOrNull()
        return if (last?.role == "assistant") {
            if (last.content == partial.content) history else history.dropLast(1) + partialMessage
        } else {
            history + partialMessage
        }
    }

}

internal fun AgentChatMessageUi.withId(id: String): AgentChatMessageUi = when (this) {
    is UserMessageUi -> copy(id = id)
    is AgentMessageUi -> copy(id = id)
    is SystemNoticeMessageUi -> copy(id = id)
    is ThinkingMessageUi -> copy(id = id)
    is RunTraceMessageUi -> copy(id = id)
    is ToolSummaryMessageUi -> copy(id = id)
    is ContextCompactedMessageUi -> copy(id = id)
    is ToolActivityMessageUi -> copy(id = id)
    is SuggestionChipsMessageUi -> copy(id = id)
}

internal fun AgentChatMessageUi.rewritePaths(rewrite: (String) -> String): AgentChatMessageUi = when (this) {
    is UserMessageUi -> copy(
        content = rewrite(content),
        imageSources = imageSources.map(rewrite),
    )
    is AgentMessageUi -> copy(content = rewrite(content))
    is ThinkingMessageUi -> copy(content = rewrite(content))
    is ToolActivityMessageUi -> copy(
        argumentsSummary = rewrite(argumentsSummary),
        command = command?.let(rewrite),
        resultSummary = resultSummary?.let(rewrite),
    )
    else -> this
}

internal fun AgentModelClient.ConversationMessage.rewritePaths(
    rewrite: (String) -> String,
): AgentModelClient.ConversationMessage = copy(
    content = rewrite(content),
    contentJson = rewrite(contentJson),
)

