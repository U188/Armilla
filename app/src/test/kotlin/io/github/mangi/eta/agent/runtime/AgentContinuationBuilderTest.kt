package io.github.mangi.eta.agent.runtime

import io.github.mangi.eta.agent.model.AgentModelClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContinuationBuilderTest {
    @Test
    fun continuationPreservesPromptImagesAndFullTranscript() {
        val image = AgentModelClient.ModelImage(
            reference = "data:image/png;base64,AA==",
            mimeType = "image/png",
            bytes = 1,
        )
        val request = AgentRuntimeWire.RunRequest(
            runId = "run-old",
            prompt = "观察屏幕",
            config = modelConfig(),
            images = listOf(image),
            history = listOf(AgentModelClient.ConversationMessage(role = "user", content = "更早的问题")),
            handoff = AgentRuntimeWire.EntryHandoff(
                id = "run-old",
                source = "agent_ui",
                payload = AgentUiHandoffPayload(conversationId = "conversation-1").toJson(),
            ),
        )
        val response = AgentModelClient.ModelResponse.Text(
            content = "完成",
            transcript = listOf(
                AgentModelClient.ConversationMessage(
                    role = "assistant",
                    toolCallsJson = "[{\"id\":\"call-1\",\"type\":\"function\",\"function\":{\"name\":\"observe_screen\",\"arguments\":\"{}\"}}]",
                ),
                AgentModelClient.ConversationMessage(
                    role = "tool",
                    toolCallId = "call-1",
                    content = "{\"ok\":true}",
                ),
                AgentModelClient.ConversationMessage(role = "assistant", content = "完成"),
            ),
        )

        val continuation = AgentContinuationBuilder.build(
            request = request,
            response = response,
            supplement = "继续检查",
            newRunId = "run-next",
            createdAt = 123L,
        )

        assertEquals("conversation-1", continuation.effectiveModelSessionId)
        assertEquals("run-next", continuation.runId)
        assertEquals("run-old", continuation.effectiveTurnId)
        assertTrue(continuation.history.drop(1).all { it.turnId == "run-old" })
        assertEquals("继续检查", continuation.prompt)
        assertTrue(continuation.images.isEmpty())
        assertEquals(
            listOf("user", "user", "assistant", "tool", "assistant"),
            continuation.history.map { it.role },
        )
        assertTrue(continuation.history[1].contentJson.contains("未写入持久会话"))
        assertTrue(!continuation.history[1].contentJson.contains("base64"))
        assertEquals("call-1", continuation.history[3].toolCallId)
        assertEquals("run-next", continuation.handoff?.id)
        val payload = AgentUiHandoffPayload.from(continuation.handoff?.payload.orEmpty())
        assertEquals("conversation-1", payload.conversationId)
        assertEquals(
            AgentUiHandoffPayload.Supplement(
                index = 1,
                text = "继续检查",
                createdAt = 123L,
            ),
            payload.promptSupplement,
        )
        assertTrue(payload.supplements.isEmpty())
    }

    @Test
    fun entryContinuationKeepsInitialSessionAcrossRuns() {
        val request = AgentRuntimeWire.RunRequest(
            runId = "entry-run", prompt = "开始", config = modelConfig(), images = emptyList(),
        )
        val continuation = AgentContinuationBuilder.build(
            request, AgentModelClient.ModelResponse.Text("完成"), "继续", newRunId = "next-run",
        )
        assertEquals("entry-run", continuation.effectiveModelSessionId)
        assertEquals("entry-run", continuation.effectiveTurnId)
        val again = AgentContinuationBuilder.build(continuation,
            AgentModelClient.ModelResponse.Text("more"), "追加", newRunId = "third-run")
        assertEquals("entry-run", again.effectiveTurnId)
    }

    @Test
    fun explicitLogicalTurnSurvivesMultipleReplacementRuns() {
        val original = AgentRuntimeWire.RunRequest(
            runId = "execution-1", turnId = "logical-turn", prompt = "start",
            config = modelConfig(), images = emptyList(),
        )
        val response = AgentModelClient.ModelResponse.Text("partial", transcript = listOf(
            AgentModelClient.ConversationMessage("assistant", "partial"),
        ))
        val next = AgentContinuationBuilder.build(original, response, "supplement 1", newRunId = "execution-2")
        val last = AgentContinuationBuilder.build(next, response, "supplement 2", newRunId = "execution-3")
        assertEquals("logical-turn", last.effectiveTurnId)
        assertEquals(setOf("logical-turn"), last.history.map { it.turnId }.toSet())
        assertEquals("execution-3", last.runId)
    }

    private fun modelConfig(): AgentModelClient.ModelConfig =
        AgentModelClient.ModelConfig(
            baseUrl = "https://example.invalid/v1",
            apiKey = "test-key",
            model = "test-model",
            systemPrompt = "",
        )
}
