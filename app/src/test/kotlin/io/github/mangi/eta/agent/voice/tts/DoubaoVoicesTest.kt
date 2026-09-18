package io.github.mangi.eta.agent.voice.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DoubaoVoicesTest {
    @Test fun officialViviIsDefault() {
        assertTrue(DoubaoVoices.contains("zh_female_vv_uranus_bigtts"))
        assertEquals("Vivi 2.0", DoubaoVoices.displayName("zh_female_vv_uranus_bigtts"))
        assertEquals("zh_female_vv_uranus_bigtts", DoubaoVoices.DEFAULT_ID)
        assertTrue(DoubaoVoices.catalog.size >= 80)
        assertTrue(DoubaoVoices.catalog.distinctBy { it.id }.size == DoubaoVoices.catalog.size)
    }
}
