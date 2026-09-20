package io.github.mangi.eta.agent.delegation

import android.app.Application
import io.github.mangi.eta.config.Prefs
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SubAgentPreferencesTest {
    @Test fun draftSwitchPromotesOnceAndConversationsRemainIndependent() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val id = java.util.UUID.randomUUID().toString()
        assertTrue(SubAgentPreferences.enabled(id))
        SubAgentPreferences.setEnabled(null, false)
        SubAgentPreferences.promote(id)
        assertFalse(SubAgentPreferences.enabled(id))
        assertTrue(SubAgentPreferences.enabled(null))
        assertTrue(SubAgentPreferences.enabled("another-$id"))
    }
}
