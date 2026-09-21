package io.github.mangi.eta.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
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
    var rolePicker by remember(profile.id) { mutableStateOf(false) }
    LaunchedEffect(enabled) { if (!enabled) { modelPicker = false; thinkingPicker = false; rolePicker = false } }
    val config = remember(profile.providerId, profile.modelId, profile.role, providers) {
        val provider = providers.firstOrNull { it.id == profile.providerId && it.isEnabled }
        val model = provider?.models?.firstOrNull { it.id == profile.modelId && it.isEnabled }
        if (provider == null || model == null || model.supportsSpeechSynthesis ||
            !profile.acceptsModel(model.supportsImageGeneration, model.supportsVideoGeneration)) null
        else runCatching { RuntimeConfigRepository.buildRuntimeConfig(provider, model) }.getOrNull()
    }
    val canThink = config != null && !profile.isMedia
    val effective = config?.let { SubAgentPreferences.applyReasoning(profile, it).effectiveReasoningEffort }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (settings) {
            // Full-width model line, rather than squeezing the primary information beside a large button.
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .semantics { contentDescription = "${profile.name}模型" }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled, role = Role.Button, hapticFeedbackEnabled = false,
                    onClickLabel = "选择${profile.name}模型", onLongClickLabel = "调整${profile.name}思考深度",
                    onClick = { if (currentEnabled) { TouchHaptics.click(view); modelPicker = true } },
                    onLongClick = { if (currentEnabled && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } }),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(config?.let { it.modelDisplayName.ifBlank { it.model } } ?: if (profile.modelId.isBlank()) "选择模型" else "模型不可用",
                        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(config?.providerName ?: "尚未配置模型", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Box(Modifier.weight(1f)) {
                    SubAgentChoiceField("职责", profile.roleLabel, enabled, "选择${profile.name}职责",
                        { if (currentEnabled) rolePicker = !rolePicker })
                    if (enabled) DropdownMenu(rolePicker, { rolePicker = false },
                        modifier = Modifier.width(220.dp).selectableGroup(), shape = RoundedCornerShape(12.dp),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 0.dp, shadowElevation = 3.dp) {
                        listOf("implementation" to "执行", "review" to "审查／总结", "image_generation" to "图片生成", "video_generation" to "视频生成").forEach { (role, label) ->
                            SubAgentSelectionItem(label, profile.role == role) {
                                if (currentEnabled) SubAgentPreferences.update(profile.id) { it.withRole(role) }
                                rolePicker = false
                            }
                        }
                    }
                }
                if (profile.supportsTaskTier) SubAgentTaskTierButton("任务分工", profile.tier, enabled,
                    { tier -> if (currentEnabled) SubAgentPreferences.update(profile.id) { it.copy(tier = tier) } }, Modifier.weight(1f))
            }
            if (!profile.isMedia) Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { contentDescription = "调整${profile.name}思考深度" }
                .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                    enabled = enabled && canThink, role = Role.Button,
                    onClick = { if (currentEnabled && canThink) thinkingPicker = true }),
                verticalAlignment = Alignment.CenterVertically) {
                Text("思考深度", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(effective?.displayName ?: "未启用", style = MaterialTheme.typography.bodyMedium,
                    color = if (canThink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f))
                Spacer(Modifier.width(8.dp))
                Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.KeyboardArrowRight, null,
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (profile.supportsTaskTier) SubAgentTaskTierButton(profile.name, profile.tier, enabled,
                    { tier -> if (currentEnabled) SubAgentPreferences.update(profile.id) { it.copy(tier = tier) } }, Modifier.weight(1f))
                else Column(Modifier.weight(1f).heightIn(min = 60.dp).padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text(profile.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(profile.roleLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(Modifier.weight(1f).heightIn(min = 60.dp)
                    .semantics { contentDescription = "${profile.name}模型" }
                    .combinedClickable(interactionSource = remember { MutableInteractionSource() }, indication = null,
                        role = Role.Button, enabled = enabled, hapticFeedbackEnabled = false,
                        onClickLabel = "选择${profile.name}模型", onLongClickLabel = "调整${profile.name}思考深度",
                        onClick = { if (currentEnabled) { TouchHaptics.click(view); modelPicker = true } },
                        onLongClick = { if (currentEnabled && canThink) { TouchHaptics.longPress(view); thinkingPicker = true } }),
                    horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.Center) {
                    Text(config?.let { it.modelDisplayName.ifBlank { it.model } } ?: if (profile.modelId.isBlank()) "无" else "模型不可用",
                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (config != null) Text(config.providerName + if (canThink) " · ${effective?.displayName}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
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
        }, highlightSelection = true)
    }
    if (enabled && thinkingPicker && config != null && canThink) {
        val effort = requireNotNull(effective)
        val options = config.reasoningCapabilities?.selectableEfforts.orEmpty()
        ThinkingEffortPickerDialog(true, effort, options.ifEmpty { listOf(effort) }, { thinkingPicker = false }, { next ->
            if (currentEnabled && next in options) SubAgentPreferences.update(profile.id) { it.copy(reasoning = next) }
        }, description = "${profile.name} · 仅影响此代理")
    }
}
