package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.data.repository.ProviderRepository
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
    taskRunning: Boolean = false,
) {
    if (!show) return
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val profiles by remember { SubAgentPreferences.profilesFlow() }.collectAsState(initial = SubAgentPreferences.profiles())
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp - 280).coerceIn(80, 360).dp
    val currentRunning by rememberUpdatedState(taskRunning)
    WindowDialog(show = true, title = "本会话协作", onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().alpha(if (taskRunning) 0.38f else 1f)
            .pointerInput(taskRunning) {
                if (taskRunning) awaitPointerEventScope {
                    while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                }
            }, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.heightIn(max = listMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchPreference(title = "自动委派", checked = enabled, enabled = !taskRunning,
                    insideMargin = PaddingValues(0.dp),
                    onCheckedChange = { if (!currentRunning) onEnabledChange(it) })
                profiles.forEach { profile ->
                    key(profile.id) {
                        SubAgentProfileRow(profile, providers, enabled = !taskRunning)
                    }
                }
                Text("单击选模型 · 长按调整思考深度", style = MiuixTheme.textStyles.footnote1,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            TextButton("完成", modifier = Modifier.fillMaxWidth(), enabled = !taskRunning,
                onClick = { if (!currentRunning) onDismiss() })
        }
    }
}
