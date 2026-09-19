package io.github.mangi.eta.agent.voice

import android.content.Context
import io.github.mangi.eta.agent.voice.doubao.DoubaoAsrSession
import io.github.mangi.eta.agent.voice.doubao.DoubaoVoiceConfig
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechPack
import io.github.mangi.eta.agent.voice.offline.OfflineSpeechSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

internal object SpeechInputSession {
    private val microphone = Mutex()
    fun ready(): Boolean = DoubaoVoiceConfig.state.value.let {
        it.inputEnabled && if (it.cloudAsr) it.asrKey.isNotBlank() else OfflineSpeechPack.state.value.ready
    }
    suspend fun recognize(context: Context, onListening: suspend () -> Unit, onText: suspend (String) -> Unit): Boolean {
        check(microphone.tryLock()) { "语音输入正在使用麦克风" }
        try {
            DoubaoVoiceConfig.load(context)
            check(ready()) { "请启用语音转文字，并配置所选识别引擎" }
            val selected = DoubaoVoiceConfig.state.value.cloudAsr
            return coroutineScope {
                val owner = currentCoroutineContext().job
                val watcher = launch {
                    DoubaoVoiceConfig.state.first { !it.inputEnabled || it.cloudAsr != selected }
                    owner.cancel(CancellationException("语音转文字已关闭或识别引擎已切换"))
                }
                try {
                    if (selected) DoubaoAsrSession.recognize(onListening, onText)
                    else OfflineSpeechSession.recognize(context, onListening, onText)
                } finally { watcher.cancel() }
            }
        } finally { microphone.unlock() }
    }
}
