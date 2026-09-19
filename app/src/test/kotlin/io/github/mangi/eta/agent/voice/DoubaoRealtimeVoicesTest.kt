package io.github.mangi.eta.agent.voice

import org.junit.Assert.*
import org.junit.Test

class DoubaoRealtimeVoicesTest {
    @Test fun publicListDoesNotImportOrdinaryTtsVoices() {
        val document = "zh_female_vv_uranus_bigtts zh_female_xiaohe_jupiter_bigtts zh_male_yunzhou_jupiter_bigtts"
        val result = DoubaoRealtimeVoices.parseOfficialList(document)
        assertEquals(listOf("小何", "云舟"), result.map { it.name })
        assertTrue(DoubaoRealtimeVoices.parseOfficialList("login required").isEmpty())
        assertEquals(result, DoubaoRealtimeVoices.parseOfficialList(document + document))
    }
}
