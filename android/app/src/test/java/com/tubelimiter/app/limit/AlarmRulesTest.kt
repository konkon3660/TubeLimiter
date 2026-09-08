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
        minutesUntilScheduleStart: Long? = null,
        scheduleStartNotified: Boolean = false,
    ) = evaluateAlarms(
        previous = previous,
        todayKey = TODAY,
        usedMillis = minutesToMillis(usedMinutes),
        limitMillis = if (limitMinutes < 0) UNLIMITED_MILLIS else minutesToMillis(limitMinutes),
        intervalMinutes = intervalMinutes,
        milestonesEnabled = milestonesEnabled,
        minutesUntilScheduleStart = minutesUntilScheduleStart,
        scheduleStartNotified = scheduleStartNotified,
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

    // --- 예약 차단 예고 (확장 alarmRules.js의 SCHEDULE_SOON_LEAD_MINUTES 판정과 같은 자리) ---

    @Test
    fun `예고 임계값은 확장과 같은 10분이다`() {
        assertEquals(10, SCHEDULE_SOON_LEAD_MINUTES)
    }

    @Test
    fun `예약 차단 시작이 임계값 안으로 들어오면 예고한다`() {
        val outcome = evaluate(
            previous = null,
            usedMinutes = 5,
            minutesUntilScheduleStart = SCHEDULE_SOON_LEAD_MINUTES.toLong(),
        )
        assertEquals(listOf(AlarmMessage.ScheduleSoon(SCHEDULE_SOON_LEAD_MINUTES)), outcome.messages)
    }

    @Test
    fun `남은 분이 아니라 예고 기준값을 문구에 싣는다`() {
        val outcome = evaluate(previous = null, usedMinutes = 5, minutesUntilScheduleStart = 7)
        assertEquals(listOf(AlarmMessage.ScheduleSoon(SCHEDULE_SOON_LEAD_MINUTES)), outcome.messages)
    }

    @Test
    fun `임계값 밖이거나 예약이 없으면 조용하다`() {
        assertFalse(evaluate(previous = null, usedMinutes = 5, minutesUntilScheduleStart = 11).changed)
        assertFalse(evaluate(previous = null, usedMinutes = 5, minutesUntilScheduleStart = 0).changed)
        assertFalse(evaluate(previous = null, usedMinutes = 5, minutesUntilScheduleStart = null).changed)
    }

    @Test
    fun `오늘 이미 예고했으면 다시 알리지 않는다`() {
        val outcome = evaluate(
            previous = null,
            usedMinutes = 5,
            minutesUntilScheduleStart = 3,
            scheduleStartNotified = true,
        )
        assertFalse(outcome.changed)
    }

    @Test
    fun `예고는 마일스톤과 같은 틱에 함께 나갈 수 있다`() {
        val outcome = evaluate(previous = null, usedMinutes = 35, minutesUntilScheduleStart = 4)
        assertEquals(
            listOf(AlarmMessage.RemainingMinutes(30), AlarmMessage.ScheduleSoon(SCHEDULE_SOON_LEAD_MINUTES)),
            outcome.messages,
        )
    }
}
