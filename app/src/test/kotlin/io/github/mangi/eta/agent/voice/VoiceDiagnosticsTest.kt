package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class VoiceDiagnosticsTest {
    @Test fun throttlesButRetainsAllCountsAndBytes() {
        val logs = mutableListOf<String>()
        var time = 0L
        val trace = VoiceDiagnostics("test", { logs.add(it) }, { time })
        repeat(100) { trace.mark("capture", sampled = true, bytes = 640) }
        assertEquals(1, logs.size)
        time = 5000
        trace.mark("capture", sampled = true, bytes = 640)
        assertTrue(logs.last().contains("count=101 bytes=64640"))
        trace.finish()
        assertTrue(logs.last().contains("capture:101/64640"))
        val size = logs.size
        trace.finish()
        trace.mark("late")
        assertEquals(size, logs.size)
    }

    @Test fun brokenLogSinkCannotBreakVoice() {
        val trace = VoiceDiagnostics("test", { error("disk unavailable") })
        trace.mark("start")
        trace.finish()
    }
}
