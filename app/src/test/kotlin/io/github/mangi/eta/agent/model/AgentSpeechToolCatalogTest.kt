package io.github.mangi.eta.agent.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSpeechToolCatalogTest {
    @Test fun textToSpeechIsAlwaysPublished() {
        val tools = AgentToolCatalog.build(terminalTools = false, browserTools = false)
        val names = (0 until tools.length()).map {
            tools.getJSONObject(it).getJSONObject("function").getString("name")
        }
        assertTrue(names.contains("text_to_speech"))
        val fn = tools.getJSONObject(names.indexOf("text_to_speech")).getJSONObject("function")
        assertEquals("text_to_speech", fn.getString("name"))
        assertTrue(fn.getJSONObject("parameters").getJSONArray("required").toString().contains("text"))
    }
}
