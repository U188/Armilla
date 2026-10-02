package io.github.mangi.eta.data.repository

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import org.json.JSONObject

/** Single-file transaction: publish to memory only after the persisted snapshot is read back. */
internal class RemotePersonaCache(private val file: File) {
    data class Snapshot(
        val text: String,
        val source: String,
        val revision: String,
        val checkedAt: Long,
    ) {
        val sha256: String get() = digest(text)
    }

    @Volatile
    var current: Snapshot? = null
        private set

    @Synchronized
    fun restore(legacyFile: File, source: String) {
        current = null
        current = if (file.isFile) {
            read()
        } else if (legacyFile.isFile) {
            legacyFile.readText().trim().takeIf { it.isNotEmpty() }?.let {
                Snapshot(it, source, "", 0L)
            }
        } else null
    }

    @Synchronized
    fun commit(snapshot: Snapshot): Boolean {
        require(snapshot.text.isNotBlank()) { "内容为空" }
        val changed = current?.sha256 != snapshot.sha256
        val json = JSONObject()
            .put("text", snapshot.text)
            .put("source", snapshot.source)
            .put("revision", snapshot.revision)
            .put("checkedAt", snapshot.checkedAt)
            .put("sha256", snapshot.sha256)
        val staging = File(file.path + ".new")
        try {
            FileOutputStream(staging).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8))
                output.flush()
                output.fd.sync()
            }
            check(read(staging) == snapshot) { "缓存写入校验失败" }
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (error: Exception) {
            if (staging.isFile) staging.delete()
            throw error
        }
        val persisted = read()
        check(persisted == snapshot) { "缓存回读校验失败，未切换人格" }
        current = persisted
        return changed
    }

    private fun read(target: File = file): Snapshot {
        val json = JSONObject(target.readText(Charsets.UTF_8))
        val snapshot = Snapshot(
            text = json.getString("text"),
            source = json.getString("source"),
            revision = json.getString("revision"),
            checkedAt = json.getLong("checkedAt"),
        )
        check(snapshot.text.isNotBlank() && snapshot.sha256 == json.getString("sha256")) {
            "缓存内容校验失败"
        }
        return snapshot
    }

    companion object {
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
