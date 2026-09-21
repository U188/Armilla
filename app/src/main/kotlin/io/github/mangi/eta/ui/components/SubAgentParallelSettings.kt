package io.github.mangi.eta.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.data.model.ProviderSetting

/** All provider/model pairs, not only models already assigned to a child profile. */
@Composable
internal fun SubAgentParallelSettings(providers: List<ProviderSetting>) {
    var open by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Pair<String, String>?>(null) }
    var value by remember { mutableStateOf("") }
    var revision by remember { mutableIntStateOf(0) }
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text("设置各提供商模型并行上限") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("模型并行上限") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("同一提供商的同一模型共用上限，可同时运行多个子任务。0 表示不限，正整数不设产品档位上限；实际仍受设备和接口限流约束。保存后应用于同模型任务调度；调低上限不会取消已在执行的任务，后续任务排队等待空位。")
                val rows = providers.flatMap { provider -> provider.models.distinctBy { it.modelId }.map { provider to it } }
                LazyColumn(Modifier.heightIn(max = 400.dp)) {
                    items(rows, key = { it.first.id + ":" + it.second.modelId }) { (provider, model) ->
                        val limit = remember(provider.id, model.modelId, revision) { SubAgentPreferences.parallelLimit(provider.id, model.modelId) }
                        TextButton(onClick = { selected = provider.id to model.modelId; value = limit.toString() }, modifier = Modifier.fillMaxWidth()) {
                            Text("${provider.name} · ${model.modelId}", Modifier.weight(1f))
                            Text(if (limit == 0) "不限" else limit.toString(), Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = { open = false }) { Text("完成") } })
    selected?.let { target ->
        val number = value.toIntOrNull()?.takeIf { it >= 0 }
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("设置并行上限") },
            text = { OutlinedTextField(value, { value = it }, label = { Text("0 为不限，或输入正整数") }, singleLine = true,
                isError = number == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)) },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("取消") } },
            confirmButton = { TextButton(enabled = number != null, onClick = {
                number?.let { SubAgentPreferences.saveParallelLimit(target.first, target.second, it); revision++; selected = null }
            }) { Text("保存") } })
    }
}
