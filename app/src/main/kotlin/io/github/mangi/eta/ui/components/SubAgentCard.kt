package io.github.mangi.eta.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.delegation.SubAgentContextStats
import java.text.NumberFormat
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 会话内子任务卡片：默认折叠，折叠态只保留代理名 / 角色 / 状态，展开后才显示上下文与用量。
 *
 * [SubAgentContextStats] 没有耗时字段，因此这里不显示耗时，避免编造数据。终态子任务由
 * `AgentAppState` 在 30 秒后从 telemetry 移除，本组件不参与该生命周期。
 */
@Composable
internal fun SubAgentCard(
    stats: SubAgentContextStats,
    modifier: Modifier = Modifier,
    defaultCollapsed: Boolean = true,
) {
    var expanded by rememberSaveable(stats.taskId) { mutableStateOf(!defaultCollapsed) }
    val fallbackName = stringResource(R.string.subtask_unknown_agent)
    val roleLabel = subAgentRoleLabel(stats.role)
    val statusLabel = stats.contextStatusLabel()
    val title = buildString {
        append(stats.agentName.ifBlank { fallbackName })
        if (roleLabel.isNotBlank()) append(" · ").append(roleLabel)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 4.dp)
            .squircleSurface(
                color = MiuixTheme.colorScheme.surface,
                cornerRadius = 14.dp,
            )
            .squircleBorder(
                width = 0.5.dp,
                color = MiuixTheme.colorScheme.outline.copy(alpha = 0.50f),
                cornerRadius = 14.dp,
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 13.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.CallSplit,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
                tint = if (stats.isCompacting) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = statusLabel,
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
            )
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                contentDescription = stringResource(
                    if (expanded) R.string.subtask_collapse else R.string.subtask_expand,
                ),
                modifier = Modifier.size(14.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = fadeIn() + expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ),
            exit = fadeOut() + shrinkVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                )
            ),
        ) {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 13.dp)
                        .height(0.5.dp)
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.45f)),
                )
                Column(modifier = Modifier.padding(top = 6.dp)) {
                    SubAgentDetailRow(
                        label = stringResource(R.string.subtask_detail_identity),
                        value = stats.contextLabel(),
                    )
                    SubAgentDetailRow(
                        label = stringResource(R.string.subtask_detail_model),
                        value = stats.modelName.ifBlank { stats.model },
                    )
                    SubAgentDetailRow(
                        label = stringResource(R.string.subtask_detail_context),
                        value = subAgentContextSummary(stats),
                    )
                    SubAgentDetailRow(
                        label = stringResource(R.string.subtask_detail_tokens),
                        value = "${formatSubAgentTokens(stats.inputTokens)} / " +
                            formatSubAgentTokens(stats.outputTokens),
                    )
                    SubAgentDetailRow(
                        label = stringResource(R.string.subtask_detail_compactions),
                        value = stats.compactionCount.toString(),
                    )
                }
            }
        }
    }
}

@Composable
private fun SubAgentDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 13.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            maxLines = 1,
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MiuixTheme.textStyles.footnote1,
            color = MiuixTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun subAgentRoleLabel(role: String): String = stringResource(
    when (role) {
        "implementation" -> R.string.subtask_role_implementation
        "review" -> R.string.subtask_role_review
        "summary" -> R.string.subtask_role_summary
        "image_generation" -> R.string.subtask_role_image_generation
        "video_generation" -> R.string.subtask_role_video_generation
        else -> R.string.subtask_role_research
    },
)

/** 上下文占用；缺少窗口大小时只显示占用值，缺少占用值时不显示任何编造数字。 */
private fun subAgentContextSummary(stats: SubAgentContextStats): String {
    val tokens = stats.contextTokens ?: return "—"
    val window = stats.contextWindow?.takeIf { it > 0 } ?: return formatSubAgentTokens(tokens.toLong())
    return "${formatSubAgentTokens(tokens.toLong())} / ${formatSubAgentTokens(window.toLong())}"
}

private fun formatSubAgentTokens(value: Long): String {
    val absolute = kotlin.math.abs(value)
    val divisor = when {
        absolute >= 1_000_000L -> 1_000_000.0
        absolute >= 1_000L -> 1_000.0
        else -> return NumberFormat.getIntegerInstance().format(value)
    }
    val suffix = if (divisor == 1_000_000.0) "M" else "K"
    val formatted = NumberFormat.getNumberInstance().apply {
        minimumFractionDigits = 0
        maximumFractionDigits = 2
        isGroupingUsed = false
    }.format(value / divisor)
    return "$formatted$suffix"
}
