package com.tubelimiter.app.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

private const val TAG = "UsageStatsReader"
private const val HISTORY_LOOKBACK_MILLIS = 24 * 60 * 60 * 1000L

data class UsageSnapshot(
    val usedMillis: Long,
    val inForeground: Boolean,
)

class UsageStatsReader(context: Context) {

    private val usageStatsManager = context.getSystemService(UsageStatsManager::class.java)

    /** One query serves both the day total and the "is it on screen right now" check. */
    fun snapshot(
        packages: List<String> = ALWAYS_WATCHED_PACKAGES,
        windowStart: Long,
        windowEnd: Long,
    ): UsageSnapshot {
        // Look back to find the state at windowStart: a session opened before the window
        // (yesterday evening, say) still counts against it.
        val byPackage = readTransitions(packages, windowStart - HISTORY_LOOKBACK_MILLIS, windowEnd)

        val intervals = mutableListOf<ForegroundInterval>()
        var inForeground = false
        var eventsInWindow = 0
        byPackage.values.forEach { allEvents ->
            val startsInForeground = allEvents
                .lastOrNull { it.timestampMillis < windowStart }
                ?.type == UsageTransition.Type.FOREGROUND

            val windowEvents = allEvents.filter { it.timestampMillis in windowStart..windowEnd }
            eventsInWindow += windowEvents.size
            intervals += foregroundIntervals(windowEvents, windowStart, windowEnd, startsInForeground)
            // 감시 대상 중 **하나라도** 떠 있으면 오버레이를 띄워야 한다.
            if (if (windowEvents.isEmpty()) startsInForeground else isInForeground(windowEvents)) {
                inForeground = true
            }
        }

        // 겹치는 구간을 두 번 세지 않는다 — [unionMillis] 주석 참고.
        val used = unionMillis(intervals)

        Log.d(
            TAG,
            "${packages.size} package(s): used=${used}ms inForeground=$inForeground " +
                "eventsInWindow=$eventsInWindow",
        )
        return UsageSnapshot(used, inForeground)
    }

    /**
     * 한 번의 [UsageStatsManager.queryEvents]로 여러 패키지를 갈라 담는다. 패키지마다 따로
     * 조회하면 같은 이벤트 스트림을 n번 훑게 되는데, 이 호출은 유튜브가 떠 있는 동안 5초마다
     * 돌기 때문에 그 배수가 그대로 배터리로 간다.
     */
    fun readTransitions(
        packages: Collection<String>,
        windowStart: Long,
        windowEnd: Long,
    ): Map<String, List<UsageTransition>> {
        if (packages.isEmpty()) return emptyMap()
        val byPackage = packages.associateWith { mutableListOf<UsageTransition>() }
        val events = usageStatsManager.queryEvents(windowStart, windowEnd) ?: return emptyMap()
        val event = UsageEvents.Event()

        while (events.getNextEvent(event)) {
            val bucket = byPackage[event.packageName] ?: continue
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageTransition.Type.FOREGROUND
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> UsageTransition.Type.BACKGROUND

                else -> continue
            }
            bucket += UsageTransition(event.timeStamp, type)
        }
        return byPackage
    }
}
