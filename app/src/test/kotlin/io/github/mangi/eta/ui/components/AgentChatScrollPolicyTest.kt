package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentChatScrollPolicyTest {
    @Test
    fun networkCompletionKeepsFollowingUntilRenderedTailSettles() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun draggingInterruptsCompletionFollowing() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = true,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completionDoesNotPullReaderBackFromHistory() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = false,
                isUserDragging = false,
                isBottomSettling = true,
            )
        )
    }

    @Test
    fun completedContentExpansionDoesNotFollowBottom() {
        assertFalse(
            resolveBottomFollowEnabled(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun streamingTailGrowthFollowsBottom() {
        assertTrue(
            resolveBottomFollowEnabled(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationDoesNotUseStreamingInitialJump() {
        assertFalse(
            shouldRequestInitialBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun completedConversationSnapsToBottomOnOpen() {
        assertTrue(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = true,
            )
        )
    }

    @Test
    fun emptyConversationDoesNotSnapToBottom() {
        assertFalse(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = false,
            )
        )
    }

    @Test
    fun scrollToMessageSkipsConversationBottomSnap() {
        assertFalse(
            shouldSnapConversationToBottom(
                isStreaming = false,
                keepBottomAnchored = true,
                isUserDragging = false,
                hasItems = true,
                scrollToMessageId = "msg-1",
            )
        )
    }

    @Test
    fun conversationBottomSnapAlignsLastItemWithoutAnimation() {
        assertEquals(
            BottomFollowDecision(scrollByPx = -120),
            resolveConversationBottomSnap(
                bottomItemIndex = 8,
                lastVisibleIndex = 8,
                lastVisibleBottom = 880,
                viewportEnd = 1000,
            ),
        )
    }

    @Test
    fun conversationBottomSnapRequestsLastItemWhenNotVisible() {
        assertEquals(
            BottomFollowDecision(requestIndex = 8),
            resolveConversationBottomSnap(
                bottomItemIndex = 8,
                lastVisibleIndex = 2,
                lastVisibleBottom = 400,
                viewportEnd = 1000,
            ),
        )
    }

    @Test
    fun streamingConversationRequestsInitialBottom() {
        assertTrue(
            shouldRequestInitialBottom(
                isStreaming = true,
                keepBottomAnchored = true,
                isUserDragging = false,
            )
        )
    }

    @Test
    fun contentGrowthDoesNotDisableBottomFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = false,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun draggingAwayFromBottomDisablesFollowing() {
        assertFalse(
            resolveKeepBottomAnchored(
                current = true,
                isUserDragging = true,
                isAtBottom = false,
            )
        )
    }

    @Test
    fun landingOnBottomAfterUserScrollEnablesFollowing() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
            )
        )
    }

    @Test
    fun reachingBottomEnablesFollowingAgain() {
        assertTrue(
            resolveKeepBottomAnchored(
                current = false,
                isUserDragging = false,
                isAtBottom = true,
            )
        )
    }

    @Test
    fun growingTailOnlyScrollsByTheOverflowDistance() {
        assertEquals(
            BottomFollowDecision(scrollByPx = 24),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = 1024,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }

    @Test
    fun largeAppendRequestsBottomOnlyWhenSentinelLeftTheViewport() {
        assertEquals(
            BottomFollowDecision(requestIndex = 8),
            resolveBottomFollowDecision(
                enabled = true,
                bottomItemIndex = 8,
                sentinelBottom = null,
                viewportEnd = 1000,
                lastVisibleIndex = 6,
            ),
        )
    }

    @Test
    fun disabledFollowingNeverMovesTheList() {
        assertEquals(
            BottomFollowDecision(),
            resolveBottomFollowDecision(
                enabled = false,
                bottomItemIndex = 8,
                sentinelBottom = 1100,
                viewportEnd = 1000,
                lastVisibleIndex = 8,
            ),
        )
    }
}
