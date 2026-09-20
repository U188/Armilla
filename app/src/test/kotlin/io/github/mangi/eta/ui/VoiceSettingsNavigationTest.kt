package io.github.mangi.eta.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceSettingsNavigationTest {
    @Test fun syncReturnsToItsParentInsteadOfDiscardingCreateDraft() {
        assertEquals(VoiceSettingsBackTarget.SYNC_PARENT, voiceSettingsBackTarget(true, false, "create", 0))
        assertEquals(VoiceSettingsBackTarget.SYNC_PARENT, voiceSettingsBackTarget(true, false, null, 0))
    }
    @Test fun accountReturnsToVoiceList() {
        assertEquals(VoiceSettingsBackTarget.ACCOUNT_PARENT, voiceSettingsBackTarget(false, true, null, 0))
    }
    @Test fun wizardStepsAndImportReturnWithinVoiceSettings() {
        for (step in 1..2) assertEquals(VoiceSettingsBackTarget.PREVIOUS_STEP, voiceSettingsBackTarget(false, false, "create", step))
        assertEquals(VoiceSettingsBackTarget.HOME, voiceSettingsBackTarget(false, false, "create", 0))
        assertEquals(VoiceSettingsBackTarget.HOME, voiceSettingsBackTarget(false, false, "import", 0))
    }
    @Test fun onlyTheHomePageExits() {
        assertEquals(VoiceSettingsBackTarget.EXIT, voiceSettingsBackTarget(false, false, null, 0))
    }
}
