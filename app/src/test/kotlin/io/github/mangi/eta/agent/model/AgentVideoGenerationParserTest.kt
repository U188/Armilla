package io.github.mangi.eta.agent.model

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentVideoGenerationParserTest {
    private val mp4 = byteArrayOf(
        0, 0, 0, 24,
        0x66, 0x74, 0x79, 0x70,
        0x69, 0x73, 0x6f, 0x6d,
        1, 2, 3, 4,
    )
    private val b64 = Base64.getEncoder().encodeToString(mp4)

    @Test
    fun parsesOpenAiTask() {
        val parsed = AgentVideoGenerationParser.parse(
            """{"id":"video_123","object":"video","status":"queued"}""",
        )
        assertEquals("video_123", parsed.taskId)
        assertEquals("queued", parsed.status)
        assertTrue(AgentVideoGenerationParser.isInProgress(parsed.status))
        assertTrue(parsed.videos.isEmpty())
    }

    @Test
    fun parsesCompletedUrl() {
        val parsed = AgentVideoGenerationParser.parse(
            """{"id":"video_123","status":"completed","url":"https://cdn.example/out.mp4"}""",
        )
        assertTrue(AgentVideoGenerationParser.isTerminalSuccess(parsed.status))
        assertEquals("https://cdn.example/out.mp4", parsed.videos.single().url)
    }

    @Test
    fun parsesDashScopeAndMarkdown() {
        val parsed = AgentVideoGenerationParser.parse(
            """
            {
              "output":{"task_id":"task-1","task_status":"SUCCEEDED","video_url":"https://cdn.example/a.mp4"},
              "choices":[{"message":{"content":"done\n![v](https://cdn.example/b.webm)"}}]
            }
            """.trimIndent(),
        )
        val urls = parsed.videos.mapNotNull { it.url }
        assertTrue(urls.contains("https://cdn.example/a.mp4"))
        assertTrue(urls.contains("https://cdn.example/b.webm"))
        assertEquals("task-1", parsed.taskId)
        assertTrue(AgentVideoGenerationParser.isTerminalSuccess(parsed.status))
        assertTrue(parsed.text.contains("done"))
    }

    @Test
    fun parsesDataUrl() {
        val parsed = AgentVideoGenerationParser.parse(
            """{"video":"data:video/mp4;base64,$b64"}""",
        )
        assertEquals(1, parsed.videos.size)
        assertEquals(mp4.toList(), parsed.videos.single().bytes!!.toList())
    }

    @Test
    fun markdownJoinsTextAndLocalPaths() {
        val markdown = AgentVideoGenerationParser.markdown(
            paths = listOf("/cache/a.mp4"),
            text = "here",
        )
        assertTrue(markdown.contains("here"))
        assertTrue(markdown.contains("![generated](/cache/a.mp4)"))
    }

    @Test
    fun failedStatus() {
        val parsed = AgentVideoGenerationParser.parse(
            """{"id":"video_1","status":"failed","error":{"message":"safety"}}""",
        )
        assertTrue(parsed.failed)
        assertEquals("safety", parsed.error)
    }

    @Test
    fun extensionForMime() {
        assertEquals("mp4", AgentVideoGenerationParser.extensionForMime("video/mp4"))
        assertEquals("webm", AgentVideoGenerationParser.extensionForMime("video/webm"))
        assertFalse(AgentVideoGenerationParser.isInProgress("completed"))
    }
}
