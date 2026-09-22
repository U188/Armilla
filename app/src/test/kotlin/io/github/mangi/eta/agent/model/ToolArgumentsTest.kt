package io.github.mangi.eta.agent.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolArgumentsTest {
    @Test fun terminalObjectDoesNotDropStreamedKeys() {
        val streamed = """{"task":"edit","role":"implementation","project":"/workspace/Eta"}"""
        val terminal = JSONObject().put("task", "edit")
        val merged = JSONObject(ToolArguments.merge(streamed, terminal))
        assertEquals("implementation", merged.getString("role"))
        assertEquals("/workspace/Eta", merged.getString("project"))
    }

    @Test fun blankTerminalKeepsStreamedArguments() {
        val streamed = """{"task":"edit","role":"implementation"}"""
        assertEquals(streamed, ToolArguments.merge(streamed, ""))
        assertEquals(streamed, ToolArguments.merge(streamed, JSONObject.NULL))
    }

    @Test fun objectArgumentsAreAcceptedWhenNothingWasStreamed() {
        val incoming = JSONObject().put("task", "edit").put("role", "implementation")
        assertEquals("implementation", JSONObject(ToolArguments.merge("", incoming)).getString("role"))
    }
}
