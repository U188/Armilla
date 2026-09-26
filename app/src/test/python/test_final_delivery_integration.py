"""Merged-source contracts only; Kotlin/Android execution is checked separately."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[3] / 'src/main/kotlin/io/github/mangi/eta'


class FinalDeliveryIntegrationTest(unittest.TestCase):
    def source(self, path):
        return (ROOT / path).read_text()

    def test_stopped_main_reply_and_pruning_signal_coexist(self):
        recovery = self.source('ui/app/AgentPendingResultRecovery.kt')
        self.assertIn('result.error == "已停止") SystemNoticeCode.Stopped', recovery)
        self.assertIn('messages[assistantIndex] = partial.copy(isStreaming = false)', recovery)
        self.assertIn('VirtualCompletionNotice.append(messagesWithResult, runId, result)', recovery)
        event = self.source('agent/runtime/AgentEvent.kt')
        wire = self.source('agent/runtime/AgentRuntimeWire.kt')
        self.assertIn('val pruningOnly: Boolean', event)
        self.assertIn('putBoolean("pruning_only", event.pruningOnly)', wire)
        self.assertIn('pruningOnly = bundle.getBoolean("pruning_only",', wire)
        app = self.source('ui/app/AgentAppState.kt')
        handler = app.split('private fun applyRuntimeCompactedHistory(', 1)[1].split('private fun scheduleAutoCompress(', 1)[0]
        prune = handler.split('if (event.pruningOnly)', 1)[1].split('runCompressedDuringRun.add(runId)', 1)[0]
        self.assertIn('current.copy(history = event.history)', prune)
        self.assertNotIn('livePromptTokens = null', prune)
        self.assertNotIn('cloudHistoryTokens = null', prune)
        self.assertIn('AgentContextCompactionUi.applyMarker(', handler)

    def test_three_horizontal_actions_only_preview_uses_full_preview_entry(self):
        controls = self.source('ui/VirtualDisplayRecoveryScreen.kt')
        row = controls.split('Row(\n            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)', 1)[1]
        self.assertEqual(3, row.count('TextButton('))
        self.assertEqual(3, row.count('Modifier.weight(1f).fillMaxHeight()'))
        self.assertEqual(1, row.count('VirtualDisplayWebPreview.openWithManualClose(context)'))
        self.assertNotIn('VirtualDisplayWebPreview.open(context)', row)
        self.assertIn('onClick = { TouchHaptics.click(view); refresh() }', row)
        self.assertIn('recover(context.applicationContext)', row)

    def test_dynamic_configuration_error_has_explicit_recovery(self):
        editor = self.source('ui/components/ConversationSubAgentEditor.kt')
        self.assertIn('get() = lifecycleFailure?.invoke()?.let { SubAgentEditorState.Error(it) } ?: storedState', editor)
        retry = editor.split('fun retry() {', 1)[1].split('@Composable', 1)[0]
        self.assertIn('repository.recoverDurability()', retry)
        self.assertIn('lifecycleRecovery?.invoke() == true', retry)
        self.assertLess(retry.index('lifecycleRecovery?.invoke() == true'), retry.index('storedState = SubAgentEditorState.Loading'))
        self.assertIn('val enabled: Boolean get() = state is SubAgentEditorState.Loaded && canEdit()', editor)


if __name__ == '__main__':
    unittest.main()
