package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun ConversationCollaborationDialog(
    show: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (!show) return
    val view = LocalView.current
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    WindowDialog(show = true, title = "本会话协作", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchPreference(
                    title = "自动委派",
                    summary = "主代理分配任务并审核结果",
                    checked = enabled,
                    insideMargin = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    onCheckedChange = { TouchHaptics.click(view); onEnabledChange(it) },
                )
                HorizontalDivider()
                for (slot in 0..1) {
                    val selection = SubAgentPreferences.selection(slot)
                    val model = AgentModelPickerProjector.project(providers, selection.providerId, selection.modelId).selectedModel
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(if (slot == 0) "实现" else "审查 / 总结", style = MiuixTheme.textStyles.body1)
                            Text(if (slot == 0) "限定工作区内修改" else "只读检查与整理",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(model?.displayName ?: "未配置", style = MiuixTheme.textStyles.body2,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            model?.let {
                                Text(it.providerName, style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text("最多并行 2 项 · 更改下次运行生效\n模型配置：设置 → 模型功能 → 子代理",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(text = "完成", modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = { TouchHaptics.click(view); onDismiss() })
        }
    }
}
