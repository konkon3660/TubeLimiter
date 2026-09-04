package com.tubelimiter.app.limit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LimitRulesTest {

    // 2026-09-02 is a Wednesday; 2026-09-06 is a Sunday.
    private val wednesday = LocalDate.of(2026, 9, 2)
    private val sunday = LocalDate.of(2026, 9, 6)

    @Test
    fun `daily frequency ignores the per-day table`() {
        val config = LimitConfig(
            dailyLimitMinutes = 45,
            byDayMinutes = List(7) { 5 },
            frequency = LimitFrequency.DAILY,
        )
        assertEquals(minutesToMillis(45), computeLimitMillis(config, wednesday))
    }

    @Test
    fun `a zero daily limit means unlimited, not blocked-always`() {
        val config = LimitConfig(dailyLimitMinutes = 0, frequency = LimitFrequency.DAILY)
        assertTrue(isUnlimited(computeLimitMillis(config, wednesday)))
    }

    @Test
    fun `per-day limits are indexed with Sunday first`() {
        val byDay = listOf(10, 20, 30, 40, 50, 60, 70)
        val config = LimitConfig(byDayMinutes = byDay, frequency = LimitFrequency.BY_DAY)

        assertEquals(minutesToMillis(10), computeLimitMillis(config, sunday))
        assertEquals(minutesToMillis(40), computeLimitMillis(config, wednesday))
    }

    @Test
    fun `the per-day sentinel means unlimited`() {
        val byDay = List(7) { UNLIMITED_MINUTES }
        val config = LimitConfig(byDayMinutes = byDay, frequency = LimitFrequency.BY_DAY)
        assertTrue(isUnlimited(computeLimitMillis(config, wednesday)))
    }

    @Test
    fun `limit state reports remaining time and floors at zero`() {
        val under = LimitState(minutesToMillis(20), minutesToMillis(60), inForeground = true)
        assertFalse(under.isOverLimit())
        assertEquals(minutesToMillis(40), under.remainingMillis())

        val over = LimitState(minutesToMillis(90), minutesToMillis(60), inForeground = true)
        assertTrue(over.isOverLimit())
        assertEquals(0L, over.remainingMillis())
    }

    @Test
    fun `an unlimited day is never over limit`() {
        val state = LimitState(minutesToMillis(600), UNLIMITED_MILLIS, inForeground = true)
        assertFalse(state.isOverLimit())
    }
}
