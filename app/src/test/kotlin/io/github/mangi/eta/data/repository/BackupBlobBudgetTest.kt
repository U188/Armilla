package io.github.mangi.eta.data.repository

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupBlobBudgetTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rejectsOversizedSparseFileBeforeAllocation() {
        val file = temporary.newFile("large.bin")
        java.io.RandomAccessFile(file, "rw").use { it.setLength(1024L * 1024 * 1024) }
        assertTrue(runCatching { BackupBlobBudget(maxFileBytes = 32).read(file) }.isFailure)
    }

    @Test fun enforcesCumulativeBudgetAcrossFiles() {
        val first = temporary.newFile("first").apply { writeBytes(ByteArray(5)) }
        val second = temporary.newFile("second").apply { writeBytes(ByteArray(5)) }
        val budget = BackupBlobBudget(maxFileBytes = 8, maxTotalBytes = 9)
        assertEquals(5, budget.read(first).size)
        assertTrue(runCatching { budget.read(second) }.isFailure)
    }

    @Test fun emptyFilesStillCountAgainstFileLimit() {
        val budget = BackupBlobBudget(maxFiles = 1)
        assertEquals(0, budget.read(temporary.newFile("empty1")).size)
        assertTrue(runCatching { budget.read(temporary.newFile("empty2")) }.isFailure)
    }

    @Test fun rejectsSymlinkAndDoesNotReadExternalTree() {
        val root = temporary.newFolder("tree")
        val external = temporary.newFile("outside").apply { writeText("secret") }
        Files.createSymbolicLink(File(root, "link").toPath(), external.toPath())
        assertTrue(runCatching { BackupBlobBudget().readTree(root) }.isFailure)
    }

    @Test fun directoryDepthCannotSilentlyTruncateSnapshot() {
        val root = temporary.newFolder("deep")
        var leaf = root
        repeat(34) { leaf = File(leaf, "d").apply { mkdir() } }
        File(leaf, "file").writeText("must not silently disappear")
        assertTrue(runCatching { BackupBlobBudget().readTree(root) }.isFailure)
    }

    @Test fun explicitSkippedDirectoryDoesNotConsumeFileBudget() {
        val root = temporary.newFolder("skills")
        File(root, ".git").mkdir()
        File(root, ".git/large").writeText("ignored")
        File(root, "SKILL.md").writeText("ok")
        val files = BackupBlobBudget(maxFileBytes = 2, maxTotalBytes = 2, maxFiles = 1)
            .readTree(root) { it.name == ".git" }
        assertEquals(setOf("SKILL.md"), files.keys)
        assertEquals("ok", files.getValue("SKILL.md").decodeToString())
    }
}
