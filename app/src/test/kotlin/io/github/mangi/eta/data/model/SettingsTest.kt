package io.github.mangi.eta.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun memoryIsEnabledByDefault() {
        assertTrue(Settings().memoryEnabled)
    }

    @Test
    fun fileLoggingIsDisabledByDefault() {
        assertEquals(false, Settings().fileLoggingEnabled)
    }

    @Test
    fun appearanceUsesBackwardCompatibleDefaults() {
        assertEquals(AppearanceSettings(), Settings().appearance)
    }
}
