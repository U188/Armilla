package io.github.mangi.eta.ui.components

import org.junit.Assert.*
import org.junit.Test

class StreamTimingStatsTest {
    @Test fun histogramKeepsSlowFramesInsteadOfHidingThemInAverage() {
        val stats = StreamTimingStats()
        listOf(8L, 16L, 17L, 32L, 33L, 50L, 51L, 100L, 101L).forEach {
            stats.add(it * 1_000_000, 1)
        }
        assertArrayEquals(longArrayOf(2, 2, 2, 2, 1), stats.buckets)
        assertEquals(9L, stats.count)
        assertEquals(101_000_000L, stats.maxNs)
        assertEquals(9L, stats.valueSum)
    }
    @Test fun missingMetricsAndEmptySummaryAreSafe() {
        val stats = StreamTimingStats()
        assertTrue(stats.summary().contains("avgUs=0"))
        stats.add(-1, -1)
        assertEquals(0L, stats.totalNs)
        assertEquals(0L, stats.valueSum)
    }
}
