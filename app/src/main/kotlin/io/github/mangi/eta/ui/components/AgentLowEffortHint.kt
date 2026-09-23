package io.github.mangi.eta.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.mangi.eta.R
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.ReasoningEffort

/**
 * 主代理以 low / minimal 思考档运行时，开启子代理协作会让它更快地轮询与重复执行，
 * 结果往往比提高思考档更贵。这里只在开启协作时做一次性提示，不阻拦用户，也不改动
 * `agent` 目录下的运行时行为（注意：Kotlin 块注释会嵌套，这里不能出现斜杠加星号）。
 */
internal fun isLowMainReasoningEffort(effort: ReasoningEffort): Boolean =
    effort == ReasoningEffort.LOW || effort == ReasoningEffort.MINIMAL

// 沿用 SubAgentPreferences 的 Prefs 键风格；未落库会话（草稿）用 "draft" 占位。
private fun lowEffortHintKey(conversation: String?) = "agent_low_effort_hint_shown_${conversation ?: "draft"}"

internal fun lowEffortHintShown(conversation: String?): Boolean =
    Prefs.getString(lowEffortHintKey(conversation), "false") == "true"

internal fun markLowEffortHintShown(conversation: String?) {
    Prefs.putString(lowEffortHintKey(conversation), "true")
}

@Composable
internal fun AgentLowEffortHintDialog(show: Boolean, onDismiss: () -> Unit) {
    if (!show) return
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.subtask_low_effort_hint_title)) },
        text = { Text(stringResource(R.string.subtask_low_effort_hint_message)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.subtask_low_effort_hint_confirm)) }
        },
    )
}
