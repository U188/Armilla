package io.github.mangi.eta.data.datastore

import io.github.mangi.eta.EtaApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = EtaApp::class, sdk = [36])
class FileLoggingSettingsTest {
    @Test
    fun fileLoggingRoundTripDoesNotResetOtherSettings() = runBlocking {
        val before = SettingsDataStore.settings()
        try {
            SettingsDataStore.updateSettings {
                it.copy(memoryEnabled = false, fileLoggingEnabled = false)
            }
            SettingsDataStore.setFileLoggingEnabled(true)

            val enabled = SettingsDataStore.settings()
            assertEquals(true, enabled.fileLoggingEnabled)
            assertEquals(false, enabled.memoryEnabled)

            SettingsDataStore.setFileLoggingEnabled(false)
            val disabled = SettingsDataStore.settings()
            assertEquals(false, disabled.fileLoggingEnabled)
            assertEquals(false, disabled.memoryEnabled)
        } finally {
            SettingsDataStore.updateSettings { before }
        }
    }
}
