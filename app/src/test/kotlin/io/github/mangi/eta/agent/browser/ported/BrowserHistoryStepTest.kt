package io.github.mangi.eta.agent.browser.ported

import io.github.mangi.eta.agent.browser.ported.browser.BrowserHistoryStep
import org.junit.Assert.*
import org.junit.Test

class BrowserHistoryStepTest {
    @Test fun ianaReturnsToExampleNotBlank() {
        val entries = listOf("about:blank", "https://example.com/", "https://www.iana.org/help/example-domains")
        val index = requireNotNull(BrowserHistoryStep.targetIndex(2, entries.size, -1))
        assertEquals("https://example.com/", entries[index])
        assertEquals(2, BrowserHistoryStep.targetIndex(index, entries.size, 1))
    }

    @Test fun boundaryDoesNotReloadOrWrap() {
        assertNull(BrowserHistoryStep.targetIndex(0, 3, -1))
        assertNull(BrowserHistoryStep.targetIndex(2, 3, 1))
        assertNull(BrowserHistoryStep.targetIndex(-1, 0, 1))
    }

    @Test fun duplicateUrlsStillTraverseByIndex() {
        assertEquals(1, BrowserHistoryStep.targetIndex(2, 4, -1))
        assertEquals(3, BrowserHistoryStep.targetIndex(2, 4, 1))
    }

    @Test(expected = IllegalArgumentException::class)
    fun historyActionCannotSkipMultipleEntries() {
        BrowserHistoryStep.targetIndex(2, 3, -2)
    }
}
