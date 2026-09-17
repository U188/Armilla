package io.github.mangi.eta.ui.components

import org.junit.Assert.*
import org.junit.Test

class StreamingMarkdownCacheTest {
    @Test fun conversationSwitchPreservesStateAndDoesNotMixMessageIds() {
        val cache = StreamingMarkdownCache()
        val a = cache.forConversation("A")
        val state = StreamingMarkdownState()
        a["message"] = state
        val b = cache.forConversation("B")
        assertNull(b["message"])
        assertSame(a, cache.forConversation("A"))
        assertSame(state, cache.forConversation("A")["message"])
    }

    @Test fun evictionIsBoundedAndKeepsRecentlyVisitedConversation() {
        val cache = StreamingMarkdownCache(2)
        val a = cache.forConversation("A")
        val b = cache.forConversation("B")
        cache.forConversation("A")
        cache.forConversation("C")
        assertSame(a, cache.forConversation("A"))
        assertNotSame(b, cache.forConversation("B"))
    }
}
