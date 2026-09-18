package io.github.mangi.eta.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
internal fun TtsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var cloud by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODE) == "cloud") }
    var providerId by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID)) }
    var modelId by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_ID)) }
    var voice by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_VOICE)) }
    var picker by remember { mutableStateOf(false) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val models = remember(providers, providerId, modelId) {
        AgentModelPickerProjector.project(providers.filter(SpeechSynthesisModels::allowsSpeechEndpoint), providerId, modelId, includeSpeechModels = true)
    }
    val playback by SpeechPlayback.state.collectAsState()
    val sample = stringResource(R.string.tts_sample)
    MiuixScaffoldPage(title = stringResource(R.string.tts_title), onBack = onBack) {
        item(key = "tts_mode") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                SwitchPreference(
                    title = stringResource(R.string.tts_cloud),
                    summary = stringResource(R.string.tts_mode_hint),
                    checked = cloud,
                    insideMargin = PaddingValues(16.dp),
                    onCheckedChange = {
                        SpeechPlayback.stop()
                        cloud = it
                        Prefs.putString(Prefs.Keys.AGENT_TTS_MODE, if (it) "cloud" else "system")
                    },
                )
            }
        }
        if (cloud) {
            item(key = "tts_model") {
                Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    ArrowPreference(
                        title = stringResource(R.string.tts_model),
                        summary = models.selectedModel?.let { "${it.providerName} · ${it.displayName}" } ?: stringResource(R.string.tts_select_model),
                        insideMargin = PaddingValues(16.dp),
                        onClick = { picker = true },
                    )
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedTextField(
                            value = voice,
                            onValueChange = {
                                voice = it.take(128)
                                SpeechPlayback.stop()
                                Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, voice.trim())
                            },
                            label = { Text(stringResource(R.string.tts_voice)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(stringResource(R.string.tts_protocol_hint), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 10.dp))
                    }
                }
            }
        }
        item(key = "tts_preview") {
            Card(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                ArrowPreference(
                    title = stringResource(if (playback.owner == "tts-preview") R.string.tts_stop else R.string.tts_preview),
                    summary = playback.error ?: playback.source.takeIf { it.isNotBlank() }
                        ?: stringResource(if (playback.preparing) R.string.tts_preparing else R.string.tts_manual_hint),
                    insideMargin = PaddingValues(16.dp),
                    enabled = !playback.recording && (!cloud || (models.selectedModel != null && voice.isNotBlank())),
                    onClick = { SpeechPlayback.toggle(context, "tts-preview", sample) },
                )
            }
        }
        item(key = "tts_privacy") {
            Text(stringResource(R.string.tts_privacy), style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 10.dp))
        }
    }
    CompressModelPickerDialog(
        state = models, show = picker, onDismiss = { picker = false },
        title = stringResource(R.string.tts_model),
        onModelSelected = { provider, model ->
            SpeechPlayback.stop()
            // Voices are provider/model-specific. Do not carry one to an unrelated service.
            if (providerId != provider || modelId != model) {
                voice = ""
                Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, "")
            }
            providerId = provider
            modelId = model
            Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID, provider)
            Prefs.putString(Prefs.Keys.AGENT_TTS_MODEL_ID, model)
            picker = false
        },
    )
}
