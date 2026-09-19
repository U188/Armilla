package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.core.AndroidAgentLogger
import java.util.UUID

/** Numeric metadata only. Bounded counters, monotonic timing, thread-safe throttling. */
internal class VoiceDiagnostics(
    private val kind: String,
    private val sink: (String) -> Unit = AndroidAgentLogger::info,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    val id: String = UUID.randomUUID().toString().take(8)
    private val started = clock()
    private data class Counter(var count: Long = 0, var bytes: Long = 0, var last: Long? = null)
    private val counters = linkedMapOf<String, Counter>()
    private var finished = false

    @Synchronized
    fun mark(stage: String, vararg values: Pair<String, Number>, sampled: Boolean = false, bytes: Long = 0) {
        if (finished) return
        val key = stage.take(64).replace(Regex("[^a-zA-Z0-9_.-]"), "_")
        if (key !in counters && counters.size >= 96) return
        val counter = counters.getOrPut(key) { Counter() }
        counter.count++
        counter.bytes += bytes.coerceAtLeast(0)
        val now = clock()
        if (sampled && counter.last != null && now - counter.last!! < 5_000) return
        counter.last = now
        val fields = values.take(20).joinToString(" ") { (name, value) ->
            "${name.take(32).replace(Regex("[^a-zA-Z0-9_]"), "_")}=$value"
        }
        runCatching { sink("VoiceDiag id=$id kind=$kind tMs=${now - started} stage=$key count=${counter.count} bytes=${counter.bytes} $fields") }
    }

    @Synchronized
    fun finish() {
        if (finished) return
        mark("summary", "elapsedMs" to (clock() - started))
        runCatching { sink("VoiceDiag id=$id totals=" + counters.entries.joinToString(" ") { (k,v) -> "$k:${v.count}/${v.bytes}" }) }
        finished = true
    }
}
