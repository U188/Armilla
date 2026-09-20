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
            Text("当前聊天模型担任主代理，自动委派适合并行的任务，并审核子代理的结果。最多同时运行两个子代理。实现代理仅能读写分配的工作树，审查／总结代理只读；子代理不能继续委派。长按聊天中的模型选择器，可切换本会话协作开关。",
                Modifier.padding(24.dp))
            Card(Modifier.padding(horizontal = 12.dp)) {
                selections.forEachIndexed { index, selected ->
                    val model = AgentModelPickerProjector.project(providers, selected.providerId, selected.modelId).selectedModel
                    ArrowPreference(title = if (index == 0) "实现模型" else "审查／总结模型",
                        summary = model?.let { "${it.providerName} / ${it.displayName}" } ?: "未配置",
                        onClick = { editing = index })
                    if (selected.modelId.isNotBlank()) {
                        ArrowPreference(title = if (index == 0) "清除实现模型" else "清除审查／总结模型", onClick = {
                            val empty = ModelFeatureSelection(true, "", "")
                            SubAgentPreferences.save(index, empty)
                            selections = selections.toMutableList().also { it[index] = empty }
                        })
                    }
                }
            }
            Text("只保存模型引用，使用提供商中已有的凭据。未配置的职责不可委派；允许两个职责使用同一模型。工作区位于项目 .agent 内，需要启用终端并在所选 Linux 环境安装 Python 和 Git。构建测试由主代理执行。每项任务最长 3 分钟，每次主代理运行最多委派 16 项任务。",
                Modifier.padding(24.dp))
        }
    }
    editing?.let { index ->
        val selected = selections[index]
        val all = AgentModelPickerProjector.project(providers, selected.providerId, selected.modelId)
        val models = all.copy(providerGroups = all.providerGroups.map { group ->
            group.copy(models = group.models.filter { !it.supportsSpeechSynthesis && !it.supportsImageGeneration && !it.supportsVideoGeneration })
        }.filter { it.models.isNotEmpty() })
        TtsModelPickerDialog(models, true, { editing = null }, { providerId, modelId ->
            val selection = ModelFeatureSelection(true, providerId, modelId)
            SubAgentPreferences.save(index, selection)
            selections = selections.toMutableList().also { it[index] = selection }
            editing = null
        }, if (index == 0) "选择实现模型" else "选择审查／总结模型")
    }
}
