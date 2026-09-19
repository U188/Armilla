package io.github.mangi.eta.agent.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import io.github.mangi.eta.agent.model.AgentHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.ByteString
import io.github.mangi.eta.core.AndroidAgentLogger
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject

/** Official SeedDuplex Realtime JSON protocol with 16 kHz PCM input and 24 kHz PCM16 output. */
internal class DoubaoDuplexSession(
    context: Context,
    private val onState: (VoiceModeState) -> Unit,
) {
    private val app = context.applicationContext
    private val opened = CompletableDeferred<WebSocket>()
    private val finished = CompletableDeferred<Unit>()
    private val events = Channel<JSONObject>(Channel.UNLIMITED)
    private val output = Channel<ByteArray>(Channel.BUFFERED)
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureJob: Job? = null
    private var playbackJob: Job? = null
    @Volatile private var closed = false
    private var transcript = ""
    private var reply = ""
    private var replyResponseId = ""
    private var turnAudioStartBytes = 0L
    private var receivedAudioBytes = 0L
    private var writtenAudioBytes = 0L

    private val client = AgentHttpClient.modelClient.newBuilder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    suspend fun run(apiKey: String, voice: String, instructions: String) = coroutineScope {
        val request = DoubaoDuplexProtocol.request(apiKey)
        socket = client.newWebSocket(request, listener)
        val ws = withTimeout(15_000) { opened.await() }
        ws.send(DoubaoDuplexProtocol.sessionCreate(voice, instructions).toString())

        playbackJob = launch(Dispatchers.IO) { playOutput() }
        try {
            while (isActive && !closed) {
                val event = events.receiveCatching().getOrNull() ?: break
                val type = event.optString("type")
                val responseId = event.optString("response_id")
                if (type.startsWith("response.output_text.") && responseId.isNotBlank() && responseId != replyResponseId) {
                    reply = ""
                    replyResponseId = responseId
                }
                if (type.startsWith("conversation.item.input_audio_transcription.") ||
                    type == "response.output_text.done") {
                    // Metadata only: never log speech content or audio payloads.
                    AndroidAgentLogger.info("Duplex: event=$type deltaChars=${event.optString("delta").length} transcriptChars=${event.optString("transcript").length} textChars=${event.optString("text").length} playing=${player?.playState == AudioTrack.PLAYSTATE_PLAYING} receivedBytes=$receivedAudioBytes writtenBytes=$writtenAudioBytes")
                }
                when (type) {
                    "session.created" -> {
                        onState(state(VoiceModePhase.Listening))
                        if (captureJob == null) captureJob = launch(Dispatchers.IO) { captureInput(ws) }
                    }
                    "conversation.item.input_audio_transcription.started" -> {
                        transcript = ""
                        reply = ""
                        flushOutput()
                        onState(state(VoiceModePhase.Listening))
                    }
                    "conversation.item.input_audio_transcription.delta" -> {
                        transcript = DoubaoDuplexProtocol.eventText(event)
                        onState(state(VoiceModePhase.Listening))
                    }
                    "conversation.item.input_audio_transcription.completed" -> {
                        transcript = DoubaoDuplexProtocol.eventText(event).ifBlank { transcript }
                        onState(state(VoiceModePhase.Thinking))
                    }
                    "response.output_text.delta" -> {
                        reply += event.optString("delta")
                        onState(state(VoiceModePhase.Speaking))
                    }
                    "response.output_text.done" -> {
                        reply = event.optString("text").ifBlank { reply }
                        onState(state(VoiceModePhase.Speaking))
                    }
                    "response.output_audio.started" -> {
                        turnAudioStartBytes = receivedAudioBytes
                        onState(state(VoiceModePhase.Speaking))
                    }
                    "response.output_audio.delta" -> {
                        val bytes = Base64.decode(DoubaoDuplexProtocol.audioPayload(event), Base64.DEFAULT)
                        if (bytes.isNotEmpty()) {
                            if (receivedAudioBytes == 0L) AndroidAgentLogger.info("Duplex: first audio bytes=${bytes.size}")
                            receivedAudioBytes += bytes.size
                            output.send(bytes)
                        }
                    }
                    "response.output_audio.done" -> {
                        if (receivedAudioBytes == turnAudioStartBytes) {
                            val code = event.optString("status_code").take(40)
                            error("豆包未返回可播放音频（状态码：${code.ifBlank { "未提供" }}），请检查实时语音音色和服务权限")
                        }
                        onState(state(VoiceModePhase.Listening))
                    }
                    "response.done", "response.canceled" -> {
                        onState(state(VoiceModePhase.Listening))
                    }
                    "error" -> error(safeError(event))
                    "session.closed" -> break
                }
            }
        } finally {
            close()
            runCatching { withTimeout(2_000) { finished.await() } }
        }
    }

    private fun state(phase: VoiceModePhase) = VoiceModeState(
        mode = VoiceEntryMode.DOUBAO_DUPLEX,
        phase = phase,
        transcript = transcript,
        reply = reply,
    )

    @SuppressLint("MissingPermission")
    private suspend fun captureInput(ws: WebSocket) {
        val minimum = AudioRecord.getMinBufferSize(
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "无法初始化麦克风" }
        val audio = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minimum * 2, INPUT_FRAME_BYTES * 8),
        )
        recorder = audio
        check(audio.state == AudioRecord.STATE_INITIALIZED) { "无法打开麦克风" }
        audio.startRecording()
        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "麦克风没有开始录音" }
        val frame = ByteArray(INPUT_FRAME_BYTES)
        try {
            while (currentCoroutineContext().isActive && !closed) {
                val count = audio.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING)
                check(count >= 0) { "麦克风读取失败" }
                if (count == 0) continue
                val payload = if (count == frame.size) frame else frame.copyOf(count)
                val event = JSONObject()
                    .put("type", "input_audio_buffer.append")
                    .put("audio", Base64.encodeToString(payload, Base64.NO_WRAP))
                check(ws.send(event.toString())) { "实时语音连接已关闭" }
            }
        } finally {
            runCatching { audio.stop() }
            audio.release()
            if (recorder === audio) recorder = null
        }
    }

    private suspend fun playOutput() = withContext(Dispatchers.IO) {
        val minimum = AudioTrack.getMinBufferSize(
            OUTPUT_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minimum > 0) { "无法初始化扬声器" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(OUTPUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minimum * 2, 24_000))
            .build()
        player = track
        check(track.state == AudioTrack.STATE_INITIALIZED) { "无法打开扬声器" }
        track.play()
        try {
            for (bytes in output) {
                currentCoroutineContext().ensureActive()
                var offset = 0
                while (offset < bytes.size) {
                    val written = track.write(bytes, offset, bytes.size - offset, AudioTrack.WRITE_BLOCKING)
                    check(written > 0) { "实时语音播放失败：AudioTrack write=$written" }
                    if (writtenAudioBytes == 0L) AndroidAgentLogger.info("Duplex: first playback write=$written route=${track.routedDevice?.type}")
                    writtenAudioBytes += written
                    offset += written
                }
            }
        } finally {
            runCatching { track.pause() }
            runCatching { track.flush() }
            track.release()
            if (player === track) player = null
        }
    }

    private fun flushOutput() {
        while (output.tryReceive().isSuccess) Unit
        player?.let { track ->
            runCatching { track.pause() }
            runCatching { track.flush() }
            runCatching { track.play() }
        }
    }

    fun close() {
        if (closed) return
        closed = true
        socket?.send(JSONObject().put("type", "session.close").put("event_id", UUID.randomUUID().toString()).toString())
        captureJob?.cancel()
        playbackJob?.cancel()
        output.close()
        events.close()
        runCatching { recorder?.stop() }
        runCatching { recorder?.release() }
        recorder = null
        runCatching { player?.pause() }
        runCatching { player?.flush() }
        runCatching { player?.release() }
        player = null
        socket?.close(1000, "voice mode stopped")
        socket = null
    }

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            opened.complete(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            acceptEvent(text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            acceptEvent(bytes.utf8())
        }

        private fun acceptEvent(text: String) {
            runCatching { JSONObject(text) }
                .onSuccess { event ->
                    if (event.optString("type").contains("audio") &&
                        !event.optString("type").contains("transcription")) {
                        val shape = event.keys().asSequence().take(24).joinToString { key ->
                            val value = event.opt(key)
                            "$key:${value?.javaClass?.simpleName}:${if (value is String) value.length else -1}"
                        }
                        AndroidAgentLogger.info("Duplex: audio event=${event.optString("type")} status=${event.optString("status_code").take(40)} fields=[$shape]")
                    }
                    if (event.optString("type") == "response.output_audio.done") {
                        AndroidAgentLogger.info("Duplex: audio done receivedBytes=$receivedAudioBytes writtenBytes=$writtenAudioBytes")
                    }
                    events.trySend(event)
                }
                .onFailure {
                    AndroidAgentLogger.warn("Duplex: invalid JSON event frame")
                    events.trySend(JSONObject().put("type", "error").put("error",
                        JSONObject().put("message", "实时语音事件解析失败")))
                }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!opened.isCompleted) opened.completeExceptionally(t)
            if (!closed) events.trySend(
                JSONObject().put("type", "error").put("error", JSONObject().put("message", "实时语音连接失败")),
            )
            finished.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            finished.complete(Unit)
        }
    }

    private fun safeError(event: JSONObject): String =
        event.optJSONObject("error")?.optString("message")?.take(300)?.ifBlank { null }
            ?: "豆包实时语音服务返回错误"

    companion object {
        private const val INPUT_RATE = 16_000
        private const val OUTPUT_RATE = 24_000
        private const val INPUT_FRAME_BYTES = 640
    }
}
