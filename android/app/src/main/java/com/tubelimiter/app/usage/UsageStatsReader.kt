package com.tubelimiter.app.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log

const val YOUTUBE_PACKAGE = "com.google.android.youtube"
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
        packageName: String = YOUTUBE_PACKAGE,
        windowStart: Long,
        windowEnd: Long,
    ): UsageSnapshot {
        // Look back to find the state at windowStart: a session opened before the window
        // (yesterday evening, say) still counts against it.
        val allEvents = readTransitions(packageName, windowStart - HISTORY_LOOKBACK_MILLIS, windowEnd)

        val startsInForeground = allEvents
            .lastOrNull { it.timestampMillis < windowStart }
            ?.type == UsageTransition.Type.FOREGROUND

        val windowEvents = allEvents.filter { it.timestampMillis in windowStart..windowEnd }
        val used = foldForegroundMillis(windowEvents, windowStart, windowEnd, startsInForeground)
        val inForeground = if (windowEvents.isEmpty()) startsInForeground else isInForeground(windowEvents)

        Log.d(
            TAG,
            "$packageName: used=${used}ms inForeground=$inForeground " +
                "startsInForeground=$startsInForeground eventsInWindow=${windowEvents.size}",
        )
        return UsageSnapshot(used, inForeground)
    }

    fun foregroundMillis(
        packageName: String = YOUTUBE_PACKAGE,
        windowStart: Long,
        windowEnd: Long,
    ): Long = snapshot(packageName, windowStart, windowEnd).usedMillis

    fun readTransitions(
        packageName: String,
        windowStart: Long,
        windowEnd: Long,
    ): List<UsageTransition> {
        val transitions = mutableListOf<UsageTransition>()
        val events = usageStatsManager.queryEvents(windowStart, windowEnd) ?: return emptyList()
        val event = UsageEvents.Event()

        while (events.getNextEvent(event)) {
            if (event.packageName != packageName) continue
            val type = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageTransition.Type.FOREGROUND
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED,
                -> UsageTransition.Type.BACKGROUND

                else -> continue
            }
            transitions += UsageTransition(event.timeStamp, type)
        }
        return transitions
    }
}
