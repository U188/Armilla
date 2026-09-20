package io.github.mangi.eta.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.mangi.eta.agent.delegation.SubAgentContextStats

internal data class AgentContextTelemetry(
    val children: List<SubAgentContextStats> = emptyList(),
    val mainModelName: String = "",
    val compactingModelName: String = "",
)
internal val LocalAgentContextTelemetry = staticCompositionLocalOf { AgentContextTelemetry() }
internal fun SubAgentContextStats.contextLabel(): String {
    val roleLabel = when (role) {
        "implementation" -> "实现"
        "review" -> "审查"
        "summary" -> "总结"
        else -> "研究"
    }
    return "$modelName（$roleLabel ${taskId.take(4)}）"
}
