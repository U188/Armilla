package io.github.mangi.eta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.VoiceEntryMode
import io.github.mangi.eta.agent.voice.VoiceModeController
import io.github.mangi.eta.agent.voice.tts.DoubaoSpeech
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun VoiceModeSettingsScreen(onBack: () -> Unit) {
    var defaultMode by remember {
        mutableStateOf(VoiceEntryMode.fromWireValue(Prefs.getString(Prefs.Keys.AGENT_VOICE_DEFAULT_MODE)))
    }
    var modePicker by remember { mutableStateOf(false) }
    var providerPicker by remember { mutableStateOf(false) }
    var providerId by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_PROVIDER_ID)
                .ifBlank { Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID) },
        )
    }
    var voice by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_VOICE)
                .ifBlank { Prefs.getString(Prefs.Keys.AGENT_TTS_VOICE) }
                .ifBlank { VoiceModeController.DEFAULT_DUPLEX_VOICE },
        )
    }
    var instructions by remember {
        mutableStateOf(
            Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS)
                .ifBlank { VoiceModeController.DEFAULT_DUPLEX_INSTRUCTIONS },
        )
    }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val speechProviders = remember(providers) { providers.filter { DoubaoSpeech.isOpenspeech(it.baseUrl) } }
    val provider = speechProviders.firstOrNull { it.id == providerId }

    MiuixScaffoldPage(title = stringResource(R.string.voice_mode_title), onBack = onBack) {
        item(key = "default") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.voice_mode_default),
                    summary = stringResource(defaultMode.labelResource()),
                    insideMargin = PaddingValues(16.dp),
                    onClick = { modePicker = true },
                )
            }
        }
        item(key = "universal") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.voice_mode_universal),
                    summary = stringResource(R.string.voice_mode_universal_settings_summary),
                    insideMargin = PaddingValues(16.dp),
                    onClick = {},
                )
                Text(
                    text = stringResource(R.string.voice_mode_universal_settings_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
        item(key = "doubao") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(R.string.voice_mode_doubao_provider),
                    summary = provider?.name ?: stringResource(R.string.voice_mode_doubao_provider_missing),
                    insideMargin = PaddingValues(16.dp),
                    onClick = { providerPicker = true },
                )
                OutlinedTextField(
                    value = voice,
                    onValueChange = {
                        voice = it
                        Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_VOICE, it.trim())
                    },
                    label = { Text(stringResource(R.string.voice_mode_doubao_voice)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = {
                        instructions = it
                        Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS, it)
                    },
                    label = { Text(stringResource(R.string.voice_mode_doubao_instructions)) },
                    minLines = 2,
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Text(
                    text = stringResource(R.string.voice_mode_doubao_settings_hint),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                )
            }
        }
    }

    VoiceModeListDialog(
        show = modePicker,
        title = stringResource(R.string.voice_mode_default),
        rows = VoiceEntryMode.entries.map { it.wireValue to stringResource(it.labelResource()) },
        selected = defaultMode.wireValue,
        onDismiss = { modePicker = false },
        onSelect = { value ->
            defaultMode = VoiceEntryMode.fromWireValue(value)
            Prefs.putString(Prefs.Keys.AGENT_VOICE_DEFAULT_MODE, value)
            modePicker = false
        },
    )
    VoiceModeListDialog(
        show = providerPicker,
        title = stringResource(R.string.voice_mode_doubao_provider),
        rows = speechProviders.map { it.id to it.name },
        selected = providerId,
        emptyText = stringResource(R.string.voice_mode_doubao_provider_missing),
        onDismiss = { providerPicker = false },
        onSelect = { value ->
            providerId = value
            Prefs.putString(Prefs.Keys.AGENT_VOICE_DOUBAO_PROVIDER_ID, value)
            providerPicker = false
        },
    )
}

@Composable
private fun VoiceModeListDialog(
    show: Boolean,
    title: String,
    rows: List<Pair<String, String>>,
    selected: String,
    emptyText: String = "",
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    WindowDialog(show = show, title = title, onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            if (rows.isEmpty()) {
                Text(emptyText, modifier = Modifier.padding(18.dp))
            }
            rows.forEach { (value, label) ->
                Text(
                    text = if (value == selected) "✓  $label" else label,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(value) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

private fun VoiceEntryMode.labelResource(): Int = when (this) {
    VoiceEntryMode.DICTATION -> R.string.voice_mode_dictation
    VoiceEntryMode.UNIVERSAL -> R.string.voice_mode_universal
    VoiceEntryMode.DOUBAO_DUPLEX -> R.string.voice_mode_doubao
}
