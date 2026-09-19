package io.github.mangi.eta.agent.voice.doubao

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class PersonalVoiceSlotTest {
    @Test fun unusedSlotsAreSelectableWithoutClaimingSynthesisReady() {
        val slot = PersonalVoices.Voice("S_unused", "slot", "account", catalogState = "Unknown", remaining = 15)
        assertTrue(slot.unused)
        assertTrue(slot.canTrain)
        assertFalse(slot.tts)
        assertFalse(slot.copy(status = 2).unused)
    }
    @Test fun unavailableSlotsCannotBeSelectedForTraining() {
        val slot = PersonalVoices.Voice("S_slot", "slot", "account")
        listOf("Training", "Active", "Expired", "Reclaimed").forEach { assertFalse(slot.copy(catalogState = it).canTrain) }
        assertFalse(slot.copy(status = 1).canTrain)
        assertFalse(slot.copy(status = 4).canTrain)
        assertFalse(slot.copy(remaining = 0).canTrain)
        assertTrue(slot.copy(status = 2, remaining = 3).canTrain)
    }

    @Test fun prepaidAndFreeSlotUseExistingSpeakerWithoutCustomIdentity() {
        val id = PersonalVoices.trainingIdentity(" S_free123 ")
        val body = PersonalVoices.identity(PersonalVoices.Voice(id, "免费音色", "account"))
        assertEquals("S_free123", body.getString("speaker_id"))
        assertFalse(body.has("custom_speaker_id"))
    }
    @Test fun postpaidRequiresExplicitNullAndGeneratesUniqueIdentity() {
        val id = PersonalVoices.trainingIdentity(null)
        val body = PersonalVoices.identity(PersonalVoices.Voice(id, "后付费", "account"))
        assertEquals("custom_speaker_id", body.getString("speaker_id"))
        assertEquals(id, body.getString("custom_speaker_id"))
        assertNotEquals(id, PersonalVoices.trainingIdentity(null))
    }
    @Test fun emptyOrMalformedSlotNeverFallsBackToPostpaid() {
        listOf("", "  ", "S_", "invented", "S_invalid space").forEach {
            assertThrows(IllegalArgumentException::class.java) { PersonalVoices.trainingIdentity(it) }
        }
    }
    @Test fun rejectedAndUnverifiedSlotsCannotBeUsedForSynthesis() {
        listOf(-2, -1, 0, 1, 3).forEach {
            assertFalse(PersonalVoices.Voice("S_slot", "slot", "account", status = it, models = setOf(5)).tts)
        }
    }
}
