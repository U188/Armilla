package io.github.mangi.eta.agent.voice

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class DuplexTextStreamTest {
    private fun event(field: String, text: Any, done: Boolean = false, id: String = "") = JSONObject()
        .put("type", if (done) "response.output_text.done" else "response.output_text.delta")
        .put(field, text).put("response_id", id)

    @Test fun allFourFieldsAppendBeforeDoneIncludingWhitespace() {
        val stream = DuplexTextStream(VoiceDiagnostics("test", {}))
        assertEquals("Hello", stream.accept(event("text", "Hello"), 0))
        assertEquals("Hello ", stream.accept(event("delta", " "), 0))
        assertEquals("Hello 世", stream.accept(event("transcript", "世"), 0))
        assertEquals("Hello 世界", stream.accept(event("content", "界"), 0))
        assertEquals("Hello 世界", stream.accept(event("text", "Hello 世界", done = true), 0))
    }

    @Test fun invalidFieldsDoNotBecomeJsonOrNullText() {
        val parsed = DoubaoDuplexProtocol.outputText(JSONObject().put("text", JSONObject.NULL)
            .put("delta", JSONObject().put("secret", "no" )).put("transcript", "ok"))
        assertEquals("ok", parsed.text)
        assertEquals(3, parsed.field)
        assertEquals("", DoubaoDuplexProtocol.outputText(JSONObject().put("text", 123)).text)
        assertEquals("first", DoubaoDuplexProtocol.outputText(JSONObject().put("text", "first").put("delta", "second")).text)
    }

    @Test fun doneFallbackReplacesRatherThanDuplicatesAndEmptyDoneKeepsDeltas() {
        val stream = DuplexTextStream(VoiceDiagnostics("test", {}))
        stream.accept(event("text", "a"), 0)
        assertEquals("ab", stream.accept(event("content", "ab", done = true), 0))
        assertEquals("ab", stream.accept(event("text", "", done = true), 0))
    }

    @Test fun newResponseAndNewTurnResetButLateFirstIdDoesNotDropText() {
        val stream = DuplexTextStream(VoiceDiagnostics("test", {}))
        stream.accept(event("text", "a"), 0)
        assertEquals("ab", stream.accept(event("delta", "b", id = "r1"), 0))
        assertEquals("c", stream.accept(event("delta", "c", id = "r2"), 0))
        stream.reset()
        assertEquals("d", stream.accept(event("text", "d", id = "r2"), 0))
    }

    @Test fun diagnosticsIdentifyDoneOnlyAndNeverLogTextOrRemoteId() {
        val logs = mutableListOf<String>()
        val trace = VoiceDiagnostics("test", { logs.add(it) }, { 0 })
        val stream = DuplexTextStream(trace, { 0 })
        stream.accept(event("text", "PRIVATE_CONTENT", done = true, id = "PRIVATE_ID"), 17)
        assertTrue(logs.any { "stage=text.summary" in it && "doneOnly=1" in it && "maxQueuedMs=17" in it })
        assertTrue(logs.none { "PRIVATE" in it })
    }

    @Test fun summaryCountsAllChunksEvenWhenLogsAreSampled() {
        val logs = mutableListOf<String>()
        var now = 0L
        val stream = DuplexTextStream(VoiceDiagnostics("test", { logs.add(it) }, { now }), { now })
        stream.accept(event("text", "a"), 2)
        now = 30
        stream.accept(event("delta", "b"), 8)
        now = 60
        stream.accept(event("text", "ab", done = true), 1)
        val summary = logs.last { "stage=text.summary" in it }
        assertTrue(summary.contains("deltaEvents=2 deltaChars=2 finalChars=2 doneOnly=0"))
        assertTrue(summary.contains("streamMs=30 maxGapMs=30 maxQueuedMs=8"))
    }
}
