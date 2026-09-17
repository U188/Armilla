package io.github.mangi.eta.agent.model

/** Bounded metadata only: never retain event payloads, headers, URLs or credentials. */
internal class SummaryRequestDiagnostics(
    private val clock: () -> Long = System::nanoTime,
) {
    private val started = clock()
    private var firstEvent: Long? = null
    private var headersAt: Long? = null
    private var firstText: Long? = null
    private var lastEvent: Long? = null
    private var lastText: Long? = null
    private var events = 0L
    private var textChars = 0L
    private var thinkingChars = 0L
    private var httpCode: Int? = null
    private var completedEvent = false
    private var responseReturned = false
    private var lastProgress = started

    @Synchronized
    fun record(event: ProviderEvent): String? {
        val now = clock()
        if (firstEvent == null) firstEvent = now
        lastEvent = now
        events++
        return when (event) {
            is ProviderEvent.ResponseHeaders -> {
                httpCode = event.httpCode
                if (headersAt == null) {
                    headersAt = now
                    "response_headers"
                } else null
            }
            is ProviderEvent.BlockDelta -> {
                when (event.kind) {
                    AssistantBlockKind.TEXT -> if (event.delta.isNotEmpty()) {
                        textChars += event.delta.length.toLong()
                        lastText = now
                        if (firstText == null) {
                            firstText = now
                            return "first_text"
                        }
                    }
                    AssistantBlockKind.THINKING -> thinkingChars += event.delta.length.toLong()
                    else -> Unit
                }
                null
            }
            is ProviderEvent.Completed -> { completedEvent = true; null }
            else -> null
        }
    }

    @Synchronized fun returned() { responseReturned = true }

    /** Rate-limited progress; no timer or thread is created by this tracker. */
    @Synchronized fun progressDue(): Boolean {
        val now = clock()
        if (responseReturned || now - lastProgress < 15_000_000_000L) return false
        lastProgress = now
        return true
    }

    @Synchronized fun snapshot(): String {
        val now = clock()
        fun sinceStart(value: Long?) = value?.let { ((it - started) / 1_000_000).coerceAtLeast(0).toString() } ?: "unknown"
        fun idle(value: Long?) = value?.let { ((now - it) / 1_000_000).coerceAtLeast(0).toString() } ?: "unknown"
        val stage = when {
            responseReturned -> "response_returned"
            completedEvent -> "completion_event_seen"
            firstText != null -> "text_stream_seen"
            thinkingChars > 0 -> "thinking_only"
            headersAt != null -> "headers_seen_no_text"
            events > 0 -> "events_seen_no_headers_or_text"
            else -> "no_provider_events"
        }
        return "elapsed_ms=${sinceStart(now)}, observed_stage=$stage, http=${httpCode ?: "unknown"}, " +
            "first_event_ms=${sinceStart(firstEvent)}, headers_ms=${sinceStart(headersAt)}, " +
            "first_text_ms=${sinceStart(firstText)}, last_event_ago_ms=${idle(lastEvent)}, " +
            "last_text_ago_ms=${idle(lastText)}, events=$events, text_delta_chars=$textChars, " +
            "thinking_delta_chars=$thinkingChars"
    }
}
