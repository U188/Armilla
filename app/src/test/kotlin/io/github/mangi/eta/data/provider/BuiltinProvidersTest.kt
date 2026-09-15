package io.github.mangi.eta.data.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BuiltinProvidersTest {
    @Test
    fun doesNotShipPresetProviders() {
        assertTrue(BuiltinProviders.PROVIDERS.isEmpty())
        assertEquals(null, BuiltinProviders.providerById(BuiltinProviders.OPENAI_ID))
        assertTrue(BuiltinProviders.DEFAULT_SYSTEM_PROMPT.isNotBlank())
    }
}
