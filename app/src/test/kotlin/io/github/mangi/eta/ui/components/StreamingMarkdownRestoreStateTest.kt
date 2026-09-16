package io.github.mangi.eta.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMarkdownRestoreStateTest {
    @Test fun userResumeCannotOpenGateBeforeRestoredLayout() {
        val state = StreamingMarkdownRestoreState()
        assertFalse(state.animationsAllowed(false))
        state.begin("already visible")
        assertFalse(state.animationsAllowed(false))
        assertFalse(state.animationsAllowed(true))
        assertTrue(state.completeLayout(state.generation, "already visible", "already visible"))
        assertFalse(state.animationsAllowed(true))
        assertTrue(state.animationsAllowed(false))
    }

    @Test fun repeatedPauseResumeKeepsProgressButCannotAnimateWhileBackgrounded() {
        val state = StreamingMarkdownRestoreState()
        state.begin("prefix")
        state.completeLayout(state.generation, "prefix", "prefix")
        repeat(4) {
            assertFalse(state.animationsAllowed(true))
            assertTrue(state.animationsAllowed(false))
        }
        state.pause()
        assertFalse(state.animationsAllowed(false))
        state.begin("prefix plus background output")
        assertFalse(state.animationsAllowed(false))
        assertFalse(state.completeLayout(state.generation, "prefix", "prefix plus background output"))
        assertTrue(state.completeLayout(state.generation, "prefix plus background output", "prefix plus background output"))
        assertTrue(state.animationsAllowed(false))
    }

    @Test
    fun restoredContentWaitsForMatchingLayoutRegardlessOfEarlierLayoutCount() {
        val state = StreamingMarkdownRestoreState()
        state.begin("后台已完成的内容")

        repeat(10) {
            assertFalse(state.completeLayout(state.generation, "后台", "后台已完成的内容"))
        }
        assertTrue(state.completeLayout(state.generation, "后台已完成的内容", "后台已完成的内容"))
        assertFalse(state.completeLayout(state.generation, "后台已完成的内容和增量", "后台已完成的内容和增量"))
    }

    @Test
    fun delayedOldLayoutCannotResumeAfterAnotherBackgroundCycle() {
        val state = StreamingMarkdownRestoreState()
        state.begin("已有内容")
        val oldGeneration = state.generation
        state.pause()
        assertFalse(state.completeLayout(oldGeneration, "已有内容", "已有内容"))
        state.begin("已有内容和后台增量")

        assertFalse(state.completeLayout(oldGeneration, "已有内容和后台增量", "已有内容和后台增量"))
        assertFalse(state.completeLayout(state.generation, "已有内容", "已有内容和后台增量"))
        assertTrue(state.completeLayout(state.generation, "已有内容和后台增量", "已有内容和后台增量"))
    }

    @Test
    fun newNetworkTextDoesNotKeepMovingTheRestoreBaseline() {
        val state = StreamingMarkdownRestoreState()
        state.begin("历史")

        assertTrue(state.completeLayout(state.generation, "历史", "历史和新内容"))
    }

    @Test
    fun authoritativeReplacementCanCompleteRestoreButStaleLayoutCannot() {
        val state = StreamingMarkdownRestoreState()
        state.begin("旧内容")

        assertFalse(state.completeLayout(state.generation, "旧内容", "纠正后的内容"))
        assertTrue(state.completeLayout(state.generation, "纠正后的内容", "纠正后的内容"))
    }

    @Test
    fun onlyCurrentTerminalSnapshotMayFinishReveal() {
        assertFalse(isStreamingMarkdownTargetComplete("正文和增量", false, "正文", true))
        assertFalse(isStreamingMarkdownTargetComplete("正文", true, "正文", true))
        assertFalse(isStreamingMarkdownTargetComplete("正文", false, "正文", false))
        assertFalse(isStreamingMarkdownTargetComplete("正文", false, null, false))
        assertTrue(isStreamingMarkdownTargetComplete("正文", false, "正文", true))
    }
}
