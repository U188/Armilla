package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.core.safeLogType
import java.io.File
import okhttp3.Request

/**
 * 小枫人格的服务器下发覆盖层。
 *
 * 设计：编译进 APK 的 [io.github.mangi.eta.data.model.AssistantPrompt.HACKER_PERSONA] 始终是兜底；
 * gist 内容是纯文本人格原文（无 JSON、无版本号、无转义），用户手动触发 [refresh] 拉取一次，
 * 成功即落盘缓存并即时生效。启动时 [init] 只从本地缓存恢复，不触网。
 * 拉取失败或首次无缓存时 [hackerPersona] 返回 null，构建链路退回编译常量。
 */
internal object RemotePersonaStore {
    private const val GIST_RAW_URL =
        "https://gist.githubusercontent.com/U188/1231d6d69cb045a33e8cd434f1e09aec/raw/gistfile1.txt"

    private const val CACHE_FILE_NAME = "remote_persona.txt"
    private const val MAX_PERSONA_BYTES = 64 * 1024

    /** 手动拉取结果，供 UI 反馈。 */
    sealed interface RefreshResult {
        data class Success(val chars: Int) : RefreshResult
        data class Failure(val reason: String) : RefreshResult
    }

    @Volatile
    private var cachedPersona: String? = null

    /** 从本地缓存文件恢复到内存；启动早期同步调用，不触网。 */
    fun init(context: Context) {
        runCatching {
            val file = cacheFile(context)
            if (!file.isFile) return
            val text = file.readText().trim()
            if (text.isNotEmpty()) cachedPersona = text
        }.onFailure { throwable ->
            AndroidAgentLogger.warn("Remote persona cache load failed: type=${throwable.safeLogType()}")
        }
    }

    /** 手动拉取一次 gist 纯文本：成功即更新内存并落盘。阻塞调用，请在 IO 线程执行。 */
    fun refresh(context: Context): RefreshResult {
        if (!GIST_RAW_URL.startsWith("https://")) return RefreshResult.Failure("未配置远程地址")
        return runCatching {
            val request = Request.Builder().url(GIST_RAW_URL).get().build()
            AgentHttpClient.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return RefreshResult.Failure("HTTP ${response.code}")
                }
                val body = response.body ?: return RefreshResult.Failure("内容为空")
                // 先按声明长度拦截超大响应，避免整体读入内存再拒绝。
                val declared = body.contentLength()
                if (declared > MAX_PERSONA_BYTES) return RefreshResult.Failure("内容超过 64KB")
                // 有界读取：最多读 MAX_PERSONA_BYTES+1 字节，超出即判定过大。
                val source = body.source()
                source.request((MAX_PERSONA_BYTES + 1).toLong())
                if (source.buffer.size > MAX_PERSONA_BYTES) return RefreshResult.Failure("内容超过 64KB")
                val text = body.string().trim()
                if (text.isEmpty()) return RefreshResult.Failure("内容为空")
                cachedPersona = text
                val persisted = runCatching { cacheFile(context).writeText(text) }
                    .onFailure { throwable ->
                        AndroidAgentLogger.warn("Remote persona cache write failed: type=${throwable.safeLogType()}")
                    }
                    .isSuccess
                AndroidAgentLogger.info("Remote persona updated: ${text.length} chars, persisted=$persisted")
                if (persisted) {
                    RefreshResult.Success(text.length)
                } else {
                    RefreshResult.Failure("已生效但缓存写入失败，重启后可能丢失")
                }
            }
        }.getOrElse { throwable ->
            AndroidAgentLogger.warn("Remote persona fetch failed: type=${throwable.safeLogType()}")
            RefreshResult.Failure(throwable.message ?: throwable.safeLogType())
        }
    }

    /** 当前生效的小枫人格覆盖；无有效覆盖时返回 null，由调用方退回编译常量。 */
    fun hackerPersona(): String? = cachedPersona?.takeIf { it.isNotBlank() }

    private fun cacheFile(context: Context): File = File(context.filesDir, CACHE_FILE_NAME)
}
