package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import okhttp3.CacheControl
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

/** Manual refresh only. Restore verified disk snapshots at startup without accessing the network. */
internal object RemotePersonaStore {
    private const val DEFAULT_GIST_ID = "9a2e1239ac8af01d6f6273b09f31fc31"
    private const val HACKER_GIST_ID = "1231d6d69cb045a33e8cd434f1e09aec"
    private const val FILE_NAME = "gistfile1.txt"
    private const val MAX_PERSONA_BYTES = 64 * 1024
    private const val MAX_METADATA_BYTES = 512 * 1024

    sealed interface PersonaResult {
        data class Success(
            val snapshot: RemotePersonaCache.Snapshot,
            val changed: Boolean,
        ) : PersonaResult
        data class Failure(
            val reason: String,
            val retained: RemotePersonaCache.Snapshot?,
        ) : PersonaResult
    }

    data class RefreshResult(val default: PersonaResult, val hacker: PersonaResult)

    @Volatile
    private var defaultCache: RemotePersonaCache? = null
    @Volatile
    private var hackerCache: RemotePersonaCache? = null

    private val httpClient by lazy {
        AgentHttpClient.client.newBuilder()
            .callTimeout(45, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun init(context: Context) = init(context.filesDir)

    @Synchronized
    internal fun init(filesDir: File) {
        defaultCache = restore(filesDir, "remote_persona_default", DEFAULT_GIST_ID)
        hackerCache = restore(filesDir, "remote_persona", HACKER_GIST_ID)
    }

    private fun restore(filesDir: File, name: String, gistId: String): RemotePersonaCache {
        val cache = RemotePersonaCache(File(filesDir, "$name.json"))
        runCatching { cache.restore(File(filesDir, "$name.txt"), source(gistId)) }
            .onFailure { error ->
                AndroidAgentLogger.warn("Remote persona cache load failed ($gistId): type=${error.safeLogType()}")
            }
        return cache
    }

    fun refresh(context: Context): RefreshResult {
        if (defaultCache == null || hackerCache == null) init(context)
        return refresh(::fetchText)
    }

    /** Injectable transport for deterministic latest-version, disk and failure regression tests. */
    @Synchronized
    internal fun refresh(
        fetch: (String, Int) -> String,
        now: () -> Long = System::currentTimeMillis,
    ): RefreshResult {
        val default = refreshOne(defaultCache, DEFAULT_GIST_ID, fetch, now)
        val hacker = refreshOne(hackerCache, HACKER_GIST_ID, fetch, now)
        return RefreshResult(default, hacker)
    }

    private fun refreshOne(
        cache: RemotePersonaCache?,
        gistId: String,
        fetch: (String, Int) -> String,
        now: () -> Long,
    ): PersonaResult = try {
        checkNotNull(cache) { "人格缓存未初始化" }
        // Query the latest gist first; never reuse the user's old immutable raw revision.
        val metadata = JSONObject(fetch("https://api.github.com/gists/$gistId", MAX_METADATA_BYTES))
        val revision = metadata.getJSONArray("history").getJSONObject(0).getString("version")
        check(revision.matches(Regex("[a-fA-F0-9]{40}"))) { "服务器版本无效" }
        val file = metadata.getJSONObject("files").getJSONObject(FILE_NAME)
        val rawUrl = file.getString("raw_url")
        check(rawUrl.startsWith("https://gist.githubusercontent.com/U188/$gistId/raw/")) {
            "服务器文件来源不匹配"
        }
        val text = fetch(rawUrl, MAX_PERSONA_BYTES).trim()
        check(text.isNotEmpty()) { "内容为空" }
        check(text.toByteArray(Charsets.UTF_8).size <= MAX_PERSONA_BYTES) { "内容超过 64KB" }
        // If the API has a complete copy, require the raw download to match it as well.
        if (!file.optBoolean("truncated", false) && file.has("content")) {
            check(text == file.getString("content").trim()) { "下载内容与服务器当前版本不一致，请重试" }
        }
        val snapshot = RemotePersonaCache.Snapshot(text, source(gistId), revision, now())
        val changed = cache.commit(snapshot)
        AndroidAgentLogger.info("Remote persona verified ($gistId): revision=$revision sha256=${snapshot.sha256}")
        PersonaResult.Success(snapshot, changed)
    } catch (error: Exception) {
        AndroidAgentLogger.warn("Remote persona refresh failed ($gistId): type=${error.safeLogType()}")
        PersonaResult.Failure(error.message ?: error.safeLogType(), cache?.current)
    }

    private fun fetchText(url: String, maxBytes: Int): String {
        val request = Request.Builder()
            .url(url.toHttpUrl().newBuilder().addQueryParameter("_persona_refresh", UUID.randomUUID().toString()).build())
            .cacheControl(CacheControl.Builder().noCache().noStore().build())
            .header("Accept", if (url.startsWith("https://api.github.com/")) "application/vnd.github+json" else "text/plain")
            .get()
            .build()
        return httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val body = response.body
            check(body.contentLength() <= maxBytes) { "响应内容过大" }
            val source = body.source()
            source.request((maxBytes + 1).toLong())
            check(source.buffer.size <= maxBytes) { "响应内容过大" }
            body.string()
        }
    }

    fun defaultPersona(): String? = defaultCache?.current?.text
    fun hackerPersona(): String? = hackerCache?.current?.text
    fun defaultSnapshot(): RemotePersonaCache.Snapshot? = defaultCache?.current
    fun hackerSnapshot(): RemotePersonaCache.Snapshot? = hackerCache?.current

    private fun source(gistId: String): String = "https://gist.github.com/U188/$gistId"
}
