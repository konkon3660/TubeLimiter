package com.tubelimiter.app.usage

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

private val SEOUL: ZoneId = ZoneId.of("Asia/Seoul")

class DayWindowTest {

    private fun millisAt(year: Int, month: Int, day: Int, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(SEOUL).toInstant().toEpochMilli()

    @Test
    fun `before the 4am cutoff still counts as the previous day`() {
        val lateNight = millisAt(2026, 9, 3, 1, 30)
        assertEquals(LocalDate.of(2026, 9, 2), effectiveDate(lateNight, SEOUL))
    }

    @Test
    fun `after the cutoff the new day has begun`() {
        val morning = millisAt(2026, 9, 3, 4, 1)
        assertEquals(LocalDate.of(2026, 9, 3), effectiveDate(morning, SEOUL))
    }

    @Test
    fun `the cutoff instant itself belongs to the new day`() {
        val boundary = millisAt(2026, 9, 3, 4, 0)
        assertEquals(LocalDate.of(2026, 9, 3), effectiveDate(boundary, SEOUL))
    }

    @Test
    fun `the day window opens at 4am of the effective date`() {
        val lateNight = millisAt(2026, 9, 3, 1, 30)
        assertEquals(millisAt(2026, 9, 2, 4, 0), startOfEffectiveDayMillis(lateNight, SEOUL))
    }

    @Test
    fun `hourOfDay reads the raw wall-clock hour, unaffected by the 4am cutoff`() {
        // 1:30am belongs to the previous *effective* day, but it is still hour 1 on the clock.
        assertEquals(1, hourOfDay(millisAt(2026, 9, 3, 1, 30), SEOUL))
        assertEquals(23, hourOfDay(millisAt(2026, 9, 3, 23, 59), SEOUL))
        assertEquals(0, hourOfDay(millisAt(2026, 9, 3, 0, 0), SEOUL))
    }

    @Test
    fun `day index matches JavaScript with Sunday first`() {
        assertEquals(0, dayIndex(LocalDate.of(2026, 9, 6)))
        assertEquals(1, dayIndex(LocalDate.of(2026, 9, 7)))
        assertEquals(3, dayIndex(LocalDate.of(2026, 9, 2)))
        assertEquals(6, dayIndex(LocalDate.of(2026, 9, 5)))
    }

    @Test
    fun `weeks start on Monday and months on the first`() {
        val wednesday = LocalDate.of(2026, 9, 2)
        assertEquals(LocalDate.of(2026, 8, 31), weekStartDate(wednesday))
        assertEquals(LocalDate.of(2026, 9, 1), monthStartDate(wednesday))
    }

    @Test
    fun `a Sunday belongs to the week that began the previous Monday`() {
        assertEquals(LocalDate.of(2026, 8, 31), weekStartDate(LocalDate.of(2026, 9, 6)))
    }

    @Test
    fun `lastNDates ends on today and runs oldest first`() {
        val today = LocalDate.of(2026, 9, 3)
        val dates = lastNDates(today, 4)
        assertEquals(listOf("2026-08-31", "2026-09-01", "2026-09-02", "2026-09-03"), dates.map { it.toString() })
    }

    @Test
    fun `durations read as seconds under a minute`() {
        // The wording lives in strings.xml; what is worth pinning here is the coarseness:
        // seconds below a minute, whole minutes above it, and hours once there are any.
        assertEquals(DurationParts(hours = 0, minutes = 0, seconds = 45), durationParts(45_000))
        assertEquals(DurationParts(hours = 0, minutes = 30, seconds = 0), durationParts(30 * 60_000L))
        assertEquals(
            DurationParts(hours = 2, minutes = 5, seconds = 0),
            durationParts((125 * 60_000).toLong()),
        )
    }

    @Test
    fun `seconds are dropped above a minute so a ticking clock does not redraw`() {
        assertEquals(durationParts(45 * 60_000L), durationParts(45 * 60_000L + 5_000L))
    }

    @Test
    fun `countdown shows sub-minute values as m colon ss`() {
        assertEquals("0:05", formatCountdown(5_000))
    }

    @Test
    fun `countdown shows exact minutes with zero seconds`() {
        assertEquals("1:00", formatCountdown(60_000))
    }

    @Test
    fun `countdown pads odd seconds to two digits`() {
        assertEquals("1:31", formatCountdown(90_500))
    }

    @Test
    fun `countdown rounds up so it does not show 0 00 a beat early`() {
        // 59.999s left should still read as a full minute, matching the extension's Math.ceil.
        assertEquals("1:00", formatCountdown(59_999))
    }

    @Test
    fun `countdown clamps zero and negative remaining time to 0 00`() {
        assertEquals("0:00", formatCountdown(0))
        assertEquals("0:00", formatCountdown(-500))
    }
}
