package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.voice.tts.SpeechVoice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Independent realtime catalog, verified against the list linked by SeedDuplex docs. */
internal object DoubaoRealtimeVoices {
    const val DEFAULT_ID = "zh_female_xiaohe_jupiter_bigtts"
    const val SOURCE = "https://docs.volcengine.com/api/doc/getDocDetail?type=online&LibraryCode=DoubaoVoice&DocumentCode=Tonelist-1"
    val catalog = listOf(
        SpeechVoice("zh_female_vv_jupiter_bigtts", "Vivi"),
        SpeechVoice(DEFAULT_ID, "小何"),
        SpeechVoice("zh_male_yunzhou_jupiter_bigtts", "云舟"),
        SpeechVoice("zh_male_xiaotian_jupiter_bigtts", "小天"),
    )

    fun selectedId(stored: String): String = stored.trim().takeIf { id -> catalog.any { it.id == id } } ?: DEFAULT_ID

    // Do not import the ordinary TTS uranus/mars catalogs or infer unsupported voice IDs.
    fun parseOfficialList(document: String): List<SpeechVoice> = catalog.filter { document.contains(it.id) }

    suspend fun refresh(): List<SpeechVoice> = withContext(Dispatchers.IO) {
        val client = AgentHttpClient.modelClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
        client.newCall(Request.Builder().url(SOURCE).build()).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val out = java.io.ByteArrayOutputStream()
            response.body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(out.size() + n <= 8 * 1024 * 1024) { "Catalog too large" }
                    out.write(buffer, 0, n)
                }
            }
            parseOfficialList(out.toString("UTF-8")).also { check(it.isNotEmpty()) { "Catalog unavailable" } }
        }
    }
}
