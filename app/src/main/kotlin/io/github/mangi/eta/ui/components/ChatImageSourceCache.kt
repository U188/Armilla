package io.github.mangi.eta.ui.components

/** Bounded per-screen cache; snapshots may be collected on different background workers. */
internal class ChatImageSourceCache(
    private val capacity: Int = 256,
    private val parse: (String) -> List<String> = ::collectMarkdownImageSources,
) {
    private data class Entry(val content: String, val sources: List<String>)
    private val entries = LinkedHashMap<String, Entry>(16, 0.75f, true)

    @Synchronized
    fun sources(messageId: String, content: String): List<String> {
        entries[messageId]?.takeIf { it.content == content }?.let {
            StreamPerformanceDiagnostics.record("gallery.hit")
            return it.sources
        }
        val sources = if (content.contains("![")) {
            StreamPerformanceDiagnostics.measure("gallery.parse", content.length.toLong()) { parse(content) }
        } else {
            StreamPerformanceDiagnostics.record("gallery.skip")
            emptyList()
        }
        entries[messageId] = Entry(content, sources)
        while (entries.size > capacity.coerceAtLeast(1)) {
            entries.entries.iterator().apply { next(); remove() }
        }
        return sources
    }
}
