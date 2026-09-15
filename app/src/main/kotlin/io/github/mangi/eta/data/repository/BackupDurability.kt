package io.github.mangi.eta.data.repository

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

/** A failed fsync/rename must never be reported as a successful durable commit. */
internal object BackupDurability {
    fun syncDirectory(directory: File) {
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    fun syncFile(file: File) {
        val fd = Os.open(file.absolutePath, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
    }

    fun mkdirs(directory: File) {
        if (directory.isDirectory) return
        val parent = requireNotNull(directory.parentFile)
        mkdirs(parent)
        check(directory.mkdir()) { "无法创建恢复目录：${directory.name}" }
        syncDirectory(parent)
    }

    fun sameFileSystem(source: File, target: File) {
        val ancestor = generateSequence(target.absoluteFile) { it.parentFile }.first { it.exists() }
        require(Os.stat(source.absolutePath).st_dev == Os.stat(ancestor.absolutePath).st_dev) {
            "恢复目标跨文件系统，未开始修改数据"
        }
    }

    fun move(source: File, target: File) {
        val from = requireNotNull(source.parentFile)
        val to = requireNotNull(target.parentFile)
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        syncDirectory(to)
        if (from != to) syncDirectory(from)
    }

    fun digest(file: File): String {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "恢复文件不是普通文件" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    /** Remove the directory from the recovery namespace BEFORE deleting any marker or log. */
    fun retire(operation: File): File {
        val garbage = File(operation.parentFile, "backup-retired-${UUID.randomUUID()}")
        move(operation, garbage)
        return garbage
    }
}
