package io.github.mangi.eta.data.repository

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BackupRestoreJournalTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun rollbackIsRepeatableAcrossMetadataRecoveryFailure() {
        val operation = temporary.newFolder("operation")
        val target = temporary.newFile("old.txt").apply { writeText("original") }
        val source = temporary.newFile("incoming.txt").apply { writeText("replacement") }
        val journal = BackupRestoreJournal(operation)
        journal.begin(listOf(target))
        journal.replace(target, source)
        assertEquals("replacement", target.readText())
        BackupRestoreJournal(operation).rollback()
        assertEquals("original", target.readText())
        BackupRestoreJournal(operation).rollback()
        assertEquals("original", target.readText())
        assertFalse(File(operation, "old/0").exists())
        // A foreign writer is not silently accepted as a completed rollback.
        target.writeText("interrupted")
        assertTrue(runCatching { BackupRestoreJournal(operation).rollback() }.isFailure)
    }

    @Test fun rollbackDeletesNewFilesButDoesNotDeleteUntouchedOriginals() {
        val operation = temporary.newFolder("operation")
        val original = temporary.newFile("original.txt").apply { writeText("original") }
        val newTarget = File(temporary.root, "new.txt")
        val source = temporary.newFile("source.txt").apply { writeText("incoming") }
        val journal = BackupRestoreJournal(operation)
        journal.begin(listOf(original, newTarget))
        journal.replace(newTarget, source)
        journal.rollback()
        journal.rollback()
        assertEquals("original", original.readText())
        assertFalse(newTarget.exists())
    }
    @Test fun rollbackAfterOriginalRenameNeedsNoFileCopy() {
        val operation = temporary.newFolder("interrupted")
        val target = temporary.newFile("target").apply { writeText("original") }
        val source = temporary.newFile("source").apply { writeText("replacement") }
        val journal = BackupRestoreJournal(operation) { step ->
            if (step == "original_moved") throw java.io.IOException("injected interruption")
        }
        journal.begin(listOf(target))
        assertTrue(runCatching { journal.replace(target, source) }.isFailure)
        assertFalse(target.exists())
        BackupRestoreJournal(operation).rollback()
        assertEquals("original", target.readText())
        assertTrue(source.exists())
    }

    @Test fun rollbackDoesNotDeleteAnUnattemptedNewTarget() {
        val operation = temporary.newFolder("unattempted")
        val target = File(temporary.root, "external-new")
        val journal = BackupRestoreJournal(operation)
        journal.begin(listOf(target))
        target.writeText("external writer")
        journal.rollback()
        assertEquals("external writer", target.readText())
    }

    @Test fun interruptedRollbackCanResumeAfterOriginalWasRenamed() {
        val operation = temporary.newFolder("resume")
        val target = temporary.newFile("resume-target").apply { writeText("old") }
        val source = temporary.newFile("resume-source").apply { writeText("new") }
        BackupRestoreJournal(operation).also { it.begin(listOf(target)); it.replace(target, source) }
        val interrupted = BackupRestoreJournal(operation) { step ->
            if (step == "original_restored") throw java.io.IOException("injected interruption")
        }
        assertTrue(runCatching { interrupted.rollback() }.isFailure)
        BackupRestoreJournal(operation).rollback()
        assertEquals("old", target.readText())
    }

    @Test fun retirementPreventsPartialCleanupFromReactivatingJournal() {
        val operation = temporary.newFolder("backup-restore")
        val journal = BackupRestoreJournal(operation)
        journal.begin(emptyList())
        journal.commit()
        val retired = BackupDurability.retire(operation)
        File(retired, "committed").delete()
        assertFalse(operation.exists())
        assertFalse(BackupRestoreJournal.hasJournal(operation))
        assertTrue(File(retired, "journal.json").exists())
    }

    @Test fun corruptCommitMarkerFailsClosed() {
        val operation = temporary.newFolder("bad-marker")
        File(operation, "committed").writeText("partial")
        assertTrue(runCatching { BackupRestoreJournal.isCommitted(operation) }.isFailure)
    }

}
