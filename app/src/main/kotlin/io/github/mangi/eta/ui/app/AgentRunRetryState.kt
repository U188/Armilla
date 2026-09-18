package io.github.mangi.eta.ui.app

import io.github.mangi.eta.agent.runtime.AgentEvent

/** Historical retry notices are not a live retry wait. Track the event state per run. */
internal class AgentRunRetryState {
    private val waiting = mutableSetOf<String>()

    fun accept(runId: String, event: AgentEvent) {
        when (event) {
            is AgentEvent.ModelRetryScheduled -> waiting.add(runId)
            is AgentEvent.ProviderRequestStarted,
            is AgentEvent.RunFinished,
            is AgentEvent.RunFailed -> waiting.remove(runId)
            else -> Unit
        }
    }

    fun isWaiting(runId: String): Boolean = runId in waiting
    fun clear(runId: String) { waiting.remove(runId) }
}
