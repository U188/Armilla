package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrSession
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import kotlinx.coroutines.sync.Mutex

internal object SpeechInputSession {
    private val microphone = Mutex()
    fun ready(): Boolean = if (DoubaoVoiceConfig.state.value.cloudAsr) DoubaoVoiceConfig.state.value.asrKey.isNotBlank()
        else OfflineSpeechPack.state.value.let { it.enabled && it.ready }
    suspend fun recognize(context: Context, onListening: suspend () -> Unit, onText: suspend (String) -> Unit): Boolean {
        check(microphone.tryLock()) { "语音输入正在使用麦克风" }
        try {
            DoubaoVoiceConfig.load(context)
            return if (DoubaoVoiceConfig.state.value.cloudAsr) DoubaoAsrSession.recognize(onListening, onText)
                else OfflineSpeechSession.recognize(context, onListening, onText)
        } finally { microphone.unlock() }
    }
}
