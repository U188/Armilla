package io.github.mangi.eta.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticLogArchiveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun writeZipIncludesFilesAndDeduplicatesNames() {
        val first = temporaryFolder.newFolder("a")
        val second = temporaryFolder.newFolder("b")
        val left = FileWithText(first, "eta-app.log", "alpha")
        val right = FileWithText(second, "eta-app.log", "beta")
        val empty = FileWithText(first, "empty.log", "")

        val bytes = ByteArrayOutputStream()
        val written = DiagnosticLogArchive.writeZip(listOf(left, empty, right), bytes)
        assertEquals(2, written)

        val entries = ZipInputStream(ByteArrayInputStream(bytes.toByteArray())).use { zip ->
            generateSequence { zip.nextEntry }.associate { entry ->
                entry.name to zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(setOf("eta-app.log", "eta-app-2.log"), entries.keys)
        assertEquals("alpha", entries.getValue("eta-app.log"))
        assertEquals("beta", entries.getValue("eta-app-2.log"))
        assertTrue(entries.values.none { it.isEmpty() })
    }

    private fun FileWithText(directory: java.io.File, name: String, text: String): java.io.File {
        val file = java.io.File(directory, name)
        if (text.isEmpty()) {
            file.writeText("")
        } else {
            file.writeText(text)
        }
        return file
    }
}
