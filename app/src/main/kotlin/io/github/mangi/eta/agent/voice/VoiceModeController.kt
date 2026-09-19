package io.github.mangi.eta.agent.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import io.github.mangi.eta.agent.voice.tts.DoubaoSpeech
import io.github.mangi.eta.agent.voice.tts.SpeechPlayback
import io.github.mangi.eta.agent.voice.tts.SpeechSpeakableText
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.ProviderRepository
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

enum class VoiceEntryMode(val wireValue: String) {
    DICTATION("dictation"),
    UNIVERSAL("universal"),
    DOUBAO_DUPLEX("doubao_duplex");

    companion object {
        fun fromWireValue(value: String): VoiceEntryMode =
            entries.firstOrNull { it.wireValue == value } ?: DICTATION
    }
}

enum class VoiceModePhase { Off, Connecting, Listening, Transcribing, Thinking, Speaking, Error }

data class VoiceModeState(
    val mode: VoiceEntryMode? = null,
    val phase: VoiceModePhase = VoiceModePhase.Off,
    val transcript: String = "",
    val reply: String = "",
    val error: String? = null,
) {
    val active: Boolean get() = phase != VoiceModePhase.Off && phase != VoiceModePhase.Error
}

data class VoiceChatSnapshot(
    val isStreaming: Boolean = false,
    val lastAgentId: String? = null,
    val lastAgentText: String = "",
)

/** Owns either the cascaded chat loop or the direct SeedDuplex call, never both. */
internal class VoiceModeController(
    context: Context,
    private val scope: CoroutineScope,
    private val submit: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val mutableState = MutableStateFlow(VoiceModeState())
    val state = mutableState.asStateFlow()
    private val chat = MutableStateFlow(VoiceChatSnapshot())
    private var job: Job? = null
    private var duplex: DoubaoDuplexSession? = null
    private var generation = 0L

    fun updateChat(snapshot: VoiceChatSnapshot) {
        chat.value = snapshot
        val current = mutableState.value
        if (current.mode == VoiceEntryMode.UNIVERSAL &&
            current.phase in setOf(VoiceModePhase.Thinking, VoiceModePhase.Speaking)) {
            mutableState.value = current.copy(reply = snapshot.lastAgentText)
        }
    }

    fun start(mode: VoiceEntryMode) {
        if (mode == VoiceEntryMode.DICTATION || job?.isActive == true) return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = VoiceModeState(mode, VoiceModePhase.Error, error = "需要麦克风权限")
            return
        }
        when (mode) {
            VoiceEntryMode.UNIVERSAL -> startUniversal()
            VoiceEntryMode.DOUBAO_DUPLEX -> startDuplex()
            VoiceEntryMode.DICTATION -> Unit
        }
    }

    private fun startUniversal() {
        if (!OfflineSpeechPack.state.value.ready || !OfflineSpeechPack.state.value.enabled) {
            mutableState.value = VoiceModeState(
                VoiceEntryMode.UNIVERSAL,
                VoiceModePhase.Error,
                error = "请先在语音转文字设置中下载并启用离线语音包",
            )
            return
        }
        stop()
        job = scope.launch {
            try {
                while (true) {
                    var text = ""
                    mutableState.value = VoiceModeState(VoiceEntryMode.UNIVERSAL, VoiceModePhase.Connecting)
                    val token = SpeechPlayback.beginInput()
                    val heard = try {
                        OfflineSpeechSession.recognize(
                            app,
                            onListening = {
                                mutableState.value = mutableState.value.copy(phase = VoiceModePhase.Listening)
                            },
                            onText = { next ->
                                text = next
                                mutableState.value = mutableState.value.copy(
                                    phase = VoiceModePhase.Listening,
                                    transcript = next,
                                )
                            },
                        )
                    } finally {
                        SpeechPlayback.endInput(token)
                    }
                    if (!heard || text.isBlank()) continue

                    val baseline = chat.value.lastAgentId
                    mutableState.value = mutableState.value.copy(phase = VoiceModePhase.Transcribing)
                    submit(text)
                    mutableState.value = mutableState.value.copy(phase = VoiceModePhase.Thinking)
                    withTimeout(20_000) {
                        chat.first { it.isStreaming || it.lastAgentId != baseline }
                    }
                    val owner = "voice-mode-${UUID.randomUUID()}"
                    var spoken = 0
                    var speakingMessageId: String? = null
                    withTimeout(240_000) {
                        while (true) {
                            val snap = chat.value
                            if (snap.lastAgentId == baseline || snap.lastAgentId == null) {
                                chat.first { it != snap }
                                continue
                            }
                            if (speakingMessageId != snap.lastAgentId) {
                                speakingMessageId = snap.lastAgentId
                                spoken = 0
                            }
                            val ready = SpeechSpeakableText.committedSentences(
                                snap.lastAgentText,
                                finalized = !snap.isStreaming && snap.lastAgentId != baseline,
                            )
                            val utterances = ready.drop(spoken)
                            if (utterances.isNotEmpty()) {
                                mutableState.value = mutableState.value.copy(
                                    phase = VoiceModePhase.Speaking,
                                    reply = snap.lastAgentText,
                                )
                                for (utterance in utterances) {
                                    SpeechPlayback.speak(app, owner, utterance)
                                    val playback = SpeechPlayback.state.first { it.owner != owner }
                                    playback.error?.let { error(it) }
                                }
                                spoken = ready.size
                            } else if (snap.lastAgentText.isNotBlank()) {
                                mutableState.value = mutableState.value.copy(reply = snap.lastAgentText)
                            }
                            if (!snap.isStreaming && snap.lastAgentId != baseline) break
                            chat.first {
                                it.lastAgentText != snap.lastAgentText ||
                                    it.isStreaming != snap.isStreaming ||
                                    it.lastAgentId != snap.lastAgentId
                            }
                        }
                    }
                    delay(300)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                mutableState.value = VoiceModeState(
                    mode = VoiceEntryMode.UNIVERSAL,
                    phase = VoiceModePhase.Error,
                    error = e.message ?: "语音对话失败",
                )
            } finally {
                SpeechPlayback.stop()
            }
        }
    }

    private fun startDuplex() {
        stop()
        val sessionGeneration = generation
        job = scope.launch {
            var ownedSession: DoubaoDuplexSession? = null
            try {
                val providerId = Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_PROVIDER_ID)
                    .ifBlank { Prefs.getString(Prefs.Keys.AGENT_TTS_MODEL_PROVIDER_ID) }
                val provider = ProviderRepository.providerById(providerId)
                    ?: error("请先在语音对话设置中选择豆包语音提供商")
                check(DoubaoSpeech.isOpenspeech(provider.baseUrl)) { "实时通话只能使用豆包语音提供商" }
                val apiKey = provider.apiKey.trim()
                check(apiKey.isNotBlank()) { "豆包语音提供商尚未配置 API Key" }
                val voice = Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_VOICE)
                    .ifBlank { Prefs.getString(Prefs.Keys.AGENT_TTS_VOICE) }
                    .ifBlank { DEFAULT_DUPLEX_VOICE }
                val instructions = Prefs.getString(Prefs.Keys.AGENT_VOICE_DOUBAO_INSTRUCTIONS)
                    .ifBlank { DEFAULT_DUPLEX_INSTRUCTIONS }
                mutableState.value = VoiceModeState(VoiceEntryMode.DOUBAO_DUPLEX, VoiceModePhase.Connecting)
                val token = SpeechPlayback.beginInput()
                try {
                    val session = DoubaoDuplexSession(app) { next ->
                        if (generation == sessionGeneration) mutableState.value = next
                    }
                    ownedSession = session
                    duplex = session
                    session.run(apiKey, voice, instructions)
                } finally {
                    SpeechPlayback.endInput(token)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                if (generation != sessionGeneration) return@launch
                mutableState.value = VoiceModeState(
                    mode = VoiceEntryMode.DOUBAO_DUPLEX,
                    phase = VoiceModePhase.Error,
                    error = e.message ?: "豆包实时通话失败",
                )
            } finally {
                ownedSession?.close()
                if (duplex === ownedSession) duplex = null
            }
        }
    }

    fun stop() {
        generation++
        job?.cancel()
        job = null
        duplex?.close()
        duplex = null
        SpeechPlayback.stop()
        mutableState.value = VoiceModeState()
    }

    companion object {
        const val DEFAULT_DUPLEX_VOICE = "zh_female_xiaohe_jupiter_bigtts"
        const val DEFAULT_DUPLEX_INSTRUCTIONS = "你是代鱼，一个简洁、自然、友善的中文语音助手。"
    }
}
