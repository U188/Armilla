package io.github.mangi.eta.data.repository

import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Export uses the same byte/count boundary as import; never silently skip a missing attachment. */
internal class BackupZipWriter(
    private val zip: ZipOutputStream,
    private val totalLimit: Long = BackupArchiveSafety.TOTAL_LIMIT,
    private val entryLimit: Int = BackupArchiveSafety.ENTRY_LIMIT,
) {
    private var total = 0L
    private val names = hashSetOf<String>()
    private var visited = 0

    private fun reserve(name: String, size: Long) {
        val normalized = BackupArchiveSafety.relativePath(name)
        require(normalized == name && names.size < entryLimit && names.add(name)) { "导出条目过多、重复或路径无效" }
        require(size >= 0 && size <= totalLimit - total) { "导出大小超过可恢复的上限" }
    }

    fun text(name: String, text: String) {
        val bytes = text.toByteArray()
        require(bytes.size.toLong() <= BackupArchiveSafety.MANIFEST_LIMIT) { "备份清单过大" }
        reserve(name, bytes.size.toLong())
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
        total += bytes.size
    }

    fun file(name: String, file: File, expectedSha256: String? = null) {
        require(file.isFile && !Files.isSymbolicLink(file.toPath())) { "备份附件缺失或不是普通文件：${file.name}" }
        val expected = file.length()
        val modified = file.lastModified()
        reserve(name, expected)
        zip.putNextEntry(ZipEntry(name))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val actual = file.inputStream().use { input ->
            java.security.DigestInputStream(input, digest).use { BackupArchiveSafety.copyLimited(it, zip, expected) }
        }
        if (expectedSha256 != null) {
            val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(sha256 == expectedSha256) { "附件在导出时发生变化" }
        }
        require(actual == expected && file.length() == expected && file.lastModified() == modified) { "备份文件在读取时发生变化" }
        zip.closeEntry()
        total += actual
    }

    fun stream(name: String, write: (OutputStream) -> Unit): Long {
        val normalized = BackupArchiveSafety.relativePath(name)
        require(normalized == name && names.size < entryLimit && names.add(name)) { "导出条目过多、重复或路径无效" }
        zip.putNextEntry(ZipEntry(name))
        val remaining = totalLimit - total
        val counting = object : OutputStream() {
            var size = 0L
            override fun write(b: Int) {
                require(size + 1 <= remaining) { "导出大小超过可恢复的上限" }
                zip.write(b)
                size++
            }
            override fun write(b: ByteArray, off: Int, len: Int) {
                require(len >= 0 && len.toLong() <= remaining - size) { "导出大小超过可恢复的上限" }
                zip.write(b, off, len)
                size += len
            }
        }
        write(counting)
        zip.closeEntry()
        total += counting.size
        return counting.size
    }

    fun directory(prefix: String, root: File, skipNames: Set<String> = emptySet()) {
        if (!root.exists()) return
        fun visit(file: File, depth: Int) {
            require(++visited <= entryLimit && depth <= 64) { "备份目录过深或条目过多" }
            if (file != root && file.name in skipNames) return
            require(!Files.isSymbolicLink(file.toPath())) { "备份目录不支持符号链接：${file.name}" }
            if (file.isDirectory) {
                Files.newDirectoryStream(file.toPath()).use { entries ->
                    for (entry in entries) visit(entry.toFile(), depth + 1)
                }
            } else {
                this@BackupZipWriter.file(prefix + file.relativeTo(root).invariantSeparatorsPath, file)
            }
        }
        visit(root, 0)
    }
}
