package io.github.mangi.eta.agent.voice.doubao

import android.app.Application
import android.content.Context
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class VoiceCatalogSecretStoreTest {
    @Test fun encryptedSecretSurvivesStoreRecreationAndIsBoundToKeyId() {
        val context = RuntimeEnvironment.getApplication()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val store = VoiceCatalogSecretStore(context) { key }
        store.clear()
        store.save("ak-a", "test-secret-value")
        val prefs = context.getSharedPreferences("voice_catalog_secret", Context.MODE_PRIVATE)
        val first = prefs.getString("secret", null)!!
        assertFalse(first.contains("test-secret-value"))
        assertEquals("test-secret-value", VoiceCatalogSecretStore(context) { key }.read("ak-a"))
        assertNull(store.read("ak-b"))
        store.save("ak-a", "test-secret-value")
        assertNotEquals(first, prefs.getString("secret", null))
        prefs.edit().putString("secret", "broken").commit()
        assertNull(store.read("ak-a"))
        store.save("ak-a", "replacement")
        store.clear()
        assertNull(store.read("ak-a"))
        assertTrue(prefs.all.isEmpty())
    }
}
