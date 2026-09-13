package io.github.mangi.eta.ui.screens.stats

import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UsageFilterPresetTest {
    private val wednesday = LocalDate.of(2026, 9, 9)
    private val thursday = LocalDate.of(2026, 9, 10)

    @Test
    fun todayCoversTheCurrentCalendarDay() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.Today, wednesday)
        assertEquals(LocalDateTime.of(2026, 9, 9, 0, 0), start)
        assertEquals(LocalDateTime.of(2026, 9, 9, 23, 59), end)
    }

    @Test
    fun last7DaysStartsSevenDaysAgo() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.Last7Days, thursday)
        assertEquals(LocalDateTime.of(2026, 9, 3, 0, 0), start)
        assertEquals(LocalDateTime.of(2026, 9, 10, 23, 59), end)
    }

    @Test
    fun thisWeekStartsOnMondayAndEndsToday() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.ThisWeek, thursday)
        assertEquals(LocalDateTime.of(2026, 9, 7, 0, 0), start)
        assertEquals(LocalDateTime.of(2026, 9, 10, 23, 59), end)
    }

    @Test
    fun thisMonthStartsOnTheFirst() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.ThisMonth, wednesday)
        assertEquals(LocalDateTime.of(2026, 9, 1, 0, 0), start)
        assertEquals(LocalDateTime.of(2026, 9, 9, 23, 59), end)
    }

    @Test
    fun last7DaysAndThisWeekDifferOnSunday() {
        val sunday = LocalDate.of(2026, 9, 13)
        val (weekStart, weekEnd) = usageFilterPresetRange(UsageFilterPreset.ThisWeek, sunday)
        val (sevenStart, sevenEnd) = usageFilterPresetRange(UsageFilterPreset.Last7Days, sunday)
        assertEquals(LocalDateTime.of(2026, 9, 7, 0, 0), weekStart)
        assertEquals(LocalDateTime.of(2026, 9, 6, 0, 0), sevenStart)
        assertEquals(weekEnd, sevenEnd)
        assertNotEquals(weekStart, sevenStart)
        assertEquals(
            UsageFilterPreset.ThisWeek,
            matchingUsageFilterPreset(
                UsageTimeBound.from(weekStart),
                UsageTimeBound.from(weekEnd),
                sunday,
            ),
        )
        assertEquals(
            UsageFilterPreset.Last7Days,
            matchingUsageFilterPreset(
                UsageTimeBound.from(sevenStart),
                UsageTimeBound.from(sevenEnd),
                sunday,
            ),
        )
    }

    @Test
    fun matchingPresetRequiresExactStartAndEnd() {
        val (start, end) = usageFilterPresetRange(UsageFilterPreset.Last30Days, wednesday)
        assertEquals(
            UsageFilterPreset.Last30Days,
            matchingUsageFilterPreset(UsageTimeBound.from(start), UsageTimeBound.from(end), wednesday),
        )
        assertNull(
            matchingUsageFilterPreset(UsageTimeBound.from(start), UsageTimeBound(), wednesday),
        )
    }
}
