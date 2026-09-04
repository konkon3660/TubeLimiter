package com.tubelimiter.app.limit

import com.tubelimiter.app.usage.dayIndex
import java.time.LocalDate

const val DEFAULT_LIMIT_MINUTES = 30
val LIMIT_PRESETS_MINUTES = listOf(15, 30, 60, 90, 120)

/** Stand-in for the extension's `Infinity`. */
const val UNLIMITED_MILLIS = Long.MAX_VALUE

/** Sentinel used inside [LimitConfig.byDayMinutes], matching the extension's `-1`. */
const val UNLIMITED_MINUTES = -1

enum class LimitFrequency { DAILY, BY_DAY }

data class LimitConfig(
    val dailyLimitMinutes: Int = DEFAULT_LIMIT_MINUTES,
    /** Indexed Sunday=0 … Saturday=6. */
    val byDayMinutes: List<Int> = List(7) { DEFAULT_LIMIT_MINUTES },
    val frequency: LimitFrequency = LimitFrequency.DAILY,
)

fun minutesToMillis(minutes: Int): Long = minutes * 60_000L

fun isUnlimited(limitMillis: Long): Boolean = limitMillis == UNLIMITED_MILLIS

fun computeLimitMillis(config: LimitConfig, date: LocalDate): Long = when (config.frequency) {
    LimitFrequency.BY_DAY -> {
        val minutes = config.byDayMinutes.getOrNull(dayIndex(date))
        if (minutes == null || minutes == UNLIMITED_MINUTES) UNLIMITED_MILLIS else minutesToMillis(minutes)
    }

    // The extension treats a zero daily limit as "no limit" rather than "block everything".
    LimitFrequency.DAILY ->
        if (config.dailyLimitMinutes <= 0) UNLIMITED_MILLIS else minutesToMillis(config.dailyLimitMinutes)
}

data class LimitState(
    val usedMillis: Long,
    val limitMillis: Long,
    val inForeground: Boolean,
)

fun LimitState.isOverLimit(): Boolean = !isUnlimited(limitMillis) && usedMillis >= limitMillis

fun LimitState.remainingMillis(): Long =
    if (isUnlimited(limitMillis)) UNLIMITED_MILLIS else (limitMillis - usedMillis).coerceAtLeast(0L)
