package io.github.mangi.eta.agent.model

/** Presentation-only filtering before events are persisted/replayed. Provider history is untouched. */
internal class AgentContinuationReasoning {
    private var continuing = false
    private var hiddenIndex: Int? = null
    private val visibleBlocks = linkedMapOf<Int, StringBuilder>()

    fun beginRequest(continuing: Boolean) {
        this.continuing = continuing
        hiddenIndex = null
        visibleBlocks.clear()
    }

    fun visibleEvent(event: ProviderEvent): ProviderEvent? {
        if (event is ProviderEvent.RequestStarted) {
            // A transport retry is a new response, with its own provider block indexes.
            hiddenIndex = null
            visibleBlocks.clear()
        }
        val kind: AssistantBlockKind
        val index: Int
        when (event) {
            is ProviderEvent.BlockStart -> { kind = event.kind; index = event.index }
            is ProviderEvent.BlockDelta -> { kind = event.kind; index = event.index }
            is ProviderEvent.BlockEnd -> { kind = event.kind; index = event.index }
            else -> return event
        }
        if (kind != AssistantBlockKind.THINKING) return event
        if (continuing && hiddenIndex == null) hiddenIndex = index
        if (continuing && index == hiddenIndex) return null
        val content = visibleBlocks.getOrPut(index) { StringBuilder() }
        when (event) {
            is ProviderEvent.BlockDelta -> content.append(event.delta)
            is ProviderEvent.BlockEnd -> {
                if (event.replaceContent || content.isEmpty()) {
                    content.setLength(0)
                    content.append(event.content)
                }
            }
            else -> Unit
        }
        return event
    }

    fun visibleCompletedReasoning(providerReasoning: String): String =
        if (!continuing) providerReasoning else visibleBlocks.values.joinToString("\n") { it.toString() }
}
