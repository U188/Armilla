package io.github.mangi.eta.core

import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal object DiagnosticLogArchive {
    fun writeZip(files: List<File>, output: OutputStream): Int {
        var written = 0
        ZipOutputStream(output).use { zip ->
            val usedNames = HashSet<String>()
            files.forEach { file ->
                if (!file.isFile || file.length() <= 0L) return@forEach
                val entryName = uniqueName(file.name, usedNames)
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
                written += 1
            }
            zip.finish()
        }
        return written
    }

    private fun uniqueName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (true) {
            val candidate = "$base-$index$ext"
            if (used.add(candidate)) return candidate
            index += 1
        }
    }
}
