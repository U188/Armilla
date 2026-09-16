package io.github.mangi.eta.agent.model

/** Provider block indexes restart at zero on every HTTP request, not on every UI round.
 * Keep indexes unique across interrupted requests; only a directly continued text block
 * may reuse its index, with authoritative replacements scoped to the new suffix.
 */
internal class AgentContinuationBlocks {
    private data class Block(val kind: AssistantBlockKind, val index: Int, val prefix: String, var content: String)
    private val current = mutableMapOf<Pair<AssistantBlockKind, Int>, Block>()
    private var round: Int? = null
    private var nextIndex = 0
    private var last: Block? = null
    private var carry: Block? = null

    fun beginRequest(continuing: Boolean) {
        carry = last?.takeIf { continuing && it.kind == AssistantBlockKind.TEXT }
        current.clear()
        if (!continuing) last = null
    }

    fun map(attemptRound: Int, event: ProviderEvent): ProviderEvent {
        if (round != attemptRound) {
            round = attemptRound
            nextIndex = 0
            current.clear()
            last = null
            carry = null
        }
        val kind: AssistantBlockKind
        val index: Int
        when (event) {
            is ProviderEvent.BlockStart -> { kind = event.kind; index = event.index }
            is ProviderEvent.BlockDelta -> { kind = event.kind; index = event.index }
            is ProviderEvent.BlockEnd -> { kind = event.kind; index = event.index }
            else -> return event
        }
        val block = current.getOrPut(kind to index) {
            val previous = carry.takeIf { current.isEmpty() && kind == AssistantBlockKind.TEXT }
            carry = null
            Block(kind, previous?.index ?: nextIndex++, previous?.content.orEmpty(), previous?.content.orEmpty())
        }
        last = block
        return when (event) {
            is ProviderEvent.BlockStart -> event.copy(index = block.index)
            is ProviderEvent.BlockDelta -> {
                block.content += event.delta
                event.copy(index = block.index)
            }
            is ProviderEvent.BlockEnd -> {
                if (event.replaceContent) block.content = block.prefix + event.content
                event.copy(index = block.index, content = block.prefix + event.content)
            }
            else -> event
        }
    }
}
