package io.github.mangi.eta.agent.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillRunAuthorizationTest {
    private data class Entry(val id: String, val present: Boolean = true)

    private fun apply(snapshot: List<Entry>, decision: SkillRunAuthorization.Decision, previous: List<Entry> = snapshot) =
        SkillRunAuthorization.apply(snapshot, { it.id }, { it.present }, decision, previous)

    @Test fun repositoryNotReadyKeepsSnapshot() {
        val snapshot = listOf(Entry("alpha"))
        val decision = SkillRunAuthorization.decide(false, false, null, false, emptySet())
        val (next, confirmed) = apply(snapshot, decision)
        assertEquals(listOf("alpha"), next.map { it.id })
        assertFalse(confirmed)
    }

    @Test fun failedProfileLookupKeepsPreviousEvenIfItLooksEmpty() {
        val snapshot = listOf(Entry("alpha"), Entry("beta"))
        val decision = SkillRunAuthorization.decide(
            repositoryReady = true,
            profileLookupFailed = true,
            profileEnabledIds = emptySet(),
            installedLookupFailed = false,
            installedIds = emptySet(),
        )
        val (next, confirmed) = apply(snapshot, decision, previous = snapshot)
        assertEquals(listOf("alpha", "beta"), next.map { it.id })
        assertFalse(confirmed)
    }

    @Test fun missingIndexDoesNotTreatUninstallAsRevokeAll() {
        val snapshot = listOf(Entry("alpha"))
        val decision = SkillRunAuthorization.decide(
            repositoryReady = true,
            profileLookupFailed = false,
            profileEnabledIds = setOf("alpha"),
            installedLookupFailed = true,
            installedIds = emptySet(),
        )
        val (next, confirmed) = apply(snapshot, decision)
        assertEquals(listOf("alpha"), next.map { it.id })
        assertTrue(confirmed)
    }

    @Test fun emptySuccessfulIndexRevokesBecauseUninstallWasConfirmed() {
        val snapshot = listOf(Entry("alpha"))
        val decision = SkillRunAuthorization.decide(
            repositoryReady = true,
            profileLookupFailed = false,
            profileEnabledIds = setOf("alpha"),
            installedLookupFailed = false,
            installedIds = emptySet(),
        )
        val (next, confirmed) = apply(snapshot, decision)
        assertTrue(next.isEmpty())
        assertTrue(confirmed)
    }

    @Test fun deletedAssistantRevokesAll() {
        val decision = SkillRunAuthorization.decide(true, false, null, false, setOf("alpha"))
        val (next, confirmed) = apply(listOf(Entry("alpha")), decision)
        assertTrue(next.isEmpty())
        assertTrue(confirmed)
    }

    @Test fun disableOneSkillKeepsTheOthers() {
        val snapshot = listOf(Entry("alpha"), Entry("beta"))
        val decision = SkillRunAuthorization.decide(true, false, setOf("beta"), false, setOf("alpha", "beta"))
        val (next, confirmed) = apply(snapshot, decision)
        assertEquals(listOf("beta"), next.map { it.id })
        assertTrue(confirmed)
    }

    @Test fun missingSnapshotFileIsDroppedWhenAuthorizationIsKnown() {
        val snapshot = listOf(Entry("alpha", present = false), Entry("beta"))
        val decision = SkillRunAuthorization.decide(true, false, setOf("alpha", "beta"), false, setOf("alpha", "beta"))
        assertEquals(listOf("beta"), apply(snapshot, decision).first.map { it.id })
    }
}
