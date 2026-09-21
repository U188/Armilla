package io.github.mangi.eta.ui.app

import org.junit.Assert.*
import org.junit.Test

class TimedOutChildVisibilityTest {
    @Test fun hideAfter30SecondsAndDuplicateTerminalEventsDoNotReviveIt() {
        var now=0L; val p=TimedOutChildVisibility{now}
        assertTrue(p.observe("run","task","timed_out"));assertEquals(30_000L,p.remaining("run","task"))
        now=29_999;assertTrue(p.observe("run","task","timed_out"))
        now=30_000;assertFalse(p.observe("run","task","timed_out"))
        now=90_000;assertFalse(p.observe("run","task","timed_out"))
        assertTrue(p.observe("another-run","task","timed_out"))
    }
    @Test fun awaitingDecisionAndActiveTaskNeverExpire() {
        var now=0L; val p=TimedOutChildVisibility{now}
        assertTrue(p.observe("run","task","awaiting_decision"))
        now=100_000;assertTrue(p.observe("run","task","awaiting_decision"))
        assertTrue(p.observe("run","task","running"))
    }
}
