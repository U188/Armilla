package io.github.mangi.eta.agent.voice

import org.json.JSONObject

/** One response accumulator. Diagnostics contain counts/timings only, never text or IDs. */
internal class DuplexTextStream(
    private val diagnostic: VoiceDiagnostics,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    var text: String = ""
        private set
    private var responseId = ""
    private var turn = 0
    private var deltas = 0
    private var chars = 0
    private var firstDeltaAt: Long? = null
    private var lastDeltaAt: Long? = null
    private var maxGapMs = 0L
    private var maxQueuedMs = 0L
    private var started = clock()
    private var complete = false

    @Synchronized fun reset() {
        if (!complete && (deltas > 0 || text.isNotEmpty())) summary(0)
        turn++
        text = ""
        responseId = ""
        deltas = 0
        chars = 0
        firstDeltaAt = null
        lastDeltaAt = null
        maxGapMs = 0
        maxQueuedMs = 0
        started = clock()
        complete = false
    }

    @Synchronized fun accept(event: JSONObject, queuedMs: Long): String {
        val done = event.optString("type") == "response.output_text.done"
        val id = (event.opt("response_id") as? String).orEmpty()
        // A late first ID must not erase earlier ID-less deltas.
        if (id.isNotEmpty() && responseId.isNotEmpty() && id != responseId) reset()
        if (id.isNotEmpty()) responseId = id
        val fragment = DoubaoDuplexProtocol.outputText(event)
        val now = clock()
        maxQueuedMs = maxOf(maxQueuedMs, queuedMs.coerceAtLeast(0))
        if (done) {
            // done is an authoritative full result, not another delta.
            if (fragment.text.isNotEmpty()) text = fragment.text
        } else {
            deltas++
            chars += fragment.text.length
            if (fragment.text.isNotEmpty()) {
                if (firstDeltaAt == null) firstDeltaAt = now
                lastDeltaAt?.let { maxGapMs = maxOf(maxGapMs, now - it) }
                lastDeltaAt = now
                text += fragment.text
            }
        }
        diagnostic.mark(if (done) "text.done" else "text.delta",
            "turn" to turn, "field" to fragment.field, "chunkChars" to fragment.text.length,
            "totalChars" to text.length, "queuedMs" to queuedMs.coerceAtLeast(0),
            sampled = !done && deltas > 1)
        if (fragment.field == 0) diagnostic.mark("text.empty", "done" to if (done) 1 else 0, sampled = true)
        if (done) { summary(1); complete = true }
        return text
    }

    @Synchronized fun finish() {
        if (!complete && (deltas > 0 || text.isNotEmpty())) summary(0)
        complete = true
    }

    private fun summary(done: Int) {
        diagnostic.mark("text.summary", "turn" to turn, "done" to done,
            "deltaEvents" to deltas, "deltaChars" to chars, "finalChars" to text.length,
            "doneOnly" to if (done == 1 && chars == 0 && text.isNotEmpty()) 1 else 0,
            "firstDeltaMs" to (firstDeltaAt?.minus(started) ?: -1L),
            "streamMs" to (firstDeltaAt?.let { (lastDeltaAt ?: it) - it } ?: 0L),
            "maxGapMs" to maxGapMs, "maxQueuedMs" to maxQueuedMs)
    }
}
