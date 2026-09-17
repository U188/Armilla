package io.github.mangi.eta.agent.voice.offline

import org.junit.Assert.*
import org.junit.Test

class SpeechInputTest {
    @Test fun disabledVoiceDoesNotShowAnIdleIndicator() {
        assertFalse(SpeechInputPolicy.visible(generating = false, enabled = false))
        assertTrue(SpeechInputPolicy.visible(generating = true, enabled = false))
    }
    @Test fun enabledVoiceShowsCircleWithoutGeneration() {
        assertTrue(SpeechInputPolicy.visible(generating = false, enabled = true))
        assertTrue(SpeechInputPolicy.visible(generating = true, enabled = true))
    }
    @Test fun enablingRequiresVerifiedCompletePack() {
        assertFalse(SpeechInputPolicy.canEnable(false, false, false))
        assertFalse(SpeechInputPolicy.canEnable(true, true, false))
        assertFalse(SpeechInputPolicy.canEnable(true, false, true))
        assertTrue(SpeechInputPolicy.canEnable(true, false, false))
    }
    @Test fun initialSilenceAndLongSessionHaveFiniteDeadlines() {
        assertFalse(SpeechInputPolicy.timedOut(7_999, false))
        assertTrue(SpeechInputPolicy.timedOut(8_000, false))
        assertFalse(SpeechInputPolicy.timedOut(8_000, true))
        assertTrue(SpeechInputPolicy.timedOut(60_000, true))
    }
    @Test fun partialTextReplacesOnlyCurrentDictation() {
        val draft = SpeechDraft("原有文字", 4, 4)
        assertEquals("原有文字你", draft.accept("原有文字", "你"))
        assertEquals("原有文字你好", draft.accept("原有文字你", "你好"))
        assertEquals(6, draft.cursor)
    }
    @Test fun decoderRevisionDoesNotDuplicatePartialWords() {
        val draft = SpeechDraft("", 0, 0)
        assertEquals("识别", draft.accept("", "识别"))
        assertEquals("识别修正", draft.accept("识别", "识别修正"))
        assertEquals("识别更正", draft.accept("识别修正", "识别更正"))
    }
    @Test fun selectionAndSuffixArePreserved() {
        val draft = SpeechDraft("aSELECTz", 7, 1)
        assertEquals("a你好z", draft.accept("aSELECTz", "你好"))
        assertEquals(3, draft.cursor)
    }
    @Test fun typingOrSendingInvalidatesDictationInsteadOfOverwriting() {
        val draft = SpeechDraft("draft", 5, 5)
        assertNull(draft.accept("typed", "speech"))
        assertNull(draft.accept("", "speech"))
        assertEquals("draft", draft.expectedText)
    }
    @Test fun cancellingBeforeTextDoesNotChangeTheDraft() {
        val draft = SpeechDraft("原文", 1, 1)
        assertEquals("原文", draft.expectedText)
    }
    @Test fun manifestIsBoundedPinnedAndContainsOnlyExpectedFiles() {
        val entries = SpeechModelManifest.assets
        assertEquals(7, entries.size)
        assertEquals(7, entries.map { it.name }.toSet().size)
        assertTrue(entries.all { it.bytes > 0 && it.sha256.matches(Regex("[0-9a-f]{64}")) })
        assertTrue(entries.none { it.name.contains('/') || it.name.contains("..") })
        assertTrue(SpeechModelManifest.BASE_URL.contains(SpeechModelManifest.REVISION))
        assertTrue(SpeechModelManifest.totalBytes in 140_000_000..145_000_000)
    }
}
