package io.github.mangi.eta.agent.model

/** Lossless ranges in a summary-only text projection, never edits to live history or archived JSON. */
internal object AgentSummaryTextFragments {
    data class Range(val start: Int, val end: Int)

    fun split(
        source: String,
        maxFragments: Int,
        checkCancellation: () -> Unit,
        fits: (Range) -> Boolean,
    ): List<Range> {
        require(maxFragments > 0) { "摘要分块过多，请使用更大窗口的摘要模型；原历史保持不变" }
        require(source.isNotEmpty()) { "没有可分片的历史文本" }
        val result = mutableListOf<Range>()
        var start = 0
        while (start < source.length) {
            checkCancellation()
            require(result.size < maxFragments) { "摘要分块过多，请使用更大窗口的摘要模型；原历史保持不变" }
            val remainder = Range(start, source.length)
            if (fits(remainder)) {
                result += remainder
                break
            }
            var low = 1
            var high = source.codePointCount(start, source.length)
            var acceptedEnd = start
            while (low <= high) {
                checkCancellation()
                val mid = low + (high - low) / 2
                val end = source.offsetByCodePoints(start, mid)
                if (fits(Range(start, end))) {
                    acceptedEnd = end
                    low = mid + 1
                } else high = mid - 1
            }
            require(acceptedEnd > start) { "摘要模型输入预算不足以容纳历史文本片段；原历史保持不变" }
            val range = Range(start, acceptedEnd)
            // The caller's budget may include structured projection and metadata. Recheck the final candidate.
            require(fits(range)) { "摘要文本片段仍超过输入预算；原历史保持不变" }
            result += range
            start = acceptedEnd
        }
        return result
    }
}
