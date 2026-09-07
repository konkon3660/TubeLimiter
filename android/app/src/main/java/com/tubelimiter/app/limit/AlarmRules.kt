package com.tubelimiter.app.limit

/** Fire a "time is running out" nudge when the remaining time crosses each of these. */
val ALARM_MILESTONE_MINUTES = listOf(30, 10, 5, 1)

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

    return AlarmOutcome(state, messages)
}
