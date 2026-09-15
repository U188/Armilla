package io.github.mangi.eta.data.repository

import android.content.Context
import io.github.mangi.eta.agent.terminal.TerminalPrivateStorage
import java.io.File
import java.util.UUID

/** Single-conversation import is always a copy. No existing IDs or shared attachment paths are reused. */
internal object ConversationArchiveImport {
    data class Plan(val document: EtaConversationExport, val files: Map<File, File>)

    fun prepare(context: Context, exported: EtaConversationExport, staged: Map<String, File>): Plan {
        val id = "conv-${UUID.randomUUID()}"
        val root = BackupArchiveSafety.target(File(TerminalPrivateStorage.workspace(context.filesDir), "imports"), id)
        require(!root.exists()) { "导入目录已存在" }
        val files = linkedMapOf<File, File>()
        val mapping = linkedMapOf<String, String>()
        val legacyTargets = mutableMapOf<String, String>()
        if (exported.schemaVersion >= 2) {
            require(exported.attachments.size <= 10_000) { "附件数量超过限制" }
            require(exported.attachmentCount == exported.attachments.size) { "附件计数与清单不一致" }
            val entries = exported.attachments.map { it.entry }
            val refs = exported.attachments.map { it.reference }
            require(entries.distinct().size == entries.size && refs.distinct().size == refs.size) { "附件清单重复" }
            require(staged.keys == entries.toSet() + EtaConversationExport.MANIFEST_NAME) { "归档附件与清单不一致" }
            exported.attachments.forEach { attachment ->
                require(attachment.reference.startsWith("/eta-attachments/") &&
                    attachment.sha256.matches(Regex("[0-9a-f]{64}")) &&
                    attachment.size in 0..BackupArchiveSafety.TOTAL_LIMIT) { "附件引用、大小或校验字段无效" }
                val relative = attachment.reference.removePrefix("/eta-attachments/")
                BackupArchiveSafety.relativePath(relative)
                require(attachment.entry == "attachments/imports/$relative") { "附件归档路径不匹配" }
                val source = staged.getValue(attachment.entry)
                require(source.length() == attachment.size && BackupDurability.digest(source) == attachment.sha256) { "附件大小或校验不匹配" }
                val target = BackupArchiveSafety.target(root, relative)
                mapping[attachment.reference] = target.absolutePath
                files[target] = source
            }
        } else {
            require(staged.keys.all { it == EtaConversationExport.MANIFEST_NAME ||
                it.startsWith("attachments/imports/") || it.startsWith("attachments/chat-images/${exported.conversation.id}/") }) {
                "旧会话归档包含其他会话或工作区数据"
            }
        }
        val used = hashSetOf<String>()
        val rewritten = ConversationArchiveMedia.transform(exported) { reference ->
            when {
                !ConversationArchiveMedia.isLocal(reference) -> reference
                exported.schemaVersion >= 2 -> {
                    used += reference
                    mapping[reference] ?: error("消息引用了清单外的本地附件")
                }
                else -> mapping.getOrPut(reference) {
                    val entry = legacyEntry(reference, exported.conversation.id)
                        ?: error("旧归档含无法迁移的本地附件引用")
                    legacyTargets.getOrPut(entry) {
                        val source = staged[entry] ?: error("旧归档附件缺失：${entry.substringAfterLast('/')}")
                        val relative = "${UUID.randomUUID()}/${entry.substringAfterLast('/')}"
                        val target = BackupArchiveSafety.target(root, relative)
                        files[target] = source
                        target.absolutePath
                    }
                }
            }
        }
        if (exported.schemaVersion >= 2) require(used == mapping.keys) { "附件清单包含未引用文件" }
        val copied = rewritten.copy(
            conversation = rewritten.conversation.copy(id = id, folderId = "",
                appliedRuntimeRunIdsJson = "[]", isPinned = false),
            messages = rewritten.messages.mapIndexed { index, message -> message.copy(
                id = "import-${UUID.randomUUID()}", conversationId = id, sortIndex = index,
            ) },
            contextCheckpoint = rewritten.contextCheckpoint?.copy(conversationId = id),
            attachmentCount = files.size,
        )
        return Plan(copied, files)
    }

    internal fun legacyEntry(reference: String, conversationId: String): String? {
        val path = reference.removePrefix("file://")
        val suffix = Regex("^/data/(?:user/\\d+|data)/[^/]+/(.*)$").matchEntire(path)?.groupValues?.get(1) ?: return null
        val entry = when {
            suffix.startsWith("cache/eta-chat-images/$conversationId/") -> "attachments/chat-images/" + suffix.removePrefix("cache/eta-chat-images/")
            suffix.startsWith("files/terminal-user/workspace/imports/") -> "attachments/imports/" + suffix.removePrefix("files/terminal-user/workspace/imports/")
            suffix.startsWith("files/terminal/workspace/imports/") -> "attachments/imports/" + suffix.removePrefix("files/terminal/workspace/imports/")
            else -> return null
        }
        BackupArchiveSafety.relativePath(entry)
        return entry
    }
}
