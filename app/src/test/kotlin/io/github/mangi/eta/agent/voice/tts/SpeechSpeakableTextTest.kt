package io.github.mangi.eta.agent.voice.tts

import org.junit.Assert.*
import org.junit.Test

class SpeechSpeakableTextTest {
    @Test fun blankAndCodeOnlyHaveNoUtterances() {
        assertTrue(SpeechSpeakableText.sentences(" \n").isEmpty())
        assertTrue(SpeechSpeakableText.sentences("```kotlin\nsecret()\n```").isEmpty())
        assertTrue(SpeechSpeakableText.sentences("~~~sh\nsecret\n~~~").isEmpty())
    }
    @Test fun proseSurvivesWithoutMarkdownOrDestinations() {
        val text = SpeechSpeakableText.speakable("# 标题\n\n**你好**，看[文档](https://example.com/private)。\n\n![图片](https://example.com/image)")
        assertTrue(text.contains("标题"))
        assertTrue(text.contains("你好"))
        assertTrue(text.contains("文档"))
        assertFalse(text.contains("https://"))
        assertFalse(text.contains("图片"))
        assertFalse(text.contains("**"))
    }
    @Test fun literalsAreNotGloballyStripped() {
        val text = SpeechSpeakableText.speakable("x_y 大于 0，`a_b` 不变。")
        assertTrue(text.contains("x_y"))
        assertTrue(text.contains("a_b"))
    }
    @Test fun zeroAndNegativeChunkSizeAreRejected() {
        listOf(0, -1, 2001).forEach { size ->
            assertThrows(IllegalArgumentException::class.java) { SpeechSpeakableText.sentences("你好", size) }
        }
    }
    @Test fun unicodeChunksNeverSplitSurrogatePairs() {
        val source = "😀你好😀世界"
        val parts = SpeechSpeakableText.sentences(source, 1)
        assertEquals(source, parts.joinToString(""))
        assertTrue(parts.all { it.codePointCount(0, it.length) == 1 })
        assertEquals("😀", parts.first())
    }
    @Test fun sentenceOrderingAndBoundsAreStable() {
        val parts = SpeechSpeakableText.sentences("第一句。第二句！第三句？", 4)
        assertEquals(listOf("第一句。", "第二句！", "第三句？"), parts)
        assertTrue(SpeechSpeakableText.sentences("长".repeat(1010)).all { it.length <= 400 })
    }
    @Test fun excessiveInputRejectedRatherThanSilentlyTruncated() {
        assertThrows(IllegalArgumentException::class.java) {
            SpeechSpeakableText.speakable("字".repeat(SpeechSpeakableText.MAX_SOURCE_CHARS + 1))
        }
    }
    @Test fun htmlAndNestedCodeAreNotRead() {
        val text = SpeechSpeakableText.speakable("前文\n\n<div>隐藏</div>\n\n后文\n\n    secret()\n")
        assertTrue(text.contains("前文"))
        assertTrue(text.contains("后文"))
        assertFalse(text.contains("隐藏"))
        assertFalse(text.contains("secret"))
    }

    @Test fun firstUtteranceKeepsOpeningProseWithHeading() {
        val parts = SpeechSpeakableText.sentences("# 标题\n\n你好，这是正文第一句。后面一句。")
        assertTrue(parts.isNotEmpty())
        assertTrue(parts.first().contains("你好"))
        val joined = parts.joinToString("")
        assertTrue(joined.contains("标题"))
        assertTrue(joined.contains("后面一句"))
    }
    @Test fun firstUtteranceDoesNotStartAfterOpeningSentence() {
        val source = "已经接到源码里了，还没提交。\n\n## 豆包语音\n\n支持云端朗读。"
        val parts = SpeechSpeakableText.sentences(source)
        assertTrue(parts.first().contains("已经接到"))
        assertFalse(parts.first().startsWith("支持云端"))
    }

    @Test fun committedSentencesHoldTheTrailingDraftUntilFinalized() {
        assertEquals(emptyList<String>(), SpeechSpeakableText.committedSentences("你好", finalized = false))
        assertEquals(listOf("你好。"), SpeechSpeakableText.committedSentences("你好。", finalized = false))
        val mid = SpeechSpeakableText.committedSentences(
            "第一句已经足够长可以单独成句了。后面还在写",
            finalized = false,
        )
        assertTrue(mid.joinToString("").contains("第一句"))
        assertFalse(mid.joinToString("").contains("还在写"))
        val done = SpeechSpeakableText.committedSentences(
            "第一句已经足够长可以单独成句了。第二句也已经完整了。",
            finalized = false,
        )
        assertTrue(done.joinToString("").contains("第二句"))
        assertEquals(listOf("你好"), SpeechSpeakableText.committedSentences("你好", finalized = true))
    }
}
