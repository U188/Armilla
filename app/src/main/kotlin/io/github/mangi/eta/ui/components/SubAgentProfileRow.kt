package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.model.ProviderSetting
import io.github.mangi.eta.data.repository.RuntimeConfigRepository
import io.github.mangi.eta.ui.TtsModelPickerDialog
import io.github.mangi.eta.ui.haptics.TouchHaptics
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Shared gestures and model/thinking dialogs for settings and conversation controls. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SubAgentProfileRow(
    profile: SubAgentProfile,
    providers: List<ProviderSetting>,
    enabled: Boolean = true,
    settings: Boolean = false,
) {
    val view = LocalView.current
    val currentEnabled by rememberUpdatedState(enabled)
    var modelPicker by remember(profile.id) { mutableStateOf(false) }
    var thinkingPicker by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) { modelPicker = false; thinkingPicker = false } }
    val config = remember(profile.providerId, profile.modelId, profile.role, providers) {
        val provider = providers.firstOrNull { it.id == profile.providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == profile.modelId && it.isEnabled }
        if (provider == null || model == null || model.supportsSpeechSynthesis ||
            !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) null
        else runCatching { RuntimeConfigRepository.buildRuntimeConfig(provider, model) }.getOrNull()
    }
    val canThink = config != null && !profile.isMedia
    val effective = config?.let { SubAgentPreferences.applyReasoning(profile, it).effectiveReasoningEffort }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            SubAgentTaskTierButton(if (settings) "任务分工" else profile.name, profile.tier, enabled,
                { tier -> if (currentEnabled) SubAgentPreferences.update(profile.id) { it.copy(tier = tier) } },
                Modifier.weight(1f))
            Column(Modifier.weight(1f).heightIn(min = 48.dp)
                .semantics { contentDescription = "${profile.name}模型" }
                .combinedClickable(role = Role.Button, enabled = enabled, hapticFeedbackEnabled = false,
                    onClickLabel = "选择${profile.name}模型", onLongClickLabel = "调整${profile.name}思考深度",
                    onClick = { if (currentEnabled) { TouchHaptics.click(view); modelPicker = true } },
                    onLongClick = { if (currentEnabled && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } }),
                horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                Text(config?.let { it.modelDisplayName.ifBlank { it.model } } ?: if (profile.modelId.isBlank()) "无" else "模型不可用",
                    style = MiuixTheme.textStyles.body2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (config != null) Text(config.providerName + if (canThink) " · ${effective?.displayName}" else "",
                    style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (settings) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val roleMenu = rememberEtaMenuState()
                Box(Modifier.weight(1f)) {
                    TextButton("职责：${profile.roleLabel}", onClick = roleMenu::onAnchorClick,
                        modifier = Modifier.fillMaxWidth(), enabled = enabled)
                    if (enabled) EtaDropdownMenu(roleMenu.expanded, roleMenu::dismiss) {
                        listOf("implementation" to "执行", "review" to "审查／总结", "image_generation" to "图片生成", "video_generation" to "视频生成").forEach { (role, label) ->
                            TextButton(label, onClick = {
                                if (currentEnabled) SubAgentPreferences.update(profile.id) {
                                    it.withRole(role)
                                }
                                roleMenu.dismiss()
                            }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                if (!profile.isMedia) TextButton("思考：${effective?.displayName ?: "未启用"}", modifier = Modifier.weight(1f),
                    enabled = enabled && canThink,
                    onClick = { if (currentEnabled && canThink) thinkingPicker = true })
            }
        }
    }
    if (enabled && modelPicker) {
        val all = AgentModelPickerProjector.project(providers, profile.providerId, profile.modelId)
        val models = all.copy(providerGroups = all.providerGroups.map { group ->
            group.copy(models = group.models.filter { profile.acceptsModel(it.supportsImageGeneration, it.supportsVideoGeneration) })
        }.filter { it.models.isNotEmpty() })
        TtsModelPickerDialog(models, true, { modelPicker = false }, { provider, model ->
            if (currentEnabled) SubAgentPreferences.saveModel(profile.id, ModelFeatureSelection(true, provider, model))
            modelPicker = false
        }, "选择${profile.name}模型", onClearSelection = {
            if (currentEnabled) SubAgentPreferences.saveModel(profile.id, ModelFeatureSelection(true, "", ""))
            modelPicker = false
        })
    }
    if (enabled && thinkingPicker && config != null && canThink) {
        val effort = requireNotNull(effective)
        val options = config.reasoningCapabilities?.selectableEfforts.orEmpty()
        ThinkingEffortPickerDialog(true, effort, options.ifEmpty { listOf(effort) }, { thinkingPicker = false }, { next ->
            if (currentEnabled && next in options) SubAgentPreferences.update(profile.id) { it.copy(reasoning = next) }
        }, description = "${profile.name} · 仅影响此代理")
    }
}
