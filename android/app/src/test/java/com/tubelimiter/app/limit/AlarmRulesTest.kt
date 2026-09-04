package com.tubelimiter.app.limit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TODAY = "2026-09-02"

class AlarmRulesTest {

    private fun evaluate(
        previous: AlarmState?,
        usedMinutes: Int,
        limitMinutes: Int = 60,
        intervalMinutes: Int = 0,
        milestonesEnabled: Boolean = true,
    ) = evaluateAlarms(
        previous = previous,
        todayKey = TODAY,
        usedMillis = minutesToMillis(usedMinutes),
        limitMillis = if (limitMinutes < 0) UNLIMITED_MILLIS else minutesToMillis(limitMinutes),
        intervalMinutes = intervalMinutes,
        milestonesEnabled = milestonesEnabled,
    )

    @Test
    fun `nothing fires when the interval is off and time remains`() {
        val outcome = evaluate(previous = null, usedMinutes = 5)
        assertFalse(outcome.changed)
    }

    @Test
    fun `crossing a milestone fires once and is remembered`() {
        val first = evaluate(previous = null, usedMinutes = 35)
        assertEquals(1, first.messages.size)
        assertTrue(30 in first.state.notifiedMilestones)

        val second = evaluate(previous = first.state, usedMinutes = 36)
        assertFalse(second.changed)
    }

    @Test
    fun `passing several milestones at once reports each`() {
        val outcome = evaluate(previous = null, usedMinutes = 56)
        assertEquals(setOf(30, 10, 5), outcome.state.notifiedMilestones)
        assertEquals(3, outcome.messages.size)
    }

    @Test
    fun `spent time fires no milestone`() {
        val outcome = evaluate(previous = null, usedMinutes = 60)
        assertFalse(outcome.changed)
    }

    @Test
    fun `an unlimited day has no milestones to cross`() {
        val outcome = evaluate(previous = null, usedMinutes = 500, limitMinutes = -1)
        assertFalse(outcome.changed)
    }

    @Test
    fun `milestones can be switched off`() {
        val outcome = evaluate(previous = null, usedMinutes = 55, milestonesEnabled = false)
        assertFalse(outcome.changed)
    }

    @Test
    fun `the interval nudge repeats on each boundary`() {
        val first = evaluate(previous = null, usedMinutes = 10, intervalMinutes = 10, milestonesEnabled = false)
        assertEquals(1, first.messages.size)
        assertEquals(minutesToMillis(10), first.state.lastIntervalNotifyMillis)

        val quiet = evaluate(previous = first.state, usedMinutes = 15, intervalMinutes = 10, milestonesEnabled = false)
        assertFalse(quiet.changed)

        val next = evaluate(previous = first.state, usedMinutes = 20, intervalMinutes = 10, milestonesEnabled = false)
        assertEquals(1, next.messages.size)
        assertEquals(minutesToMillis(20), next.state.lastIntervalNotifyMillis)
    }

    @Test
    fun `a long gap snaps to the boundary instead of queueing a burst`() {
        val outcome = evaluate(previous = null, usedMinutes = 47, intervalMinutes = 10, milestonesEnabled = false)
        assertEquals(1, outcome.messages.size)
        assertEquals(minutesToMillis(40), outcome.state.lastIntervalNotifyMillis)
    }

    @Test
    fun `yesterday's bookkeeping does not suppress today's alarms`() {
        val yesterday = AlarmState("2026-09-01", notifiedMilestones = setOf(30, 10, 5, 1))
        val outcome = evaluate(previous = yesterday, usedMinutes = 35)
        assertEquals(TODAY, outcome.state.dateKey)
        assertEquals(1, outcome.messages.size)
    }
}
