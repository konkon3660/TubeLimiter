package com.tubelimiter.app.limit

/** Fire a "time is running out" nudge when the remaining time crosses each of these. */
val ALARM_MILESTONE_MINUTES = listOf(30, 10, 5, 1)

/**
 * 예약 차단 시작 몇 분 전에 알릴지. 확장 `lib/alarmRules.js`의 `SCHEDULE_SOON_LEAD_MINUTES`와
 * 같은 값이어야 하고, 알림 문구에도 이 값이 그대로 들어간다
 * (`R.plurals.nudge_schedule_upcoming`). 서비스 안에 숫자로 박아두면 한쪽만 바뀌어도 눈치채기
 * 어려워서 상수로 뺐다.
 */
const val SCHEDULE_SOON_LEAD_MINUTES = 10

data class AlarmState(
    val dateKey: String,
    val lastIntervalNotifyMillis: Long = 0L,
    val notifiedMilestones: Set<Int> = emptySet(),
)

/**
 * A nudge that is due, as data rather than text: the notification only exists on a device, but
 * the "did we already say this today" bookkeeping around it is what the unit tests exercise,
 * and unit tests cannot reach Android resources. The service turns these into strings
 * ([com.tubelimiter.app.service.UsageMonitorService.nudgeText]).
 */
sealed interface AlarmMessage {
    /** The recurring "you have been watching for a while" interval nudge. */
    data class WatchedMinutes(val minutes: Long) : AlarmMessage

    /** One of [ALARM_MILESTONE_MINUTES], crossed on the way down. */
    data class RemainingMinutes(val minutes: Int) : AlarmMessage

    /**
     * 예약 차단이 곧 시작된다. [minutes]에는 실제 남은 분이 아니라 예고 기준값
     * ([SCHEDULE_SOON_LEAD_MINUTES])이 담긴다 — 하루 한 번만 알리는데 7분 남았을 때 "7분 후"로
     * 바뀌면 규칙과 어긋나 보인다(확장 `evaluateAlarms`와 같은 판단).
     */
    data class ScheduleSoon(val minutes: Int) : AlarmMessage
}

data class AlarmOutcome(
    val state: AlarmState,
    val messages: List<AlarmMessage>,
) {
    val changed: Boolean get() = messages.isNotEmpty()
}

/**
 * Decides which nudges are due. Ported from the extension's `checkAlarms`; kept pure so
 * the "did we already say this today" bookkeeping is testable without a device.
 *
 * [minutesUntilScheduleStart]는 [minutesUntilNextScheduleStart]의 반환값(이미 활성 중이거나
 * 예약이 없으면 null)이고, [scheduleStartNotified]는 오늘 이미 예고했는지다. 예약 차단 예고
 * 판정을 여기 둔 건 확장 `evaluateAlarms`와 자리를 맞추기 위해서다 — 임계값과 판정이 서비스
 * 안에 흩어져 있으면 한쪽만 바뀌어도 드러나지 않는다. 저장 위치만 다르다: 확장은 이 표시를
 * `alarm_state`에 담지만 안드로이드는 별도 DataStore 키(`schedule_start_notified_date`)에
 * 날짜로 담으므로, 여기서는 읽기만 하고 갱신은 호출자가 한다.
 */
fun evaluateAlarms(
    previous: AlarmState?,
    todayKey: String,
    usedMillis: Long,
    limitMillis: Long,
    intervalMinutes: Int,
    milestonesEnabled: Boolean,
    minutesUntilScheduleStart: Long? = null,
    scheduleStartNotified: Boolean = false,
): AlarmOutcome {
    // A state from an earlier day carries stale bookkeeping, so start the day fresh.
    var state = previous?.takeIf { it.dateKey == todayKey } ?: AlarmState(todayKey)
    val messages = mutableListOf<AlarmMessage>()

    if (intervalMinutes > 0) {
        val intervalMillis = minutesToMillis(intervalMinutes)
        if (usedMillis - state.lastIntervalNotifyMillis >= intervalMillis) {
            // Snap to the interval boundary so a long gap does not queue up a burst.
            state = state.copy(lastIntervalNotifyMillis = (usedMillis / intervalMillis) * intervalMillis)
            messages += AlarmMessage.WatchedMinutes(usedMillis / 60_000)
        }
    }

    if (milestonesEnabled && !isUnlimited(limitMillis)) {
        val remaining = limitMillis - usedMillis
        val due = ALARM_MILESTONE_MINUTES.filter { minutes ->
            remaining > 0 &&
                remaining <= minutesToMillis(minutes) &&
                minutes !in state.notifiedMilestones
        }
        if (due.isNotEmpty()) {
            state = state.copy(notifiedMilestones = state.notifiedMilestones + due)
            due.forEach { messages += AlarmMessage.RemainingMinutes(it) }
        }
    }

    // 예약 차단 예고. 하루 한 번만 알리면 충분하므로 dedupe는 날짜로 한다 — 확장의
    // `state.scheduleStartNotified`와 같은 규칙이고, 안드로이드는 그 표시를 별도 키에 담는다.
    if (minutesUntilScheduleStart != null &&
        minutesUntilScheduleStart > 0 &&
        minutesUntilScheduleStart <= SCHEDULE_SOON_LEAD_MINUTES &&
        !scheduleStartNotified
    ) {
        messages += AlarmMessage.ScheduleSoon(SCHEDULE_SOON_LEAD_MINUTES)
    }

    return AlarmOutcome(state, messages)
}
