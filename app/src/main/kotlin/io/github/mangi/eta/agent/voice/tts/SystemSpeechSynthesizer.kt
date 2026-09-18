package io.github.mangi.eta.agent.voice.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** One engine per playback session. No shared listeners, blocking latches or recycled utterance IDs. */
internal class SystemSpeechSynthesizer {
    suspend fun speak(context: Context, sentences: List<String>, onReady: (String) -> Unit) = withContext(Dispatchers.Main.immediate) {
        if (sentences.isEmpty()) return@withContext
        val initialized = CompletableDeferred<Int>()
        val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val engine = TextToSpeech(context.applicationContext) { initialized.complete(it) }
        try {
            speechCheck(withTimeout(8_000) { initialized.await() } == TextToSpeech.SUCCESS) { "系统朗读引擎不可用" }
            val locale = if (sentences.any { it.any { char -> char.code in 0x3400..0x9fff } }) Locale.CHINESE else Locale.getDefault()
            val voice = engine.voices.orEmpty().filter {
                !it.isNetworkConnectionRequired && "notInstalled" !in it.features.orEmpty() && it.locale.language == locale.language
            }.sortedBy { it.name }.firstOrNull()
            speechCheck(voice != null) { "没有已安装的本地语音音色，请到系统文字转语音设置下载，或选择云端朗读" }
            speechCheck(engine.setVoice(voice) == TextToSpeech.SUCCESS) { "系统音色不可用" }
            engine.setAudioAttributes(SpeechPlayback.audioAttributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { utteranceId?.let { pending.remove(it)?.complete(Unit) } }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { fail(utteranceId) }
                override fun onError(utteranceId: String?, errorCode: Int) { fail(utteranceId) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { fail(utteranceId) }
                private fun fail(id: String?) {
                    id?.let { pending.remove(it)?.completeExceptionally(SpeechPlaybackFailure("系统朗读已中断或失败")) }
                }
            })
            warmup(engine, pending)
            onReady(voice.name)
            for (sentence in sentences) {
                currentCoroutineContext().ensureActive()
                awaitSpeak(engine, pending, sentence, TextToSpeech.QUEUE_ADD)
            }
        } finally {
            initialized.cancel()
            runCatching { engine.stop() }
            runCatching { engine.shutdown() }
            pending.values.forEach { it.cancel() }
            pending.clear()
        }
    }

    private suspend fun warmup(
        engine: TextToSpeech,
        pending: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
    ) {
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        val queued = runCatching { engine.playSilentUtterance(120, TextToSpeech.QUEUE_FLUSH, id) }.getOrDefault(TextToSpeech.ERROR)
        if (queued != TextToSpeech.SUCCESS) {
            pending.remove(id)
            return
        }
        runCatching { withTimeoutOrNull(3_000) { done.await() } }
        pending.remove(id)
    }

    private suspend fun awaitSpeak(
        engine: TextToSpeech,
        pending: ConcurrentHashMap<String, CompletableDeferred<Unit>>,
        sentence: String,
        queueMode: Int,
    ) {
        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        speechCheck(engine.speak(sentence, queueMode, null, id) == TextToSpeech.SUCCESS) { "系统语音无法播放" }
        withTimeout(90_000) { done.await() }
        pending.remove(id)
    }
}
