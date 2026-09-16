package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentSummaryPipelineTest {
    private fun validSummary() = "[Conversation summary]\n" + AgentContextCompactor.SUMMARY_SECTIONS.joinToString("\n") { "## $it\n- (none)" }
    private fun config() = AgentModelClient.ModelConfig(baseUrl = "https://example.invalid/v1", apiKey = "test", model = "test",
        systemPrompt = "", contextWindow = 200_000)
    private fun history() = listOf(AgentModelClient.ConversationMessage("user", "OLD " + "a".repeat(10_000)),
        AgentModelClient.ConversationMessage("assistant", "verified old result"), AgentModelClient.ConversationMessage("user", "protected"))
    private fun provider(block: (ProviderRequest) -> JSONObject) = object : AgentProviderClient {
        override val id = "summary-test"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, false, false, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit) = ProviderResponse(block(request))
    }
    private fun response(text: String, finish: String = "stop") = JSONObject().put("role", "assistant").put("content", text).put("finish_reason", finish)

    @Test fun summaryCallIsOneShotCappedAndNeverExecutesTools() {
        var calls = 0
        val result = AgentContextCompactor.compress(history(), AgentContextCompactor.Config(500, 1, config(), provider {
            calls++
            assertEquals(1024, it.config.summaryOutputLimit)
            assertEquals(0, it.tools.length())
            assertFalse(it.config.hostedWebSearchEnabled)
            assertTrue(it.messages.toString().contains("historical"))
            response(validSummary())
        }), toolExecutor = AgentModelClient.ToolExecutor { error("Must never execute") })
        assertEquals(1, calls)
        assertEquals("user", result.first().role)
        assertEquals(history().last(), result.last())
        assertTrue(result.first().content.contains("## Verified evidence"))
    }

    @Test fun truncatedEmptyMalformedAndToolCallingSummariesAreRejected() {
        val bad = listOf(response(validSummary(), "length"), response(""), response("not structured"),
            response(validSummary()).put("tool_calls", JSONArray().put(JSONObject().put("id", "bad"))))
        for (output in bad) {
            val source = history()
            val original = source.toList()
            assertThrows(RuntimeException::class.java) {
                AgentContextCompactor.compress(source, AgentContextCompactor.Config(500, 1, config(), provider { output }))
            }
            assertEquals(original, source)
        }
    }

    @Test fun cancellationIsPropagatedWithoutReplacingTheHistory() {
        val controller = AgentRunController()
        val source = history()
        assertThrows(AgentRunCancelledException::class.java) {
            AgentContextCompactor.compress(source, AgentContextCompactor.Config(500, 1, config(), provider {
                controller.cancel()
                response(validSummary())
            }), controller = controller)
        }
        assertEquals("protected", source.last().content)
    }

    @Test fun sameModelReplayRetainsPrefixToolsAndSessionButDoesNotRunAgentLoop() {
        val source = history()
        val system = JSONObject().put("role", "system").put("content", "original instructions")
        val tools = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "test")))
        val replay = AgentContextCompactor.ReplayContext(JSONArray().put(system),
            JSONArray().put(AgentConversationCodec.toJsonObject(source[0])).put(AgentConversationCodec.toJsonObject(source[1])), tools, "stable-session")
        var calls = 0
        AgentContextCompactor.compress(source, AgentContextCompactor.Config(500, 1, config(), provider {
            calls++
            assertEquals("stable-session", it.sessionId)
            assertEquals(system.toString(), it.messages.getJSONObject(0).toString())
            assertEquals(source[0].content, it.messages.getJSONObject(1).getString("content"))
            assertEquals(tools.toString(), it.tools.toString())
            response(validSummary())
        }), replay = replay)
        assertEquals(1, calls)
    }

    @Test fun duplicateMissingAndReorderedHeadingsFailAcceptance() {
        AgentContextCompactor.validateSummary(validSummary())
        assertThrows(IllegalArgumentException::class.java) { AgentContextCompactor.validateSummary("not structured") }
        assertThrows(IllegalArgumentException::class.java) { AgentContextCompactor.validateSummary("## Tasks\n- x") }
    }

    @Test fun chineseHeadingsAndFencesAreCoercedIntoCheckpoint() {
        val raw = """```markdown
好的，下面是摘要。
[对话摘要]
## 目标
- 修压缩
## 约束
- 不要丢原文
## 已验证证据
- 存档 failed
## 文件和标识符
- /workspace/Eta
## 错误和待解决问题
- 格式不对
## 当前状态
- 已回滚
## 待办工作
- 重试
## 下一步
- 修校验
```""".trimIndent()
        val coerced = AgentContextCompactor.coerceSummary(raw)
        assertNotNull(coerced)
        AgentContextCompactor.validateSummary(coerced!!)
        assertTrue(coerced.startsWith(AgentContextCompactor.SUMMARY_PREFIX_ZH))
        assertTrue(coerced.contains("## Verified evidence"))
        assertTrue(coerced.contains("存档 failed"))
        assertFalse(coerced.contains("```"))
    }

    @Test fun malformedSummaryIsRepairedWithASecondFormatOnlyCall() {
        var calls = 0
        val result = AgentContextCompactor.compress(history(), AgentContextCompactor.Config(500, 1, config(), provider {
            calls++
            if (calls == 1) response("下面是摘要\n目标：继续任务")
            else {
                assertTrue(it.messages.toString().contains("Rewrite the checkpoint"))
                assertFalse(it.messages.toString().contains("OLD "))
                response(validSummary())
            }
        }))
        assertEquals(2, calls)
        assertEquals("user", result.first().role)
        assertTrue(result.first().content.contains("## Next step"))
    }

    @Test fun compressionProbesReasoningFromOffThenRemembersWorkingEffort() {
        CompressionReasoningStore.clearForTests()
        var calls = 0
        val source = history()
        val cfg = config().copy(providerId = "compress-probe", model = "probe-model")
        val result = AgentContextCompactor.compress(source, AgentContextCompactor.Config(500, 1, cfg, provider {
            calls++
            when (it.config.reasoningEffort) {
                ReasoningEffort.OFF, ReasoningEffort.MINIMAL ->
                    throw AgentModelFailure(
                        "HTTP_400",
                        false,
                        "DeepSeek reasoning_effort 只支持 low、medium、high、xhigh、max",
                    )
                else -> {
                    assertEquals(ReasoningEffort.LOW, it.config.reasoningEffort)
                    response(validSummary())
                }
            }
        }))
        assertEquals(3, calls)
        assertEquals(ReasoningEffort.LOW, CompressionReasoningStore.effortFor(cfg))
        assertEquals("user", result.first().role)

        val again = AgentContextCompactor.compress(source, AgentContextCompactor.Config(500, 1, cfg, provider {
            calls++
            assertEquals(ReasoningEffort.LOW, it.config.reasoningEffort)
            response(validSummary())
        }))
        assertEquals(4, calls)
        assertEquals(history().last(), again.last())
    }

    @Test fun turnIdsPersistButNeverLeakToProviderMessages() {
        val original = AgentModelClient.ConversationMessage("user", "question", turnId = "run-1")
        val json = AgentConversationCodec.toJsonObject(original)
        assertEquals(original, AgentConversationCodec.fromJsonObject(json))
        assertFalse(AgentRequestMediaPolicy.filter(JSONArray().put(json), false, false).toString().contains("eta_turn_id"))
        assertEquals("run-1", json.getString(AgentTurnIdentity.JSON_KEY))
        val messages = listOf(original, AgentModelClient.ConversationMessage("assistant", "step", turnId = "run-1"),
            AgentModelClient.ConversationMessage("user", "supplement", turnId = "run-1"))
        assertEquals(0, AgentContextCompactor.recentKeepStartIndex(messages, 1))
    }
}
