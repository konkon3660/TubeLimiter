package com.tubelimiter.app.limit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

// Reference dates (same ones extension/test/schedule.test.js and LimitRulesTest.kt use):
// 2026-08-31 is a Monday, 2026-09-01 is a Tuesday, 2026-09-02 is a Wednesday, 2026-09-06 is a
// Sunday. A fixed zone (UTC) is used throughout so the test is not sensitive to the CI host's
// timezone.
private val ZONE = ZoneId.of("UTC")

private fun at(month: Int, day: Int, hour: Int, minute: Int = 0): Long =
    ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, ZONE).toInstant().toEpochMilli()

private val ALL_DAYS = List(7) { true }
private val MON_ONLY = listOf(false, true, false, false, false, false, false) // Sunday=0, Monday=1
private val WED_ONLY = listOf(false, false, false, true, false, false, false)

class ScheduleRulesTest {

    @Test
    fun `non-wrapping window is active within its start-end minutes on an included day`() {
        val windows = listOf(ScheduleWindow("1", "낮잠 방지", ALL_DAYS, 13 * 60, 14 * 60, enabled = true))
        assertNotNull(isScheduleActive(at(9, 2, 13, 30), windows, ZONE)) // Wed 13:30
        assertNull(isScheduleActive(at(9, 2, 12, 59), windows, ZONE)) // before start
        assertNull(isScheduleActive(at(9, 2, 14, 0), windows, ZONE)) // end is exclusive
    }

    @Test
    fun `wrapping window is active both before and after midnight`() {
        val windows = listOf(ScheduleWindow("w", "밤 시간", ALL_DAYS, 22 * 60, 7 * 60, enabled = true))
        assertNotNull(isScheduleActive(at(9, 2, 23, 0), windows, ZONE)) // Wed 23:00
        assertNotNull(isScheduleActive(at(9, 3, 3, 0), windows, ZONE)) // Thu 03:00
        assertNull(isScheduleActive(at(9, 2, 12, 0), windows, ZONE)) // Wed noon
    }

    @Test
    fun `a wrapping window belongs to the day it starts on, not the day it ends on`() {
        val windows = listOf(ScheduleWindow("w", "월요일 밤", MON_ONLY, 22 * 60, 7 * 60, enabled = true))
        assertNotNull(isScheduleActive(at(8, 31, 23, 0), windows, ZONE)) // Monday night itself
        assertNotNull(isScheduleActive(at(9, 1, 3, 0), windows, ZONE)) // Tuesday 03:00 - carried from Monday
        // Tuesday 23:00 would need Tuesday itself in `days`, which this window does not have.
        assertNull(isScheduleActive(at(9, 1, 23, 0), windows, ZONE))
    }

    @Test
    fun `boundary active exactly at startMinute, inactive exactly at endMinute`() {
        val nonWrap = listOf(ScheduleWindow("w", "x", ALL_DAYS, 13 * 60, 14 * 60, enabled = true))
        assertNotNull(isScheduleActive(at(9, 2, 13, 0), nonWrap, ZONE))
        assertNull(isScheduleActive(at(9, 2, 14, 0), nonWrap, ZONE))

        val wrap = listOf(ScheduleWindow("w", "x", ALL_DAYS, 22 * 60, 7 * 60, enabled = true))
        assertNotNull(isScheduleActive(at(9, 2, 22, 0), wrap, ZONE))
        assertNull(isScheduleActive(at(9, 3, 7, 0), wrap, ZONE))
    }

    @Test
    fun `a disabled window is always ignored regardless of time`() {
        val windows = listOf(ScheduleWindow("w", "x", ALL_DAYS, 0, 1439, enabled = false))
        assertNull(isScheduleActive(at(9, 2, 12, 0), windows, ZONE))
    }

    @Test
    fun `multiple overlapping windows any active one wins`() {
        val windows = listOf(
            ScheduleWindow("a", "a", MON_ONLY, 0, 60, enabled = true), // not active on Wed
            ScheduleWindow("b", "b", ALL_DAYS, 13 * 60, 14 * 60, enabled = true), // active Wed 13:30
        )
        val result = isScheduleActive(at(9, 2, 13, 30), windows, ZONE)
        assertEquals("b", result?.id)
    }

    @Test
    fun `empty scheduled windows is never active`() {
        assertNull(isScheduleActive(at(9, 2, 13, 30), emptyList(), ZONE))
    }

    @Test
    fun `schedule evaluation uses the real wall-clock day, not the 4am usage-day cutoff`() {
        // A Monday-only window from 00:00-03:00 (non-wrapping). Under the 4am cutoff used for
        // usage/streak accounting (com.tubelimiter.app.usage.DAY_CUTOFF_HOUR / effectiveDate),
        // Tuesday 02:00 would fold into "Monday". Schedule evaluation must ignore that and use
        // the real wall-clock day (LocalDate.dayOfWeek), which is Tuesday - so a Monday-only
        // window must NOT be active at real Tuesday 02:00.
        val windows = listOf(ScheduleWindow("w", "x", MON_ONLY, 0, 180, enabled = true))
        assertNull(isScheduleActive(at(9, 1, 2, 0), windows, ZONE)) // Tue 02:00, wall-clock Tuesday
        assertNotNull(isScheduleActive(at(8, 31, 2, 0), windows, ZONE)) // sanity: real Monday 02:00 IS active
    }

    @Test
    fun `minutesUntilNextScheduleStart counts minutes to a start later today`() {
        val windows = listOf(ScheduleWindow("w", "x", ALL_DAYS, 22 * 60, 7 * 60, enabled = true))
        assertEquals(10L, minutesUntilNextScheduleStart(at(9, 2, 21, 50), windows, ZONE))
    }

    @Test
    fun `minutesUntilNextScheduleStart rolls over to the next matching day when today has passed`() {
        val windows = listOf(ScheduleWindow("w", "x", MON_ONLY, 22 * 60, 7 * 60, enabled = true))
        // From Wednesday 10:00, the next Monday is 5 days away.
        val minutes = minutesUntilNextScheduleStart(at(9, 2, 10, 0), windows, ZONE)
        assertEquals(5L * 24 * 60 + (22 * 60 - 10 * 60), minutes)
    }

    @Test
    fun `minutesUntilNextScheduleStart wraps to next week when the only matching day already passed today`() {
        val windows = listOf(ScheduleWindow("w", "x", WED_ONLY, 8 * 60, 9 * 60, enabled = true))
        val minutes = minutesUntilNextScheduleStart(at(9, 2, 10, 0), windows, ZONE)
        assertEquals(7L * 24 * 60 - 10 * 60 + 8 * 60, minutes)
    }

    @Test
    fun `minutesUntilNextScheduleStart is null while a window is already active`() {
        val windows = listOf(ScheduleWindow("w", "x", ALL_DAYS, 22 * 60, 7 * 60, enabled = true))
        assertNull(minutesUntilNextScheduleStart(at(9, 2, 23, 0), windows, ZONE))
    }

    @Test
    fun `minutesUntilNextScheduleStart is null with no windows`() {
        assertNull(minutesUntilNextScheduleStart(at(9, 2, 12, 0), emptyList(), ZONE))
    }

    @Test
    fun `minutesUntilNextScheduleStart ignores disabled windows`() {
        val windows = listOf(ScheduleWindow("w", "x", ALL_DAYS, 13 * 60, 14 * 60, enabled = false))
        assertNull(minutesUntilNextScheduleStart(at(9, 2, 10, 0), windows, ZONE))
    }
}
