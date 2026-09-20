package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.delegation.SubAgentProfile
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.*
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun SubAgentSettingsScreen(onBack: () -> Unit) {
    val profiles by remember { SubAgentPreferences.profilesFlow() }.collectAsState(initial = SubAgentPreferences.profiles())
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    var rename by remember { mutableStateOf<SubAgentProfile?>(null) }
    var delete by remember { mutableStateOf<SubAgentProfile?>(null) }
    var name by remember { mutableStateOf("") }
    MiuixScaffoldPage(title = "子代理", onBack = onBack) {
        profiles.forEach { profile ->
            item(key = profile.id) {
                Card(Modifier.padding(horizontal = 12.dp).padding(bottom = 12.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, modifier = Modifier.weight(1f), style = MiuixTheme.textStyles.body1)
                            Switch(checked = profile.enabled, onCheckedChange = { enabled ->
                                SubAgentPreferences.update(profile.id) { it.copy(enabled = enabled) }
                            })
                            val menu = rememberEtaMenuState()
                            Box {
                                TextButton("⋯", onClick = menu::onAnchorClick)
                                EtaDropdownMenu(menu.expanded, menu::dismiss) {
                                    TextButton("重命名", modifier = Modifier.fillMaxWidth(), onClick = {
                                        name = profile.name; rename = profile; menu.dismiss()
                                    })
                                    TextButton("删除", modifier = Modifier.fillMaxWidth(), onClick = { delete = profile; menu.dismiss() })
                                }
                            }
                        }
                        SubAgentProfileRow(profile, providers, settings = true)
                    }
                }
            }
        }
        item {
            TextButton("添加子代理", modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                onClick = { SubAgentPreferences.add() })
            Text("更改下次运行生效", modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                style = MiuixTheme.textStyles.footnote1, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
    }
    rename?.let { profile ->
        WindowDialog(show = true, title = "重命名", onDismissRequest = { rename = null }) {
            Column {
                TextField(value = name, onValueChange = { name = it.take(80) }, label = "名称", singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                MiuixDialogActions(confirmText = "保存", confirmEnabled = name.isNotBlank(), onCancel = { rename = null },
                    onConfirm = { SubAgentPreferences.update(profile.id) { it.copy(name = name.trim()) }; rename = null },
                    modifier = Modifier.padding(top = 16.dp))
            }
        }
    }
    delete?.let { profile ->
        WindowDialog(show = true, title = "删除“${profile.name}”？", onDismissRequest = { delete = null }) {
            MiuixDialogActions(confirmText = "删除", onCancel = { delete = null },
                onConfirm = { SubAgentPreferences.remove(profile.id); delete = null })
        }
    }
}
