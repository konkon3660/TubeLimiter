package com.tubelimiter.app.limit

/** Fire a "time is running out" nudge when the remaining time crosses each of these. */
val ALARM_MILESTONE_MINUTES = listOf(30, 10, 5, 1)

data class AlarmState(
    val dateKey: String,
    val lastIntervalNotifyMillis: Long = 0L,
    val notifiedMilestones: Set<Int> = emptySet(),
)

data class AlarmOutcome(
    val state: AlarmState,
    val messages: List<String>,
) {
    val changed: Boolean get() = messages.isNotEmpty()
}

/**
 * Decides which nudges are due. Ported from the extension's `checkAlarms`; kept pure so
 * the "did we already say this today" bookkeeping is testable without a device.
 */
fun evaluateAlarms(
    previous: AlarmState?,
    todayKey: String,
    usedMillis: Long,
    limitMillis: Long,
    intervalMinutes: Int,
    milestonesEnabled: Boolean,
): AlarmOutcome {
    // A state from an earlier day carries stale bookkeeping, so start the day fresh.
    var state = previous?.takeIf { it.dateKey == todayKey } ?: AlarmState(todayKey)
    val messages = mutableListOf<String>()

    if (intervalMinutes > 0) {
        val intervalMillis = minutesToMillis(intervalMinutes)
        if (usedMillis - state.lastIntervalNotifyMillis >= intervalMillis) {
            // Snap to the interval boundary so a long gap does not queue up a burst.
            state = state.copy(lastIntervalNotifyMillis = (usedMillis / intervalMillis) * intervalMillis)
            messages += "오늘 유튜브를 ${usedMillis / 60_000}분째 보고 있어요."
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
            due.forEach { messages += "오늘 남은 시청 시간이 ${it}분입니다." }
        }
    }

    return AlarmOutcome(state, messages)
}
