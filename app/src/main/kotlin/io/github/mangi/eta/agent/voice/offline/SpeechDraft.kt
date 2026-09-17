package io.github.mangi.eta.agent.voice.offline

/** Replace only the current dictation at the captured selection, not the user's existing draft. */
internal class SpeechDraft(text: String, start: Int, end: Int) {
    private val before = text.take(minOf(start, end).coerceIn(0, text.length))
    private val after = text.drop(maxOf(start, end).coerceIn(0, text.length))
    var expectedText: String = text
        private set
    var cursor: Int = start
        private set

    fun accept(current: String, recognized: String): String? {
        if (current != expectedText) return null // User typed/deleted/sent; never overwrite that edit.
        expectedText = before + recognized + after
        cursor = before.length + recognized.length
        return expectedText
    }
}
