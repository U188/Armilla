package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import okhttp3.Request

/**
 * 内置助手人格的服务器下发覆盖层。
 *
 * 设计：编译进 APK 的内置人格始终是兜底；gist 内容是纯文本人格原文（无 JSON、无版本号、无转义），
 * 用户手动触发 [refresh] 拉取一次，成功即落盘缓存并即时生效。启动时 [init] 只从本地缓存恢复，不触网。
 * 拉取失败或首次无缓存时对应人格返回 null，构建链路退回编译常量。
 */
internal object RemotePersonaStore {
    private const val HACKER_GIST_RAW_URL =
        "https://gist.githubusercontent.com/U188/1231d6d69cb045a33e8cd434f1e09aec/raw/gistfile1.txt"
    private const val DEFAULT_GIST_RAW_URL =
        "https://gist.githubusercontent.com/U188/9a2e1239ac8af01d6f6273b09f31fc31/raw/a78e1d4bbc2ce7387bae33d12f82e7a1b258dbf4/gistfile1.txt"

    private const val HACKER_CACHE_FILE_NAME = "remote_persona.txt"
    private const val DEFAULT_CACHE_FILE_NAME = "remote_persona_default.txt"
    private const val MAX_PERSONA_BYTES = 64 * 1024

    /** 单个人格的拉取结果。 */
    sealed interface PersonaResult {
        data class Success(val chars: Int) : PersonaResult
        data class Failure(val reason: String) : PersonaResult
    }

    /** 小蝶与小枫各自的拉取结果。 */
    data class RefreshResult(
        val default: PersonaResult,
        val hacker: PersonaResult,
    )

    @Volatile
    private var cachedHackerPersona: String? = null

    @Volatile
    private var cachedDefaultPersona: String? = null

    /** 从本地缓存文件恢复到内存；启动早期同步调用，不触网。 */
    fun init(context: Context) {
        runCatching {
            cachedHackerPersona = readCache(cacheFile(context, HACKER_CACHE_FILE_NAME))
            cachedDefaultPersona = readCache(cacheFile(context, DEFAULT_CACHE_FILE_NAME))
        }.onFailure { throwable ->
            AndroidAgentLogger.warn("Remote persona cache load failed: type=${throwable.safeLogType()}")
        }
    }

    /** 手动拉取两个内置人格：返回小蝶与小枫各自的结果。阻塞调用，请在 IO 线程执行。 */
    fun refresh(context: Context): RefreshResult {
        val hacker = refreshOne(context, HACKER_GIST_RAW_URL, HACKER_CACHE_FILE_NAME) { text ->
            cachedHackerPersona = text
        }
        val default = refreshOne(context, DEFAULT_GIST_RAW_URL, DEFAULT_CACHE_FILE_NAME) { text ->
            cachedDefaultPersona = text
        }
        return RefreshResult(default = default, hacker = hacker)
    }

    private fun refreshOne(
        context: Context,
        url: String,
        cacheFileName: String,
        onLoaded: (String) -> Unit,
    ): PersonaResult {
        if (!url.startsWith("https://")) return PersonaResult.Failure("未配置远程地址")
        return runCatching {
            val request = Request.Builder().url(url).get().build()
            AgentHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return PersonaResult.Failure("HTTP ${response.code}")
                }
                val body = response.body ?: return PersonaResult.Failure("内容为空")
                // 先按声明长度拦截超大响应，避免整体读入内存再拒绝。
                val declared = body.contentLength()
                if (declared > MAX_PERSONA_BYTES) return PersonaResult.Failure("内容超过 64KB")
                // 有界读取：最多读 MAX_PERSONA_BYTES+1 字节，超出即判定过大。
                val source = body.source()
                source.request((MAX_PERSONA_BYTES + 1).toLong())
                if (source.buffer.size > MAX_PERSONA_BYTES) return PersonaResult.Failure("内容超过 64KB")
                val text = body.string().trim()
                if (text.isEmpty()) return PersonaResult.Failure("内容为空")
                onLoaded(text)
                val persisted = runCatching { cacheFile(context, cacheFileName).writeText(text) }
                    .onFailure { throwable ->
                        AndroidAgentLogger.warn("Remote persona cache write failed: type=${throwable.safeLogType()}")
                    }
                    .isSuccess
                AndroidAgentLogger.info("Remote persona updated: ${text.length} chars, persisted=$persisted")
                if (persisted) {
                    PersonaResult.Success(text.length)
                } else {
                    PersonaResult.Failure("已生效但缓存写入失败，重启后可能丢失")
                }
            }
        }.getOrElse { throwable ->
            AndroidAgentLogger.warn("Remote persona fetch failed: type=${throwable.safeLogType()}")
            PersonaResult.Failure(throwable.message ?: throwable.safeLogType())
        }
    }

    /** 当前生效的小枫人格覆盖；无有效覆盖时返回 null，由调用方退回编译常量。 */
    fun hackerPersona(): String? = cachedHackerPersona?.takeIf { it.isNotBlank() }

    /** 当前生效的小蝶人格覆盖；无有效覆盖时返回 null，由调用方退回编译常量。 */
    fun defaultPersona(): String? = cachedDefaultPersona?.takeIf { it.isNotBlank() }

    private fun readCache(file: File): String? =
        if (!file.isFile) null else file.readText().trim().takeIf { it.isNotEmpty() }

    private fun cacheFile(context: Context, name: String): File = File(context.filesDir, name)
}
