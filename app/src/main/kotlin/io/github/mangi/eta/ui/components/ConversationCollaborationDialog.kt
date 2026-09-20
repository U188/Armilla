package io.github.mangi.eta.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
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
    var editingSlot by remember { mutableStateOf<Int?>(null) }
    var overrides by remember { mutableStateOf((0 until SubAgentPreferences.SLOT_COUNT).map(SubAgentPreferences::reasoning)) }
    // Use the same capability resolver as runtime, without resolving credentials or refreshing OAuth.
    val configs = remember(providers) {
        (0 until SubAgentPreferences.SLOT_COUNT).associateWith { slot ->
            val selection = SubAgentPreferences.selection(slot)
            val provider = providers.firstOrNull { it.id == selection.providerId && it.isEnabled }
            val model = provider?.models?.firstOrNull { it.id == selection.modelId && it.isEnabled }
            if (provider == null || model == null || model.supportsSpeechSynthesis ||
                model.supportsImageGeneration || model.supportsVideoGeneration) null
            else runCatching { RuntimeConfigRepository.buildRuntimeConfig(provider, model) }.getOrNull()
        }
    }
    WindowDialog(show = editingSlot == null, title = "本会话协作", onDismissRequest = onDismiss) {
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
                for (slot in SubAgentPreferences.displayOrder) {
                    val selection = SubAgentPreferences.selection(slot)
                    val model = AgentModelPickerProjector.project(providers, selection.providerId, selection.modelId).selectedModel
                    val config = configs[slot]
                    val effective = config?.let { base ->
                        overrides[slot]?.let { base.reasoningCapabilities?.normalize(it) }
                            ?: base.effectiveReasoningEffort
                    }
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .clickable(enabled = config != null, role = Role.Button,
                            onClickLabel = "调整${SubAgentPreferences.label(slot)}思考深度") {
                            TouchHaptics.click(view)
                            editingSlot = slot
                        }.padding(vertical = 6.dp), verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(SubAgentPreferences.label(slot), style = MiuixTheme.textStyles.body1)
                            Text(if (SubAgentPreferences.role(slot) == "implementation") "限定工作区内修改" else "只读检查与整理",
                                style = MiuixTheme.textStyles.footnote1,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        }
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                            Text(model?.displayName ?: "未配置", style = MiuixTheme.textStyles.body2,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            model?.let {
                                Text(listOfNotNull(it.providerName, effective?.let { effort -> "思考：${effort.displayName}" }).joinToString(" · "), style = MiuixTheme.textStyles.footnote1,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Text("点击模型行调整子代理思考深度\n最多并行 2 项 · 更改下次运行生效\n模型配置：设置 → 模型功能 → 子代理",
                    style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp))
            }
            TextButton(text = "完成", modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = { TouchHaptics.click(view); onDismiss() })
        }
    }
    editingSlot?.let { slot ->
        val config = configs[slot]
        if (config != null) {
            val effort = overrides[slot]?.let { config.reasoningCapabilities?.normalize(it) }
                ?: config.effectiveReasoningEffort
            val options = config.reasoningCapabilities?.selectableEfforts.orEmpty()
            ThinkingEffortPickerDialog(
                show = true,
                effort = effort,
                options = options.ifEmpty { listOf(effort) },
                description = "${SubAgentPreferences.label(slot)} · ${config.modelDisplayName.ifBlank { config.model }}\n" +
                    "仅影响此子代理槽位，下次运行生效" +
                    if (options.size <= 1) "\n该模型没有可切换的思考档位" else "",
                onDismiss = { editingSlot = null },
                onEffortChange = { next ->
                    if (next in options) {
                        SubAgentPreferences.saveReasoning(slot, next)
                        overrides = overrides.toMutableList().also { it[slot] = next }
                    }
                },
            )
        } else {
            androidx.compose.runtime.LaunchedEffect(slot) { editingSlot = null }
        }
    }

}
