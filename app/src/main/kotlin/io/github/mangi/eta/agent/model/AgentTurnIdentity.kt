package io.github.mangi.eta.agent.model

/** Stable turn identity is metadata, never provider input. Legacy records are upgraded without guessing tool results as turns. */
internal object AgentTurnIdentity {
    const val JSON_KEY = "eta_turn_id"

    fun migrate(history: List<AgentModelClient.ConversationMessage>): List<AgentModelClient.ConversationMessage> {
        var current = ""
        return history.mapIndexed { index, message ->
            if (AgentContextCompactor.isCompressionSummary(message)) return@mapIndexed message.copy(turnId = "")
            when {
                message.turnId.isNotBlank() -> current = message.turnId
                message.role == "user" && !AgentContextCompactor.isSteeringUserMessage(message) ->
                    current = history.asSequence().drop(index + 1)
                        .takeWhile { it.role != "user" || AgentContextCompactor.isSteeringUserMessage(it) }
                        .map { it.turnId }.firstOrNull { it.isNotBlank() } ?: java.util.UUID.randomUUID().toString()
                current.isBlank() -> current = java.util.UUID.randomUUID().toString()
            }
            message.copy(turnId = current)
        }
    }
}
