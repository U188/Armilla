package io.github.mangi.eta.agent.delegation

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentTraceFormatter
import org.junit.Assert.*
import org.junit.Test

class SubAgentTraceTest {
    @Test fun runningAndCompletedAreNotConfusedAndEvidenceIsNotInSummary() {
        val formatter = AgentTraceFormatter()
        assertEquals("子代理执行中", formatter.summarizeResult("delegate_task",
            AgentModelClient.ToolResult("{\"ok\":true,\"status\":\"running\"}")))
        val result = formatter.summarizeResult("get_task_result",
            AgentModelClient.ToolResult("{\"ok\":true,\"status\":\"completed\",\"result\":\"private evidence\"}", sensitive = true))
        assertTrue(result.contains("等待主代理审核"))
        assertFalse(result.contains("private evidence"))
    }
}
