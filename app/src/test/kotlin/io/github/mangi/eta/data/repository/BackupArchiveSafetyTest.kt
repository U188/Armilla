package io.github.mangi.eta.data.repository

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveSafetyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rejectsAbsoluteTraversalAndAmbiguousNames() {
        listOf("../escape", "/absolute", "a/../b", "a//b", "a\\b", "C:/x", "a/./b", "a\u0000b").forEach {
            assertTrue(it, runCatching { BackupArchiveSafety.relativePath(it) }.isFailure)
        }
        assertEquals("attachments/chat-images/a.png", BackupArchiveSafety.relativePath("attachments/chat-images/a.png"))
    }

    @Test fun boundedCopyRejectsOneByteOverLimit() {
        val output = ByteArrayOutputStream()
        assertEquals(4L, BackupArchiveSafety.copyLimited(ByteArrayInputStream(ByteArray(4)), output, 4))
        assertTrue(runCatching { BackupArchiveSafety.copyLimited(ByteArrayInputStream(ByteArray(5)), output, 4) }.isFailure)
    }

    @Test fun refusesExistingSymlinkAncestor() {
        val root = temporary.newFolder("root")
        val outside = temporary.newFolder("outside")
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        assertTrue(runCatching { BackupArchiveSafety.target(root, "link/file") }.isFailure)
    }

    @Test fun stagesZeroByteFilesAndRejectsTruncatedCentralDirectory() {
        val archive = temporary.newFile("valid.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("eta-backup.json")); zip.write("{}".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("attachments/imports/empty")); zip.closeEntry()
        }
        val staged = BackupArchiveSafety.stageZip(archive, File(temporary.root, "stage"))
        assertEquals(0L, staged.getValue("attachments/imports/empty").length())
        val truncated = temporary.newFile("truncated.zip")
        truncated.writeBytes(archive.readBytes().dropLast(22).toByteArray())
        assertTrue(runCatching { BackupArchiveSafety.stageZip(truncated, File(temporary.root, "bad-stage")) }.isFailure)
    }

    @Test fun rejectsArchivesWithTwoManifests() {
        val archive = temporary.newFile("ambiguous.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            listOf("eta-backup.json", "eta-conversation.json").forEach { name ->
                zip.putNextEntry(ZipEntry(name)); zip.write("{}".toByteArray()); zip.closeEntry()
            }
        }
        assertTrue(runCatching { BackupArchiveSafety.stageZip(archive, File(temporary.root, "ambiguous-stage")) }.isFailure)
    }
}
