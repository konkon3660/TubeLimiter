package com.tubelimiter.app.usage

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The day rolls over at 04:00, not midnight — watching at 01:00 counts toward the
 * previous day. Ported from the extension's `lib/time.js` so both clients agree on
 * which day a session belongs to.
 */
const val DAY_CUTOFF_HOUR = 4

fun effectiveDate(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochMilli(nowMillis)
        .atZone(zone)
        .minusHours(DAY_CUTOFF_HOUR.toLong())
        .toLocalDate()

/** Epoch millis of 04:00 on the effective day containing [nowMillis]. */
fun startOfEffectiveDayMillis(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    startOfEffectiveDayMillis(effectiveDate(nowMillis, zone), zone)

fun startOfEffectiveDayMillis(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
    date.atTime(DAY_CUTOFF_HOUR, 0).atZone(zone).toInstant().toEpochMilli()

/**
 * Wall-clock hour (0-23) of [nowMillis]. Used to bucket usage for the dashboard's time-of-day
 * pattern chart — deliberately the raw clock hour rather than anything shifted by
 * [DAY_CUTOFF_HOUR], since a viewer watching at 1am thinks of that as "1am", even though
 * [effectiveDate] attributes the session to the previous day.
 */
fun hourOfDay(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Int =
    Instant.ofEpochMilli(nowMillis).atZone(zone).hour

/** Monday-based, matching the extension. */
fun weekStartDate(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

fun monthStartDate(date: LocalDate): LocalDate = date.withDayOfMonth(1)

/** Sunday=0 … Saturday=6, matching JavaScript's `Date.getDay()` so stored per-day limits line up. */
fun dayIndex(date: LocalDate): Int = date.dayOfWeek.value % 7

fun lastNDates(today: LocalDate, count: Int): List<LocalDate> =
    (count - 1 downTo 0).map { today.minusDays(it.toLong()) }

fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000
    if (totalSeconds < 60) return "${totalSeconds}초"

    val totalMinutes = totalSeconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "${hours}시간 ${minutes}분" else "${minutes}분"
}

/**
 * Live `m:ss` countdown, mirroring the extension popup's `formatCountdown` (`popup.js`)
 * exactly: seconds are rounded up (`ceil`) so the display doesn't flash "0:00" a beat before
 * the block actually lands, and negative input (already-elapsed deadlines) clamps to zero.
 * Intended only for the countdown-to-block ring — other displays should keep [formatDuration].
 */
fun formatCountdown(millis: Long): String {
    val totalSeconds = kotlin.math.ceil(millis / 1000.0).toLong().coerceAtLeast(0L)
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "$m:${s.toString().padStart(2, '0')}"
}
