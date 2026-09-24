package io.github.mangi.eta.agent.delegation

/**
 * 子代理终态的最小快照，用于「完成即主动通知」。
 *
 * 只承载主代理做决策所需的状态与已截断的结果正文；子代理原始证据仍按敏感内容处理，
 * 通知文本由运行时投递给主代理的模型上下文，**不产生落盘投影事件**。
 */
internal data class SubAgentCompletion(
    val taskId: String,
    val agentId: String,
    val agentName: String,
    val role: String,
    val status: String,
    val errorCode: String,
    val result: String,
    val workspacePath: String = "",
    /** 阶段 0 度量基线：子代理自身的计费响应次数与压缩次数（不含正文）。 */
    val responseCount: Int = 0,
    val compactionCount: Int = 0,
    val continuationCount: Int = 0,
)
/** 子代理报告的有界截断：结论通常在尾部，因此保头 + 保尾，而不是直接砍尾。 */

internal object SubAgentReport {
    const val LIMIT = 16_000
    private const val HEAD_CHARS = 9_600
    private const val OMITTED_MARKER = "\n[结果中段已省略，仅保留开头与结尾]\n"

    /** 保头 + 保尾，且标记计入总预算，保证输出不超过 [LIMIT]。 */
    fun truncate(text: String): String {
        if (text.length <= LIMIT) return text
        val tailChars = (LIMIT - HEAD_CHARS - OMITTED_MARKER.length).coerceAtLeast(1)
        val head = text.substring(0, HEAD_CHARS)
        val tail = text.substring(text.length - tailChars)
        return head + OMITTED_MARKER + tail
    }
}

/**
 * 主动通知文案。一条通知对应一个终态子任务；同一轮多条通知由注入侧合并。
 *
 * 文案刻意不包含子代理的原始工具调用与凭据：正文就是子代理自己的报告（已按
 * [SubAgentReport] 截断），主代理仍可用 get_task_result 重新取回。
 */
internal object SubAgentNotice {
    fun text(completion: SubAgentCompletion): String = buildString {
        append("task_id=").append(completion.taskId)
        append(" agent=").append(completion.agentName.ifBlank { completion.agentId })
        append(" role=").append(completion.role)
        append(" status=").append(completion.status)
        if (completion.errorCode.isNotBlank()) append(" error_code=").append(completion.errorCode)
        if (completion.workspacePath.isNotBlank()) append(" workspace_path=").append(completion.workspacePath)
        append('\n')
        append(completion.result.ifBlank { "(子代理未返回结果正文)" })
    }
}
