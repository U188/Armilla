package io.github.mangi.eta.data.repository

import android.util.AtomicFile
import java.io.File
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject

internal fun durableText(file: File, text: String) {
    BackupDurability.mkdirs(requireNotNull(file.parentFile))
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(text.toByteArray())
        stream.fd.sync()
        atomic.finishWrite(stream)
    } catch (failure: Throwable) {
        atomic.failWrite(stream)
        throw failure
    }
    BackupDurability.syncDirectory(requireNotNull(file.parentFile))
}

/** Same-filesystem undo by rename; no full-size copy allocation is required during rollback. */
internal class BackupRestoreJournal(
    private val directory: File,
    private val afterStep: (String) -> Unit = {},
) {
    companion object {
        fun hasJournal(directory: File): Boolean =
            listOf("journal.json", "journal.json.bak", "journal.json.new").any { File(directory, it).exists() }

        fun isCommitted(directory: File): Boolean {
            if (!File(directory, "committed").exists() && !File(directory, "committed.bak").exists()) return false
            return AtomicFile(File(directory, "committed")).openRead().use {
                require(BackupArchiveSafety.readText(it, 32) == "committed\n") { "恢复提交标记损坏" }
                true
            }
        }
    }

    private val file = File(directory, "journal.json")
    private fun read(): JSONArray = AtomicFile(file).openRead().use {
        JSONArray(BackupArchiveSafety.readText(it))
    }
    private val entriesByTarget: Map<String, JSONObject> by lazy {
        val entries = read()
        (0 until entries.length()).associate { index ->
            val item = entries.getJSONObject(index)
            item.getString("target") to item
        }
    }

    fun begin(targets: List<File>) {
        val entries = JSONArray()
        require(targets.distinct().size == targets.size) { "恢复目标重复" }
        // Complete all validation/checksums before publishing the journal or modifying originals.
        targets.forEachIndexed { index, target ->
            require(target.absolutePath == target.canonicalPath) { "恢复目标路径发生链接变化" }
            BackupArchiveSafety.target(requireNotNull(target.parentFile), target.name)
            require(!Files.isSymbolicLink(target.toPath()) && (!target.exists() || target.isFile)) { "恢复目标不是普通文件" }
            BackupDurability.sameFileSystem(directory, target)
            val digest = if (target.exists()) BackupDurability.digest(target) else ""
            if (target.exists()) BackupDurability.syncFile(target)
            entries.put(JSONObject().put("target", target.absolutePath)
                .put("backup", "old/$index").put("intent", "intent/$index")
                .put("existed", target.exists()).put("sha256", digest))
        }
        val text = entries.toString()
        require(text.toByteArray().size <= BackupArchiveSafety.MANIFEST_LIMIT) { "恢复日志超过大小限制" }
        durableText(file, text)
        afterStep("journal_durable")
    }

    fun replace(target: File, source: File) {
        val item = entriesByTarget.getValue(target.absolutePath)
        require(target.absolutePath == target.canonicalPath) { "恢复目标路径发生链接变化" }
        BackupArchiveSafety.target(requireNotNull(target.parentFile), target.name)
        BackupDurability.sameFileSystem(directory, source)
        BackupDurability.sameFileSystem(directory, target)
        BackupDurability.syncFile(source)
        val backup = File(directory, item.getString("backup"))
        require(!backup.exists()) { "同一恢复条目不能重复应用" }
        if (item.getBoolean("existed")) {
            require(BackupDurability.digest(target) == item.getString("sha256")) { "恢复目标在预检后被修改" }
            val attributes = android.system.Os.stat(target.absolutePath)
            val workspaceUid = android.system.Os.stat(directory.absolutePath).st_uid
            require(attributes.st_uid == workspaceUid) { "恢复目标不属于应用用户，拒绝改变其所有权" }
            android.system.Os.chmod(source.absolutePath, attributes.st_mode and 511)
            BackupDurability.syncFile(source)
        } else require(!target.exists()) { "恢复目标在预检后被创建" }
        val incomingDigest = BackupDurability.digest(source)
        // Intent is durable before any original is moved. Unattempted entries must not be undone.
        durableText(File(directory, item.getString("intent")), incomingDigest)
        afterStep("intent_durable")
        if (item.getBoolean("existed")) {
            BackupDurability.mkdirs(requireNotNull(backup.parentFile))
            BackupDurability.move(target, backup)
            afterStep("original_moved")
        }
        BackupDurability.mkdirs(requireNotNull(target.parentFile))
        BackupDurability.move(source, target)
        afterStep("replacement_installed")
    }

    fun rollback() {
        val entries = read()
        for (index in entries.length() - 1 downTo 0) {
            val entry = entries.getJSONObject(index)
            val target = File(entry.getString("target"))
            require(target.absolutePath == target.canonicalPath) { "恢复目标路径发生链接变化" }
            BackupArchiveSafety.target(requireNotNull(target.parentFile), target.name)
            val backup = File(directory, BackupArchiveSafety.relativePath(entry.getString("backup")))
            val intent = entry.optString("intent").takeIf { it.isNotBlank() }
                ?.let { File(directory, BackupArchiveSafety.relativePath(it)) }
            if (intent != null && !intent.exists() && !File(intent.path + ".bak").exists() && !backup.exists()) continue
            if (backup.exists()) {
                // Original inode retains mode/owner/timestamps. No copy, even on a nearly full disk.
                if (entry.has("sha256")) require(BackupDurability.digest(backup) == entry.getString("sha256")) { "回滚原件校验失败" }
                BackupDurability.mkdirs(requireNotNull(target.parentFile))
                BackupDurability.move(backup, target)
                afterStep("original_restored")
            } else if (!entry.getBoolean("existed")) {
                if (target.exists() && intent != null) {
                    val incomingDigest = AtomicFile(intent).openRead().use { BackupArchiveSafety.readText(it, 64) }
                    require(BackupDurability.digest(target) == incomingDigest) { "新文件在恢复后被其他写入修改，拒绝删除" }
                }
                if (Files.deleteIfExists(target.toPath())) BackupDurability.syncDirectory(requireNotNull(target.parentFile))
            } else {
                // A prior rollback may have renamed the original and crashed before metadata recovery.
                // Never mistake a missing backup and modified target for a successful rollback.
                require(entry.has("sha256") && target.isFile && BackupDurability.digest(target) == entry.getString("sha256")) {
                    "回滚原件缺失或目标已改变；保留日志，禁止继续写入"
                }
            }
        }
    }

    fun commit() = durableText(File(directory, "committed"), "committed\n")
}
