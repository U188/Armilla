package io.github.mangi.eta.agent.delegation

import org.junit.Assert.*
import org.junit.Test

class SubAgentEventJournalTest {
    @Test fun heartbeatDoesNotExtendEffectiveProgress() {
        var now = 0L
        val journal = SubAgentEventJournal(3) { now }
        journal.mark("started", progress = true)
        now = 360_000
        journal.mark("heartbeat")
        assertEquals(0, journal.lastProgressMs)
        assertEquals(360_000, journal.lastHeartbeatMs)
        assertEquals(0, journal.page(0, 10).getLong("last_progress_ms"))
    }

    @Test fun overflowReportsMissingEventsAndNextCursor() {
        var now = 0L
        val journal = SubAgentEventJournal(2) { now++ }
        journal.mark("queued")
        journal.mark("started")
        journal.mark("tool_started", "private_tool", data = true)
        val page = journal.page(0, 50)
        assertTrue(page.getBoolean("truncated"))
        assertEquals(2, page.getLong("oldest_seq"))
        assertEquals(3, page.getLong("next_seq"))
        assertEquals("other", page.getJSONArray("events").getJSONObject(1).getString("tool"))
        val empty = journal.page(3, 50)
        assertEquals(3, empty.getLong("next_seq"))
        assertFalse(empty.getBoolean("truncated"))
    }

    @Test fun waitingCursorWakesForNewEvent() {
        val journal = SubAgentEventJournal()
        val worker = Thread { Thread.sleep(30); journal.mark("provider_response", data = true) }
        worker.start()
        val page = journal.awaitPage(0, 5, 1000)
        worker.join(1000)
        assertEquals(1, page.getJSONArray("events").length())
    }
}
