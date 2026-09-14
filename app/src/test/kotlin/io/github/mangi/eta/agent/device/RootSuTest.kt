package io.github.mangi.eta.agent.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RootSuTest {
    @Test
    fun usesMountMaster() {
        assertArrayEquals(
            arrayOf("su", "-M", "-c", "ls /data/data"),
            RootSu.args("ls /data/data"),
        )
        assertEquals(listOf("su", "-M", "-c", "id"), RootSu.process("id").command())
    }
}
