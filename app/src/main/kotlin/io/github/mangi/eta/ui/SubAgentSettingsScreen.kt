package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.agent.delegation.SubAgentPreferences
import io.github.mangi.eta.agent.model.ModelFeatureSelection
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference

@Composable
internal fun SubAgentSettingsScreen(onBack: () -> Unit) {
    var selections by remember { mutableStateOf((0..1).map(SubAgentPreferences::selection)) }
    var editing by remember { mutableStateOf<Int?>(null) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    MiuixScaffoldPage(title = "子代理", onBack = onBack) {
        item {
            Text("当前聊天模型担任主代理，自动委派适合并行的任务，并审核子代理的结果。最多同时运行两个只读子代理，子代理不能继续委派。长按聊天中的模型选择器，可切换本会话协作开关。",
                Modifier.padding(24.dp))
            Card(Modifier.padding(horizontal = 12.dp)) {
                selections.forEachIndexed { index, selected ->
                    val model = AgentModelPickerProjector.project(providers, selected.providerId, selected.modelId).selectedModel
                    ArrowPreference(title = "子代理 ${index + 1}",
                        summary = model?.let { "${it.providerName} / ${it.displayName}" } ?: "未配置",
                        onClick = { editing = index })
                    if (selected.modelId.isNotBlank()) {
                        ArrowPreference(title = "清除子代理 ${index + 1}", onClick = {
                            val empty = ModelFeatureSelection(true, "", "")
                            SubAgentPreferences.save(index, empty)
                            selections = selections.toMutableList().also { it[index] = empty }
                        })
                    }
                }
            }
            Text("只保存模型引用，使用提供商中已有的凭据。未配置或与主代理相同的模型不会参与委派。每项任务最长 3 分钟，每次主代理运行最多委派 16 项任务。",
                Modifier.padding(24.dp))
        }
    }
    editing?.let { index ->
        val selected = selections[index]
        val all = AgentModelPickerProjector.project(providers, selected.providerId, selected.modelId)
        val models = all.copy(providerGroups = all.providerGroups.map { group ->
            group.copy(models = group.models.filter { !it.supportsImageGeneration && !it.supportsVideoGeneration })
        }.filter { it.models.isNotEmpty() })
        TtsModelPickerDialog(models, true, { editing = null }, { providerId, modelId ->
            val selection = ModelFeatureSelection(true, providerId, modelId)
            SubAgentPreferences.save(index, selection)
            selections = selections.toMutableList().also { it[index] = selection }
            editing = null
        }, "选择子代理 ${index + 1}")
    }
}
