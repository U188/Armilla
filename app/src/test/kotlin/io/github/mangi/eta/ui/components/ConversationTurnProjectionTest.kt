package io.github.mangi.eta.ui.components

import io.github.mangi.eta.ui.model.AgentMessageUi
import io.github.mangi.eta.ui.model.ThinkingMessageUi
import io.github.mangi.eta.ui.model.ToolActivityMessageUi
import io.github.mangi.eta.ui.model.ToolActivityStatusUi
import io.github.mangi.eta.ui.model.UserMessageUi
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTurnProjectionTest {
    @Test fun toolGroupsAndHiddenResumeDoNotMisalignTargets() {
        val entries = listOf(
            UserMessageUi("user-1", "first"),
            ThinkingMessageUi("thinking", "reasoning", false),
            ToolActivityMessageUi("tool-1", "search", ToolActivityStatusUi.Success, "args"),
            ToolActivityMessageUi("tool-2", "search", ToolActivityStatusUi.Success, "args"),
            UserMessageUi("user-1-supplement-resume", "resume"),
            AgentMessageUi("answer-1", "answer"),
            UserMessageUi("user-1-supplement-1", "extra instruction"),
            AgentMessageUi("answer-1b", "continuation"),
            UserMessageUi("user-2", "second"),
            AgentMessageUi("answer-2", "answer"),
        ).toTimelineEntries()
        assertEquals(7, entries.size)
        assertEquals(listOf(0, 5), entries.turnStartIndices())
        assertEquals(5, conversationTurnTarget(entries.turnStartIndices(), 2, entries.size,
            ConversationNavigationDirection.Down, false))
    }
    @Test fun upwardJumpSkipsAllSupplementsWithinPreviousTurn() {
        val entries = listOf(
            UserMessageUi("user-1", "first"),
            AgentMessageUi("answer-1", "long answer"),
            UserMessageUi("user-1-supplement-1", "extra instruction"),
            AgentMessageUi("answer-1b", "continuation"),
            UserMessageUi("user-1-supplement-2", "another instruction"),
            AgentMessageUi("answer-1c", "final answer"),
            UserMessageUi("user-2", "second"),
            AgentMessageUi("answer-2", "answer"),
        ).toTimelineEntries()
        assertEquals(listOf(0, 6), entries.turnStartIndices())
        for (firstVisible in listOf(6, 7)) {
            assertEquals(0, conversationTurnTarget(entries.turnStartIndices(), firstVisible,
                entries.size, ConversationNavigationDirection.Up, false))
        }
    }

    @Test fun orphanAnswerAndEmptyHistoryHaveNoInventedTurns() {
        assertEquals(emptyList<Int>(), listOf(AgentMessageUi("a", "answer")).toTimelineEntries().turnStartIndices())
        assertEquals(emptyList<Int>(), emptyList<AgentTimelineEntry>().turnStartIndices())
    }
}
