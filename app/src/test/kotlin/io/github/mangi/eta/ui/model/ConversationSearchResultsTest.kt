package io.github.mangi.eta.ui.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSearchResultsTest {
    private val chats = listOf(
        summary("a", "Kotlin UI", "screen"),
        summary("b", "Kotlin server", "backend"),
        summary("c", "Notes", "Android UI"),
    )
    private fun summary(id: String, title: String, preview: String) = ConversationSummaryUi(
        id = id, title = title, preview = preview, timeLabel = "", mode = ConversationModeUi.Chat,
    )
    private fun state(query: String) = ConversationPaneUiState(chats, null, query)

    @Test fun typingAndDeletingImmediatelyDeriveFromTheFullSource() {
        var pane = state("Kotlin UI")
        assertEquals(listOf("a"), pane.searchResults().map { it.id })
        pane = pane.copy(selectedConversationId = "a")
        pane = pane.copy(searchQuery = "Kotlin")
        assertEquals(listOf("a", "b"), pane.searchResults().map { it.id })
        pane = pane.copy(searchQuery = "")
        assertEquals(chats, pane.searchResults())
        assertEquals(chats, pane.conversations)
    }

    @Test fun noMatchCanBeClearedWithoutAnotherAction() {
        val pane = state("missing")
        assertEquals(emptyList<ConversationSummaryUi>(), pane.searchResults())
        assertEquals(chats, pane.copy(searchQuery = "  ").searchResults())
    }

    @Test fun matchesTitleAndPreviewIgnoringCaseAndOuterSpaces() {
        assertEquals(listOf("a", "c"), state(" ui ").searchResults().map { it.id })
    }

    @Test fun searchDoesNotEscapeTheSelectedFolder() {
        val pane = state("").copy(conversations = listOf(chats[1]), selectedFolderId = "work")
        assertEquals(listOf(chats[1]), pane.copy(searchQuery = "kotlin").searchResults())
        assertEquals(emptyList<ConversationSummaryUi>(), pane.copy(searchQuery = "ui").searchResults())
        assertEquals(listOf(chats[1]), pane.copy(searchQuery = "").searchResults())
    }
}
