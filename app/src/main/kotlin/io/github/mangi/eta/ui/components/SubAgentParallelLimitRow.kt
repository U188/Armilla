package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentParallelModel
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.ui.haptics.TouchHaptics

/** Limits belong to this conversation's provider/API model pool, not the global preferences. */
@Composable
internal fun SubAgentParallelLimitRow(profile: SubAgentProfile, config: AgentModelClient.ModelConfig?, enabled: Boolean = true) {
    val editor = LocalConversationSubAgentEditor.current
    val view = LocalView.current
    val usable = enabled && editor?.enabled == true && config != null && config.providerId.isNotBlank() && config.model.isNotBlank()
    val currentUsable by rememberUpdatedState(usable)
    key(editor, profile.id, profile.providerId, profile.modelId, profile.role, config?.providerId, config?.model, usable) {
        var open by remember { mutableStateOf(false) }
        var value by remember { mutableStateOf("") }
        val ownerConfig = if (editor != null) {
            val flow = remember(editor) { editor.repository.flow(editor.owner) }
            val current by flow.collectAsState(initial = editor.repository.snapshot(editor.owner))
            current
        } else null
        val limit = config?.let { cfg ->
            ownerConfig?.parallelLimit(SubAgentParallelModel(cfg.providerId, cfg.model))
        } ?: 1
        SubAgentSettingRow("并行上限", when {
            config == null -> if (profile.modelId.isBlank()) "未选择模型" else "模型不可用"
            limit == 0 -> "不限"
            else -> limit.toString()
        }, Icons.Rounded.CallSplit, "设置${profile.name}并行上限", enabled = usable,
            onClick = {
                if (currentUsable && config != null) {
                    TouchHaptics.click(view)
                    value = limit.toString()
                    open = true
                }
            })
        if (open && usable && config != null) {
            val number = value.trim().toIntOrNull()?.takeIf { it >= 0 }
            AlertDialog(onDismissRequest = { open = false }, title = { Text("${profile.name} · 并行上限") },
                text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${config.providerName} · ${config.modelDisplayName.ifBlank { config.model }}")
                    OutlinedTextField(value, { if (currentUsable) value = it }, label = { Text("0 为不限，或输入正整数") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, isError = number == null, enabled = usable,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Text("同一会话下相同提供商/API 模型共用此上限；0 表示不限。调低不会取消正在执行的任务。")
                } },
                dismissButton = { TextButton(onClick = { open = false }) { Text("取消") } },
                confirmButton = { TextButton(enabled = usable && number != null, onClick = {
                    if (currentUsable && number != null) {
                        editor?.saveParallelLimit(profile.id, profile.providerId, profile.modelId, config.model, number)
                        open = false
                    }
                }) { Text("保存") } })
        }
    }
}
