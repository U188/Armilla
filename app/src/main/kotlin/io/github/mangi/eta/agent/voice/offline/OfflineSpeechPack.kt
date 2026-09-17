package io.github.mangi.eta.agent.voice.offline

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One app-process download. Private no-backup storage; neither audio nor credentials uploaded. */
internal object OfflineSpeechPack {
    data class State(
        val checking: Boolean = true,
        val ready: Boolean = false,
        val enabled: Boolean = false,
        val downloading: Boolean = false,
        val downloadedBytes: Long = 0,
        val error: Boolean = false,
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    private var initialized = false
    private var download: Job? = null
    private fun prefs(context: Context) = context.getSharedPreferences("offline_speech", Context.MODE_PRIVATE)
    fun directory(context: Context) = File(context.noBackupFilesDir, "speech/${SpeechModelManifest.REVISION}")

    @Synchronized fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        scope.launch {
            val ready = withContext(Dispatchers.IO) { verify(directory(app)) }
            val enabled = ready && prefs(app).getBoolean("enabled", false)
            if (!ready) prefs(app).edit().putBoolean("enabled", false).apply()
            mutable.value = State(checking = false, ready = ready, enabled = enabled)
        }
    }

    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (enabled && !SpeechInputPolicy.canEnable(mutable.value.ready, mutable.value.checking, mutable.value.downloading)) return false
        prefs(context).edit().putBoolean("enabled", enabled).apply()
        mutable.value = mutable.value.copy(enabled = enabled)
        return true
    }

    fun cancelDownload() { download?.cancel() }

    fun download(context: Context) {
        if (mutable.value.checking || mutable.value.downloading || mutable.value.ready) return
        val app = context.applicationContext
        mutable.value = mutable.value.copy(downloading = true, downloadedBytes = 0, error = false)
        download = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val dir = directory(app).apply { mkdirs() }
                    var done = 0L
                    for (asset in SpeechModelManifest.assets) {
                        ensureActive()
                        val target = File(dir, asset.name)
                        if (!valid(target, asset)) {
                            val partial = File(dir, asset.name + ".part")
                            val connection = URI(SpeechModelManifest.BASE_URL + asset.name).toURL()
                                .openConnection() as HttpURLConnection
                            connection.connectTimeout = 15_000
                            connection.readTimeout = 15_000
                            connection.setRequestProperty("Accept-Encoding", "identity")
                            try {
                                check(connection.responseCode == 200) { "Model download HTTP failure" }
                                check(connection.url.protocol == "https") { "Insecure model redirect" }
                                var received = 0L
                                var lastProgress = 0L
                                connection.inputStream.use { input ->
                                    partial.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            ensureActive()
                                            val count = input.read(buffer)
                                            if (count < 0) break
                                            received += count
                                            check(received <= asset.bytes) { "Model file exceeds manifest size" }
                                            output.write(buffer, 0, count)
                                            if (received - lastProgress >= 256 * 1024) {
                                                lastProgress = received
                                                withContext(Dispatchers.Main) {
                                                    mutable.value = mutable.value.copy(downloadedBytes = done + received)
                                                }
                                            }
                                        }
                                    }
                                }
                                check(valid(partial, asset)) { "Model checksum mismatch" }
                                ensureActive()
                                check(partial.renameTo(target)) { "Cannot commit verified model" }
                            } finally {
                                connection.disconnect()
                                partial.delete()
                            }
                        }
                        done += asset.bytes
                        withContext(Dispatchers.Main) { mutable.value = mutable.value.copy(downloadedBytes = done) }
                    }
                }
                // Downloading does not silently enable microphone functionality.
                mutable.value = State(checking = false, ready = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value = mutable.value.copy(error = true)
            } finally {
                mutable.value = mutable.value.copy(downloading = false)
            }
        }
    }

    internal fun valid(file: File, asset: SpeechModelManifest.Asset): Boolean = runCatching {
        if (!file.isFile || file.length() != asset.bytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) } == asset.sha256
    }.getOrDefault(false)

    private fun verify(dir: File) = SpeechModelManifest.assets.all { valid(File(dir, it.name), it) }
}
