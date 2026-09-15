package io.github.mangi.eta.agent.media

import android.content.Context
import android.util.Base64
import io.github.mangi.eta.agent.device.AgentFileReferenceGateway
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import java.io.File
import java.util.UUID

/**
 * 将原始附件落盘，UI 预览和模型能力相互独立。纯文本模型只能接收路径，不能通过 read_image 获得视觉能力。
 * 不写入用户工作区；删除会话或变成孤儿后清理，避免缓存无限涨。
 */
internal class AgentChatImageCache(context: Context) {
    private val root = File(context.applicationContext.cacheDir, CACHE_DIRECTORY)

    fun stage(
        conversationId: String,
        bytes: ByteArray,
        displayName: String,
        maxBytes: Int = MAX_AGENT_IMAGE_BYTES,
    ): AgentFileReference? {
        if (bytes.isEmpty() || bytes.size > maxBytes) return null
        val conversationDir = conversationDir(conversationId) ?: return null
        val safeName = AgentFileReferenceGateway.safeImportName(displayName)
        val destination = File(conversationDir, "${UUID.randomUUID()}-$safeName")
        destination.writeBytes(bytes)
        if (!destination.isFile) return null
        return AgentFileReference(
            displayName = safeName,
            absolutePath = destination.absolutePath,
            kind = AgentFileReferenceKind.File,
        )
    }

    fun stageFromFile(
        conversationId: String,
        source: File,
        displayName: String,
        maxBytes: Int = MAX_AGENT_VIDEO_BYTES,
    ): AgentFileReference? {
        if (!source.isFile || source.length() !in 1L..maxBytes.toLong()) return null
        val conversationDir = conversationDir(conversationId) ?: return null
        val safeName = AgentFileReferenceGateway.safeImportName(displayName)
        val destination = File(conversationDir, "${UUID.randomUUID()}-$safeName")
        source.copyTo(destination, overwrite = true)
        if (!destination.isFile || destination.length() != source.length()) {
            destination.delete()
            return null
        }
        return AgentFileReference(
            displayName = safeName,
            absolutePath = destination.absolutePath,
            kind = AgentFileReferenceKind.File,
        )
    }

    private fun conversationDir(conversationId: String): File? {
        val directory = File(root, sanitize(conversationId)).apply { mkdirs() }
        return directory.takeIf { it.isDirectory }
    }

    fun copyConversation(fromId: String, toId: String) {
        if (fromId == toId) return
        val source = File(root, sanitize(fromId))
        if (!source.isDirectory) return
        val target = File(root, sanitize(toId))
        if (target.exists()) target.deleteRecursively()
        source.copyRecursively(target, overwrite = true)
    }

    fun rewriteCachedPath(value: String, fromId: String, toId: String): String {
        if (value.isEmpty() || fromId == toId) return value
        val fromDir = File(root, sanitize(fromId)).absolutePath
        val toDir = File(root, sanitize(toId)).absolutePath
        return value.replace(fromDir, toDir)
    }

    fun deleteConversation(conversationId: String) {
        val directory = File(root, sanitize(conversationId))
        if (directory.exists()) directory.deleteRecursively()
        pruneEmptyRoot()
    }

    fun deleteOrphans(activeConversationIds: Set<String>) {
        if (!root.isDirectory) return
        val active = activeConversationIds.mapTo(mutableSetOf(), ::sanitize)
        root.listFiles()?.forEach { child ->
            if (child.isDirectory && child.name !in active) {
                child.deleteRecursively()
            }
        }
        pruneEmptyRoot()
    }

    private fun pruneEmptyRoot() {
        if (root.isDirectory && root.list().isNullOrEmpty()) root.delete()
    }

    companion object {
        const val CACHE_DIRECTORY = "eta-chat-images"

        fun decodeImageBytes(value: String): ByteArray? {
            val trimmed = value.trim()
            if (!trimmed.startsWith("data:image/", ignoreCase = true)) return null
            val marker = trimmed.indexOf("base64,", ignoreCase = true)
            if (marker < 0) return null
            return runCatching {
                Base64.decode(trimmed.substring(marker + "base64,".length), Base64.DEFAULT)
            }.getOrNull()
        }

        fun readBytes(value: String): ByteArray? {
            decodeImageBytes(value)?.let { return it }
            val path = value.trim().removePrefix("file://")
            if (!path.startsWith("/")) return null
            val file = File(path)
            if (!file.isFile || file.length() !in 1L..MAX_AGENT_IMAGE_BYTES.toLong()) return null
            return runCatching { file.readBytes() }.getOrNull()
                ?.takeIf { it.isNotEmpty() && it.size <= MAX_AGENT_IMAGE_BYTES }
        }

        private fun sanitize(conversationId: String): String =
            AgentFileReferenceGateway.safeImportName(conversationId)
    }
}
