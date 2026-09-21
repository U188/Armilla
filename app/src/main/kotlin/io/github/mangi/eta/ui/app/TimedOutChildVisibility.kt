package io.github.mangi.eta.ui.app

/** UI-only expiry; never deletes coordinator results, resumable context or worktrees. */
internal class TimedOutChildVisibility(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val deadlines = mutableMapOf<Pair<String, String>, Long>()
    fun observe(runId: String, taskId: String, status: String): Boolean {
        val key = runId to taskId
        if (status != "timed_out") { deadlines.remove(key); return true }
        val deadline = deadlines.getOrPut(key) { now() + 30_000 }
        return now() < deadline
    }
    fun remaining(runId: String, taskId: String): Long =
        ((deadlines[runId to taskId] ?: now()) - now()).coerceAtLeast(0)
}
