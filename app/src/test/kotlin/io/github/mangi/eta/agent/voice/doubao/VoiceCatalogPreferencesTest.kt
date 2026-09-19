package io.github.mangi.eta.agent.voice.doubao

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class VoiceCatalogPreferencesTest {
    @Test fun remembersOnlyIdAndProjectAndSupportsClearing() {
        val context = RuntimeEnvironment.getApplication()
        VoiceCatalogPreferences.clear(context)
        assertEquals("", VoiceCatalogPreferences.keyId(context))
        assertEquals("default", VoiceCatalogPreferences.project(context))
        VoiceCatalogPreferences.save(context, " key-id ", " my-project ")
        assertEquals("key-id", VoiceCatalogPreferences.keyId(context))
        assertEquals("my-project", VoiceCatalogPreferences.project(context))
        assertEquals(setOf("key_id", "project"), context.getSharedPreferences("voice_catalog_id", Context.MODE_PRIVATE).all.keys)
        VoiceCatalogPreferences.clear(context)
        assertTrue(context.getSharedPreferences("voice_catalog_id", Context.MODE_PRIVATE).all.isEmpty())
    }
}
