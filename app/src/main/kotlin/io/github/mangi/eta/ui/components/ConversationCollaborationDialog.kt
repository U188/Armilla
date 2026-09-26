package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.haptics.TouchHaptics

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConversationCollaborationDialog(
    show: Boolean,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    taskRunning: Boolean = false,
) {
    if (!show) return
    val editor = LocalConversationSubAgentEditor.current
    val current = if (editor != null) {
        val flow = remember(editor) { editor.repository.flow(editor.owner) }
        val state by flow.collectAsState(initial = editor.repository.snapshot(editor.owner))
        state
    } else null
    val canChange = editor?.enabled == true && !taskRunning
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val maximumHeight = (LocalConfiguration.current.screenHeightDp - 64).coerceAtLeast(240).dp
    val view = LocalView.current
    CompositionLocalProvider(LocalRippleConfiguration provides null) {
    WithoutPressRipple {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Surface(Modifier.padding(horizontal = 16.dp).widthIn(max = 420.dp).fillMaxWidth().heightIn(max = maximumHeight),
                shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp)) {
                    Text("本会话协作", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 18.dp))
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                        .alpha(if (canChange) 1f else 0.38f)) {
                        if (editor == null) Text("请先选择会话；未选择会话时不可编辑。")
                        else if (!canChange) Text("主代理尚未停止，暂不可编辑本会话配置。")
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
                            .toggleable(value = current?.enabled == true, enabled = canChange, role = Role.Switch,
                                onValueChange = { if (editor?.enabled == true && !taskRunning) {
                                    TouchHaptics.click(view); editor.setEnabled(it)
                                } }), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("自动委派", style = MaterialTheme.typography.bodyLarge)
                                Text("按职责自动分配任务", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface)
                            }
                            Switch(checked = current?.enabled == true, enabled = canChange, onCheckedChange = null)
                        }
                        HorizontalDivider(Modifier.padding(top = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        current?.profiles?.forEach { profile ->
                            key(editor, profile.id) {
                                SubAgentProfileRow(profile, providers, enabled = canChange)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))
                            }
                        }
                        Text("点按模型切换 · 长按调整思考", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { TouchHaptics.click(view); onDismiss() }) { Text("完成") }
                    }
                }
            }
        }
    }
    }
}
