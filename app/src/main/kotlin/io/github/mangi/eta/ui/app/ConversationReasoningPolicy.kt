package io.github.mangi.eta.ui.app

import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort

/** OFF is an explicit application preference; model defaults must never silently enable it. */
internal object ConversationReasoningPolicy {
    fun resolve(effort: ReasoningEffort?, capabilities: ModelReasoningCapabilities?): ReasoningEffort {
        if (effort == null || effort == ReasoningEffort.OFF) return ReasoningEffort.OFF
        return capabilities?.normalize(effort) ?: ReasoningEffort.OFF
    }
}
