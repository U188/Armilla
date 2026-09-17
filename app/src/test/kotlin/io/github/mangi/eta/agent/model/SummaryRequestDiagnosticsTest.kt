package io.github.mangi.eta.agent.model

import org.junit.Assert.*
import org.junit.Test

class SummaryRequestDiagnosticsTest {
    private class Clock {
        var nanos = 0L
        fun advance(ms: Long) { nanos += ms * 1_000_000 }
    }

    @Test fun noEventsAreUnknownNotZeroLatency() {
        val clock = Clock()
        val trace = SummaryRequestDiagnostics { clock.nanos }
        clock.advance(120_000)
        val value = trace.snapshot()
        assertTrue(value.contains("elapsed_ms=120000"))
        assertTrue(value.contains("observed_stage=no_provider_events"))
        assertTrue(value.contains("first_text_ms=unknown"))
        assertTrue(value.contains("last_event_ago_ms=unknown"))
    }

    @Test fun requestStartedDoesNotPretendServerHeadersArrived() {
        val trace = SummaryRequestDiagnostics { 0L }
        trace.record(ProviderEvent.RequestStarted)
        assertTrue(trace.snapshot().contains("observed_stage=events_seen_no_headers_or_text"))
        assertTrue(trace.snapshot().contains("headers_ms=unknown"))
    }

    @Test fun headersAndFirstTextAreOneTimeMilestonesAndZeroIsValid() {
        val clock = Clock()
        val trace = SummaryRequestDiagnostics { clock.nanos }
        assertEquals("response_headers", trace.record(ProviderEvent.ResponseHeaders(200)))
        assertNull(trace.record(ProviderEvent.ResponseHeaders(200)))
        clock.advance(50)
        assertEquals("first_text", trace.record(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "ab")))
        clock.advance(20)
        assertNull(trace.record(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "c")))
        clock.advance(30)
        val value = trace.snapshot()
        assertTrue(value.contains("headers_ms=0"))
        assertTrue(value.contains("first_text_ms=50"))
        assertTrue(value.contains("last_text_ago_ms=30"))
        assertTrue(value.contains("last_event_ago_ms=30"))
        assertTrue(value.contains("text_delta_chars=3"))
    }

    @Test fun emptyDeltasAreNotFirstTextAndThinkingIsSeparate() {
        val trace = SummaryRequestDiagnostics { 0L }
        assertNull(trace.record(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "")))
        trace.record(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 1, "secret"))
        val value = trace.snapshot()
        assertTrue(value.contains("observed_stage=thinking_only"))
        assertTrue(value.contains("first_text_ms=unknown"))
        assertTrue(value.contains("thinking_delta_chars=6"))
        assertTrue(value.contains("text_delta_chars=0"))
        assertFalse(value.contains("secret"))
    }

    @Test fun textIdleIsNotResetByUnrelatedEvents() {
        val clock = Clock()
        val trace = SummaryRequestDiagnostics { clock.nanos }
        trace.record(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "a"))
        clock.advance(100)
        trace.record(ProviderEvent.BlockStart(AssistantBlockKind.THINKING, 1))
        clock.advance(50)
        val value = trace.snapshot()
        assertTrue(value.contains("last_text_ago_ms=150"))
        assertTrue(value.contains("last_event_ago_ms=50"))
    }

    @Test fun completionEventAndProviderReturnAreDistinct() {
        val trace = SummaryRequestDiagnostics { 0L }
        trace.record(ProviderEvent.Completed("private provider message"))
        assertTrue(trace.snapshot().contains("observed_stage=completion_event_seen"))
        trace.returned()
        assertTrue(trace.snapshot().contains("observed_stage=response_returned"))
        assertFalse(trace.snapshot().contains("private provider message"))
    }

    @Test fun nonStreamingResponseDoesNotInventTextEvents() {
        val trace = SummaryRequestDiagnostics { 0L }
        trace.returned()
        assertTrue(trace.snapshot().contains("observed_stage=response_returned"))
        assertTrue(trace.snapshot().contains("first_text_ms=unknown"))
        assertTrue(trace.snapshot().contains("text_delta_chars=0"))
    }

    @Test fun progressIsRateLimitedAndStopsAfterReturn() {
        val clock = Clock()
        val trace = SummaryRequestDiagnostics { clock.nanos }
        clock.advance(14_999)
        assertFalse(trace.progressDue())
        clock.advance(1)
        assertTrue(trace.progressDue())
        assertFalse(trace.progressDue())
        clock.advance(15_000)
        assertTrue(trace.progressDue())
        trace.returned()
        clock.advance(15_000)
        assertFalse(trace.progressDue())
    }

    @Test fun countersAreThreadSafeAndDoNotRetainPayloads() {
        val trace = SummaryRequestDiagnostics { 0L }
        val threads = List(4) {
            Thread {
                repeat(250) {
                    trace.record(ProviderEvent.BlockDelta(AssistantBlockKind.TEXT, 0, "sensitive"))
                    trace.snapshot()
                }
            }.apply { start() }
        }
        threads.forEach { it.join(5_000); assertFalse(it.isAlive) }
        val value = trace.snapshot()
        assertTrue(value.contains("events=1000"))
        assertTrue(value.contains("text_delta_chars=9000"))
        assertFalse(value.contains("sensitive"))
        assertTrue(value.length < 1024)
    }
}
