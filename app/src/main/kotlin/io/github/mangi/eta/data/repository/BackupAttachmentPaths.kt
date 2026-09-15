package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.media.AgentChatImageCache
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Only structured attachment references are portable; arbitrary prose/code is never rewritten. */
internal object BackupAttachmentPaths {
    fun rewriteJson(raw: String, context: Context): String = rewrite(raw, context, false)
    fun rewriteImages(raw: String, context: Context): String = rewrite(raw, context, true)

    private fun rewrite(raw: String, context: Context, images: Boolean): String {
        val parsed = runCatching { JSONTokener(raw).nextValue() }.getOrNull() ?: return raw
        val path: (String) -> String = { value -> relocate(value, context.packageName,
            File(context.cacheDir, AgentChatImageCache.CACHE_DIRECTORY),
            File(TerminalPrivateStorage.workspace(context.filesDir), "imports")) }
        return transform(parsed, path, images).toString()
    }

    internal fun relocate(value: String, packageName: String, imagesRoot: File, importsRoot: File): String {
        val prefix = if (value.startsWith("file://")) "file://" else ""
        val raw = value.removePrefix(prefix)
        val pattern = Regex("^/data/(?:user/\\d+|data)/" + Regex.escape(packageName) + "/(.*)$")
        val suffix = pattern.matchEntire(raw)?.groupValues?.get(1) ?: return value
        val roots = listOf(
            "cache/eta-chat-images/" to imagesRoot,
            "files/terminal/workspace/imports/" to importsRoot,
            "files/terminal-user/workspace/imports/" to importsRoot,
        )
        val (old, root) = roots.firstOrNull { suffix.startsWith(it.first) } ?: return value
        val relative = suffix.removePrefix(old)
        if (runCatching { BackupArchiveSafety.relativePath(relative) }.isFailure) return value
        return prefix + File(root, relative).absolutePath
    }

    internal fun transform(value: Any, path: (String) -> String, images: Boolean = false): Any = when (value) {
        is JSONArray -> JSONArray().also { output ->
            for (i in 0 until value.length()) {
                val item = value.get(i)
                output.put(if (images && item is String) path(item) else transform(item, path, images))
            }
        }
        is JSONObject -> JSONObject(value.toString()).also { output ->
            val type = value.optString("type")
            val keys = value.keys().asSequence().toList()
            keys.forEach { key ->
                val item = value.get(key)
                when {
                    images && key in setOf("preview", "source", "dataUrl") && item is String -> output.put(key, path(item))
                    type in setOf("image_file", "video_file") && key == "path" && item is String -> output.put(key, path(item))
                    key in setOf("contentJson", "imagesJson") && item is String -> {
                        val embedded = runCatching { JSONTokener(item).nextValue() }.getOrNull()
                        if (embedded is JSONArray || embedded is JSONObject) {
                            output.put(key, transform(embedded, path, key == "imagesJson").toString())
                        }
                    }
                    item is JSONArray || item is JSONObject -> output.put(key, transform(item, path, images))
                }
            }
        }
        else -> value
    }
}
