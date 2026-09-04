package com.tubelimiter.app.limit

import com.tubelimiter.app.usage.dayIndex
import java.time.Instant
import java.time.ZoneId

/**
 * Recurring time-of-day curfew. Mirrors the extension's `scheduled_blocks` settings row
 * (`extension/src/lib/schedule.js`) 1:1 - same fields, same wrap rule, same day convention.
 *
 * [days] is Sunday=0 … Saturday=6, matching [com.tubelimiter.app.limit.LimitConfig.byDayMinutes]
 * and the extension's `daily_limit_by_day`. [startMinute]/[endMinute] are minutes since midnight
 * (0-1439), not "HH:mm" strings.
 */
data class ScheduleWindow(
    val id: String,
    val label: String,
    val days: List<Boolean>,
    val startMinute: Int,
    val endMinute: Int,
    val enabled: Boolean = true,
)

private const val MINUTES_PER_DAY = 24 * 60

private fun isValid(window: ScheduleWindow): Boolean = window.days.size >= 7

/**
 * Deliberately independent of [com.tubelimiter.app.usage.DAY_CUTOFF_HOUR] / `effectiveDate` -
 * a curfew is a real wall-clock commitment, not a usage-accounting day. Ported 1:1 from the
 * extension's `isScheduleActive` (`lib/schedule.js`), including the wrap rule: a window whose
 * `endMinute <= startMinute` spans past midnight and belongs to the day it *starts* on.
 */
fun isScheduleActive(
    nowMillis: Long,
    windows: List<ScheduleWindow>,
    zone: ZoneId = ZoneId.systemDefault(),
): ScheduleWindow? {
    if (windows.isEmpty()) return null

    val zoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val dayIdx = dayIndex(zoned.toLocalDate())
    val yesterdayIdx = (dayIdx + 6) % 7
    val nowMinute = zoned.hour * 60 + zoned.minute

    for (window in windows) {
        if (!window.enabled || !isValid(window)) continue

        val wraps = window.endMinute <= window.startMinute
        if (!wraps) {
            if (window.days[dayIdx] && nowMinute >= window.startMinute && nowMinute < window.endMinute) {
                return window
            }
        } else {
            // Either today is the day it started on (running until midnight), or yesterday
            // started it and it is still running into this morning.
            if (window.days[dayIdx] && nowMinute >= window.startMinute) return window
            if (window.days[yesterdayIdx] && nowMinute < window.endMinute) return window
        }
    }
    return null
}

/**
 * Minutes until the next window starts, or null if nothing is scheduled or a window is already
 * active. Ported 1:1 from the extension's `minutesUntilNextScheduleStart`, including the
 * offset-7 fallback for a single day-of-week window whose start already passed today.
 */
fun minutesUntilNextScheduleStart(
    nowMillis: Long,
    windows: List<ScheduleWindow>,
    zone: ZoneId = ZoneId.systemDefault(),
): Long? {
    if (windows.isEmpty()) return null
    if (isScheduleActive(nowMillis, windows, zone) != null) return null

    val zoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val nowDayIdx = dayIndex(zoned.toLocalDate())
    val nowMinute = zoned.hour * 60 + zoned.minute

    var best: Long? = null
    for (window in windows) {
        if (!window.enabled || !isValid(window)) continue

        // offset 0..6 covers each day of this week; offset 7 is "today's weekday, next week" -
        // needed when a window only runs on one day and today's occurrence already started.
        for (offset in 0..7) {
            val dayIdx = (nowDayIdx + offset) % 7
            if (!window.days[dayIdx]) continue
            if (offset == 0 && window.startMinute <= nowMinute) continue

            val minutesUntil = (offset * MINUTES_PER_DAY - nowMinute + window.startMinute).toLong()
            val currentBest = best
            if (currentBest == null || minutesUntil < currentBest) best = minutesUntil
        }
    }
    return best
}
