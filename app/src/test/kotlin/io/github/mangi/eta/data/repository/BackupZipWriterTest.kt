package io.github.mangi.eta.data.repository

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupZipWriterTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun totalBudgetIncludesManifestAndFiles() {
        val file = temporary.newFile("file").apply { writeText("1234") }
        ZipOutputStream(ByteArrayOutputStream()).use { zip ->
            val writer = BackupZipWriter(zip, totalLimit = 5)
            writer.text("eta-backup.json", "{}")
            assertTrue(runCatching { writer.file("attachments/imports/file", file) }.isFailure)
        }
    }

    @Test fun duplicateEntriesAndMissingFilesFail() {
        ZipOutputStream(ByteArrayOutputStream()).use { zip ->
            val writer = BackupZipWriter(zip)
            writer.text("eta-backup.json", "{}")
            assertTrue(runCatching { writer.text("eta-backup.json", "{}") }.isFailure)
            assertTrue(runCatching { writer.file("attachments/imports/missing", File(temporary.root, "missing")) }.isFailure)
        }
    }

    @Test fun preservesEmptyFilesAndExcludesExplicitMountDirectory() {
        val root = temporary.newFolder("workspace")
        File(root, "empty").createNewFile()
        File(root, "mounts").mkdir()
        File(root, "mounts/external").writeText("not ours")
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            BackupZipWriter(zip).directory("linux/workspace/", root, setOf("mounts"))
        }
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            assertEquals("linux/workspace/empty", zip.nextEntry.name)
            assertEquals(0, zip.readBytes().size)
            assertNull(zip.nextEntry)
        }
    }

    @Test fun refusesTooManyEntriesBeforeCreatingUnrestorableArchive() {
        ZipOutputStream(ByteArrayOutputStream()).use { zip ->
            val writer = BackupZipWriter(zip, entryLimit = 1)
            writer.text("eta-backup.json", "{}")
            assertTrue(runCatching { writer.text("second", "") }.isFailure)
        }
    }
    @Test fun refusesBytesThatDoNotMatchAttachmentManifestHash() {
        val file = temporary.newFile("changed.jpg").apply { writeText("changed") }
        ZipOutputStream(ByteArrayOutputStream()).use { zip ->
            assertTrue(runCatching {
                BackupZipWriter(zip).file("attachments/imports/changed.jpg", file, "0".repeat(64))
            }.isFailure)
        }
    }

}
