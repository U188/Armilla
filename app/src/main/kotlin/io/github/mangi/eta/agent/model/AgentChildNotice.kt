package io.github.mangi.eta.agent.model

/**
 * 注入侧的预算与合并：同一回合内多个子代理完成时只注入 **一条** user 消息。
 *
 * 多发消息会打爆 prompt 缓存，因此这里对整批通知设总字数上限；超限只注入前若干条，
 * 并明确告知还有多少条未注入（主代理用 get_task_result 按 task_id 取回），不静默丢弃。
 */
internal object AgentChildNotice {
    const val BUDGET_CHARS = 8_000
    private const val MORE_HINT = "[还有 %d 条子任务通知未注入；需要时用 get_task_result 按 task_id 取回]"
    /** 通知文本的任务标识前缀；注入侧据此解除对应任务的轮询限制。 */
    const val TASK_ID_PREFIX = "task_id="

    fun merge(notices: List<String>): String {
        val kept = mutableListOf<String>()
        var used = 0
        for (notice in notices) {
            // 至少保留第一条，避免超长单条通知变成空注入。
            if (kept.isNotEmpty() && used + notice.length > BUDGET_CHARS) break
            kept += notice
            used += notice.length
        }
        val omitted = notices.size - kept.size
        return buildString {
            append(kept.joinToString("\n\n"))
            if (omitted > 0) {
                append('\n')
                append(MORE_HINT.format(omitted))
            }
        }
    }
}
