package io.github.mangi.eta.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.agent.voice.tts.SpeechEngineResolver
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.agent.voice.tts.SpeechVoices
import io.github.mangi.eta.agent.voice.tts.SpeechVoice
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.model.SpeechSynthesisModels
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.ui.components.MiuixScaffoldPage
import io.github.mangi.eta.ui.model.AgentModelPickerProjector
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
internal fun TtsSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var cloud by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODE) == "cloud") }
    var providerId by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID)) }
    var modelId by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_ID)) }
    var voice by remember { mutableStateOf(Prefs.getString(Prefs.Keys.AGENT_TTS_VOICE)) }
    var picker by remember { mutableStateOf(false) }
    var voicePicker by remember { mutableStateOf(false) }
    val providers by remember { ProviderRepository.providersFlow() }.collectAsState(initial = emptyList())
    val models = remember(providers, providerId, modelId) {
        AgentModelPickerProjector.project(providers.filter(SpeechSynthesisModels::allowsSpeechEndpoint), providerId, modelId, includeSpeechModels = true)
    }
    val selectedProvider = remember(providers, providerId) { providers.firstOrNull { it.id == providerId } }
    val engine = remember(selectedProvider, modelId) { SpeechEngineResolver.resolve(selectedProvider, modelId) }
    val catalog = remember(engine, modelId) { SpeechVoices.catalog(engine, modelId) }
    val playback by SpeechPlayback.state.collectAsState()
    val sample = stringResource(R.string.tts_sample)
    LaunchedEffect(cloud, providerId, selectedProvider?.id, engine, modelId, catalog, voice) {
        if (!SpeechVoices.shouldReplaceStoredVoice(
                cloud = cloud,
                providerId = providerId,
                providerReady = selectedProvider != null,
                storedVoice = voice,
                catalogIds = catalog.map { it.id },
            )
        ) {
            return@LaunchedEffect
        }
        val fallback = catalog.first().id
        voice = fallback
        Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, fallback)
    }
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
                    ArrowPreference(
                        title = stringResource(R.string.tts_voice),
                        summary = catalog.firstOrNull { it.id == voice }?.name
                            ?: voice.ifBlank { stringResource(R.string.tts_select_voice) },
                        insideMargin = PaddingValues(16.dp),
                        enabled = models.selectedModel != null && catalog.isNotEmpty(),
                        onClick = { voicePicker = true },
                    )
                    Text(
                        stringResource(R.string.tts_protocol_hint),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
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
    TtsVoicePickerDialog(
        show = voicePicker,
        voices = catalog,
        selectedId = voice,
        onDismiss = { voicePicker = false },
        onSelected = { id ->
            SpeechPlayback.stop()
            voice = id
            Prefs.putString(Prefs.Keys.AGENT_TTS_VOICE, id)
            voicePicker = false
        },
    )
}

@Composable
private fun TtsVoicePickerDialog(
    show: Boolean,
    voices: List<SpeechVoice>,
    selectedId: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit,
) {
    WindowDialog(
        show = show,
        title = stringResource(R.string.tts_voice),
        onDismissRequest = onDismiss,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            val female = voices.filter { "_female_" in it.id }
            val male = voices.filter { "_male_" in it.id }
            val other = voices.filter { voice -> voice !in female && voice !in male }
            if (female.isNotEmpty()) {
                VoiceSectionTitle(stringResource(R.string.tts_voice_female))
                female.forEach { VoiceRow(it, selectedId, onSelected) }
            }
            if (male.isNotEmpty()) {
                VoiceSectionTitle(stringResource(R.string.tts_voice_male))
                male.forEach { VoiceRow(it, selectedId, onSelected) }
            }
            other.forEach { VoiceRow(it, selectedId, onSelected) }
        }
    }
}

@Composable
private fun VoiceSectionTitle(text: String) {
    Text(
        text = text,
        style = MiuixTheme.textStyles.subtitle,
        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun VoiceRow(voice: SpeechVoice, selectedId: String, onSelected: (String) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelected(voice.id) }
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Text(
            text = voice.name,
            color = if (voice.id == selectedId) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        )
    }
}
