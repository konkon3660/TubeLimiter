package com.tubelimiter.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tubelimiter.app.gamification.StreakRecord
import com.tubelimiter.app.limit.AlarmState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val KEY_USAGE_HISTORY = stringPreferencesKey("usage_history")
private val KEY_USAGE_HISTORY_HOURLY = stringPreferencesKey("usage_history_hourly")
/** Per-day share of [KEY_USAGE_HISTORY] that was watched on an emergency pass, and how many
 * passes that day spent. Kept apart so the rollover can excuse emergency time from the streak
 * while still barring that day from the perfect-day count. */
private val KEY_EMERGENCY_MILLIS_HISTORY = stringPreferencesKey("emergency_history")
private val KEY_EMERGENCY_USES_HISTORY = stringPreferencesKey("emergency_uses_history")
private val KEY_LAST_ROLLOVER_DATE = stringPreferencesKey("last_rollover_date")

private val KEY_STREAK_CURRENT = intPreferencesKey("streak_current")
private val KEY_STREAK_BEST = intPreferencesKey("streak_best")
private val KEY_STREAK_LAST_DATE = stringPreferencesKey("streak_last_date")
private val KEY_STREAK_TOTAL_SUCCESS = intPreferencesKey("streak_total_success")
private val KEY_STREAK_XP = intPreferencesKey("streak_xp")
private val KEY_STREAK_PERFECT_DAYS = intPreferencesKey("streak_perfect_days")
private val KEY_STREAK_PERFECT_CURRENT = intPreferencesKey("streak_perfect_current")
private val KEY_STREAK_PERFECT_BEST = intPreferencesKey("streak_perfect_best")
private val KEY_ACHIEVEMENTS = stringPreferencesKey("achievements")

private val KEY_ALARM_DATE = stringPreferencesKey("alarm_date")
private val KEY_ALARM_LAST_INTERVAL = longPreferencesKey("alarm_last_interval")
private val KEY_ALARM_MILESTONES_DONE = stringPreferencesKey("alarm_milestones_done")

private val KEY_MANUAL_BLOCK = booleanPreferencesKey("manual_block")
private val KEY_FOCUS_END = longPreferencesKey("focus_end")
private val KEY_FOCUS_DELAY_END = longPreferencesKey("focus_delay_end")
private val KEY_FOCUS_DELAY_DURATION = intPreferencesKey("focus_delay_duration")
/** When an already-active focus session was asked to stop early; null means no request pending. */
private val KEY_FOCUS_STOP_REQUESTED_AT = longPreferencesKey("focus_stop_requested_at")
private val KEY_EMERGENCY_END = longPreferencesKey("emergency_end")
private val KEY_EMERGENCY_REMAINING = intPreferencesKey("emergency_remaining")
private val KEY_EMERGENCY_RESET_KEY = stringPreferencesKey("emergency_reset_key")
private val KEY_LAST_EMERGENCY_GRANTED_AT = longPreferencesKey("last_emergency_granted_at")

private val KEY_DAILY_USAGE_SYNC_DATE = stringPreferencesKey("daily_usage_sync_date")
private val KEY_DAILY_USAGE_SYNCED_MILLIS = longPreferencesKey("daily_usage_synced_millis")
private val KEY_DAILY_USAGE_COMBINED_MILLIS = longPreferencesKey("daily_usage_combined_millis")
private val KEY_DAILY_USAGE_EMERGENCY_SYNCED_MILLIS = longPreferencesKey("daily_usage_emergency_synced_millis")

/** Whether a scheduled-block window was active the last time it was checked, so `tick()` can
 * detect the start/end transition (survives process death, unlike an in-memory flag). */
private val KEY_SCHEDULE_BLOCK_WAS_ACTIVE = booleanPreferencesKey("schedule_block_was_active")
/** Date the "10 minutes until a scheduled block starts" nudge was last sent, for once-per-day dedupe. */
private val KEY_SCHEDULE_START_NOTIFIED_DATE = stringPreferencesKey("schedule_start_notified_date")

/** How many days of usage history to keep for the dashboard. */
const val HISTORY_RETENTION_DAYS = 60

data class RuntimeState(
    val usageHistory: Map<String, Long> = emptyMap(),
    /** Same days as [usageHistory], broken down by wall-clock hour (0-23); feeds the dashboard's
     * time-of-day pattern chart. Days recorded before this field existed are simply absent. */
    val usageHistoryHourly: Map<String, Map<Int, Long>> = emptyMap(),
    /** Per-day emergency-pass time and use count, keyed like [usageHistory]. */
    val emergencyMillisHistory: Map<String, Long> = emptyMap(),
    val emergencyUseHistory: Map<String, Long> = emptyMap(),
    val lastRolloverDate: String? = null,
    val streak: StreakRecord = StreakRecord(),
    val achievements: Set<String> = emptySet(),
    val alarm: AlarmState? = null,
    val manuallyBlocked: Boolean = false,
    val focusEndMillis: Long? = null,
    val focusDelayEndMillis: Long? = null,
    val focusDelayDurationMinutes: Int = 0,
    /** Set once the user asks to stop an already-active focus session; see [com.tubelimiter.app.limit.resolveFocusStopTime]. */
    val focusStopRequestedAtMillis: Long? = null,
    val emergencyEndMillis: Long? = null,
    val emergencyRemaining: Int? = null,
    val emergencyResetKey: String? = null,
    /** When the last emergency pass was granted, for the extra 15s anti-mash cooldown. */
    val lastEmergencyGrantedAtMillis: Long? = null,
    /** Date this device last told the server about its usage, and how much it reported. */
    val dailyUsageSyncDate: String? = null,
    val dailyUsageSyncedMillis: Long = 0L,
    /** Latest known cross-device total for [dailyUsageSyncDate] (includes this device's own contribution). */
    val dailyUsageCombinedMillis: Long = 0L,
    /** How much emergency-pass time this device already reported for [dailyUsageSyncDate]. */
    val dailyUsageEmergencySyncedMillis: Long = 0L,
    val scheduleBlockWasActive: Boolean = false,
    val scheduleStartNotifiedDate: String? = null,
) {
    fun focusActiveAt(nowMillis: Long): Boolean =
        focusEndMillis != null && nowMillis < focusEndMillis

    fun emergencyActiveAt(nowMillis: Long): Boolean =
        emergencyEndMillis != null && nowMillis < emergencyEndMillis

    fun emergencyMillisOn(dateKey: String): Long = emergencyMillisHistory[dateKey] ?: 0L

    fun emergencyUsesOn(dateKey: String): Int = (emergencyUseHistory[dateKey] ?: 0L).toInt()
}

class AppState(private val context: Context) {

    val state: Flow<RuntimeState> = context.dataStore.data.map { it.toRuntimeState() }

    private fun Preferences.toRuntimeState() = RuntimeState(
        usageHistory = decodeLongMap(this[KEY_USAGE_HISTORY]),
        usageHistoryHourly = decodeHourlyMap(this[KEY_USAGE_HISTORY_HOURLY]),
        emergencyMillisHistory = decodeLongMap(this[KEY_EMERGENCY_MILLIS_HISTORY]),
        emergencyUseHistory = decodeLongMap(this[KEY_EMERGENCY_USES_HISTORY]),
        lastRolloverDate = this[KEY_LAST_ROLLOVER_DATE],
        streak = StreakRecord(
            currentStreak = this[KEY_STREAK_CURRENT] ?: 0,
            bestStreak = this[KEY_STREAK_BEST] ?: 0,
            lastResultDate = this[KEY_STREAK_LAST_DATE],
            totalSuccessDays = this[KEY_STREAK_TOTAL_SUCCESS] ?: 0,
            xp = this[KEY_STREAK_XP] ?: 0,
            perfectDays = this[KEY_STREAK_PERFECT_DAYS] ?: 0,
            currentPerfectStreak = this[KEY_STREAK_PERFECT_CURRENT] ?: 0,
            bestPerfectStreak = this[KEY_STREAK_PERFECT_BEST] ?: 0,
        ),
        achievements = decodeStringSet(this[KEY_ACHIEVEMENTS]),
        alarm = this[KEY_ALARM_DATE]?.let { date ->
            AlarmState(
                dateKey = date,
                lastIntervalNotifyMillis = this[KEY_ALARM_LAST_INTERVAL] ?: 0L,
                notifiedMilestones = decodeIntSet(this[KEY_ALARM_MILESTONES_DONE]),
            )
        },
        manuallyBlocked = this[KEY_MANUAL_BLOCK] ?: false,
        focusEndMillis = this[KEY_FOCUS_END],
        focusDelayEndMillis = this[KEY_FOCUS_DELAY_END],
        focusDelayDurationMinutes = this[KEY_FOCUS_DELAY_DURATION] ?: 0,
        focusStopRequestedAtMillis = this[KEY_FOCUS_STOP_REQUESTED_AT],
        emergencyEndMillis = this[KEY_EMERGENCY_END],
        emergencyRemaining = this[KEY_EMERGENCY_REMAINING],
        emergencyResetKey = this[KEY_EMERGENCY_RESET_KEY],
        lastEmergencyGrantedAtMillis = this[KEY_LAST_EMERGENCY_GRANTED_AT],
        dailyUsageSyncDate = this[KEY_DAILY_USAGE_SYNC_DATE],
        dailyUsageSyncedMillis = this[KEY_DAILY_USAGE_SYNCED_MILLIS] ?: 0L,
        dailyUsageCombinedMillis = this[KEY_DAILY_USAGE_COMBINED_MILLIS] ?: 0L,
        dailyUsageEmergencySyncedMillis = this[KEY_DAILY_USAGE_EMERGENCY_SYNCED_MILLIS] ?: 0L,
        scheduleBlockWasActive = this[KEY_SCHEDULE_BLOCK_WAS_ACTIVE] ?: false,
        scheduleStartNotifiedDate = this[KEY_SCHEDULE_START_NOTIFIED_DATE],
    )

    suspend fun recordUsage(dateKey: String, usedMillis: Long, keepKeys: Set<String>) = edit { prefs ->
        val history = decodeLongMap(prefs[KEY_USAGE_HISTORY]) + (dateKey to usedMillis)
        prefs[KEY_USAGE_HISTORY] = encodeLongMap(pruneHistory(history, keepKeys + dateKey))
    }

    /**
     * Adds [deltaMillis] to [dateKey]'s bucket for [hour] (0-23), analogous to [recordUsage]
     * but additive rather than absolute since callers only know how much time passed since the
     * last tick, not a running total for that hour.
     */
    suspend fun recordHourlyUsage(dateKey: String, hour: Int, deltaMillis: Long, keepKeys: Set<String>) = edit { prefs ->
        val history = decodeHourlyMap(prefs[KEY_USAGE_HISTORY_HOURLY])
        val dayHours = history[dateKey] ?: emptyMap()
        val updatedDayHours = dayHours + (hour to (dayHours[hour] ?: 0L) + deltaMillis)
        val updated = history + (dateKey to updatedDayHours)
        prefs[KEY_USAGE_HISTORY_HOURLY] = encodeHourlyMap(pruneHourlyHistory(updated, keepKeys + dateKey))
    }

    /**
     * Adds [deltaMillis] of emergency-pass viewing to [dateKey]. Additive like
     * [recordHourlyUsage], since a tick only knows how much of its own window the pass covered.
     */
    suspend fun recordEmergencyUsage(dateKey: String, deltaMillis: Long, keepKeys: Set<String>) = edit { prefs ->
        val history = decodeLongMap(prefs[KEY_EMERGENCY_MILLIS_HISTORY])
        val updated = history + (dateKey to (history[dateKey] ?: 0L) + deltaMillis)
        prefs[KEY_EMERGENCY_MILLIS_HISTORY] = encodeLongMap(pruneHistory(updated, keepKeys + dateKey))
    }

    /** Records that one emergency pass was spent on [dateKey] - a day with any use is never perfect. */
    suspend fun recordEmergencyUse(dateKey: String, keepKeys: Set<String>) = edit { prefs ->
        val history = decodeLongMap(prefs[KEY_EMERGENCY_USES_HISTORY])
        val updated = history + (dateKey to (history[dateKey] ?: 0L) + 1L)
        prefs[KEY_EMERGENCY_USES_HISTORY] = encodeLongMap(pruneHistory(updated, keepKeys + dateKey))
    }

    suspend fun saveRollover(record: StreakRecord, unlockedKeys: Set<String>, lastRolloverDate: String) =
        edit { prefs ->
            prefs[KEY_STREAK_CURRENT] = record.currentStreak
            prefs[KEY_STREAK_BEST] = record.bestStreak
            record.lastResultDate?.let { prefs[KEY_STREAK_LAST_DATE] = it }
            prefs[KEY_STREAK_TOTAL_SUCCESS] = record.totalSuccessDays
            prefs[KEY_STREAK_XP] = record.xp
            prefs[KEY_STREAK_PERFECT_DAYS] = record.perfectDays
            prefs[KEY_STREAK_PERFECT_CURRENT] = record.currentPerfectStreak
            prefs[KEY_STREAK_PERFECT_BEST] = record.bestPerfectStreak
            prefs[KEY_LAST_ROLLOVER_DATE] = lastRolloverDate
            if (unlockedKeys.isNotEmpty()) {
                val merged = decodeStringSet(prefs[KEY_ACHIEVEMENTS]) + unlockedKeys
                prefs[KEY_ACHIEVEMENTS] = encodeStringSet(merged)
            }
        }

    suspend fun setLastRolloverDate(dateKey: String) =
        edit { it[KEY_LAST_ROLLOVER_DATE] = dateKey }

    /** Writes a streak that came back from the server, leaving rollover bookkeeping alone. */
    suspend fun saveStreak(record: StreakRecord, achievements: Set<String>) = edit { prefs ->
        prefs[KEY_STREAK_CURRENT] = record.currentStreak
        prefs[KEY_STREAK_BEST] = record.bestStreak
        record.lastResultDate?.let { prefs[KEY_STREAK_LAST_DATE] = it }
        prefs[KEY_STREAK_TOTAL_SUCCESS] = record.totalSuccessDays
        prefs[KEY_STREAK_XP] = record.xp
        prefs[KEY_STREAK_PERFECT_DAYS] = record.perfectDays
        prefs[KEY_STREAK_PERFECT_CURRENT] = record.currentPerfectStreak
        prefs[KEY_STREAK_PERFECT_BEST] = record.bestPerfectStreak
        prefs[KEY_ACHIEVEMENTS] = encodeStringSet(achievements)
    }

    /**
     * Hardcore mode is the price of the streak: dropping it wipes the running count. The perfect-day
     * run is the same kind of in-progress record so it goes too; the earned totals stay.
     */
    suspend fun resetCurrentStreak() = edit { prefs ->
        prefs[KEY_STREAK_CURRENT] = 0
        prefs[KEY_STREAK_PERFECT_CURRENT] = 0
    }

    suspend fun saveAlarmState(state: AlarmState) = edit { prefs ->
        prefs[KEY_ALARM_DATE] = state.dateKey
        prefs[KEY_ALARM_LAST_INTERVAL] = state.lastIntervalNotifyMillis
        prefs[KEY_ALARM_MILESTONES_DONE] = encodeIntSet(state.notifiedMilestones)
    }

    suspend fun setManuallyBlocked(blocked: Boolean) = edit { it[KEY_MANUAL_BLOCK] = blocked }

    suspend fun startFocus(endMillis: Long) = edit { prefs ->
        prefs[KEY_FOCUS_END] = endMillis
        prefs.remove(KEY_FOCUS_DELAY_END)
        prefs.remove(KEY_FOCUS_DELAY_DURATION)
        prefs.remove(KEY_FOCUS_STOP_REQUESTED_AT)
    }

    suspend fun scheduleFocus(delayEndMillis: Long, durationMinutes: Int) = edit { prefs ->
        prefs[KEY_FOCUS_DELAY_END] = delayEndMillis
        prefs[KEY_FOCUS_DELAY_DURATION] = durationMinutes
    }

    suspend fun clearFocus() = edit { prefs ->
        prefs.remove(KEY_FOCUS_END)
        prefs.remove(KEY_FOCUS_DELAY_END)
        prefs.remove(KEY_FOCUS_DELAY_DURATION)
        prefs.remove(KEY_FOCUS_STOP_REQUESTED_AT)
    }

    /**
     * Asks to stop an already-active focus session early. Only records the request (the
     * session keeps blocking) - [com.tubelimiter.app.service.UsageMonitorService]'s
     * `advanceFocusMode` is what actually clears the session once
     * [com.tubelimiter.app.limit.resolveFocusStopTime] says the cooldown (or the session's
     * own natural end) has arrived. A second request while one is already pending is a no-op,
     * so re-clicking cannot push the cooldown back out.
     */
    suspend fun requestFocusStop(nowMillis: Long) = edit { prefs ->
        if (prefs[KEY_FOCUS_STOP_REQUESTED_AT] == null) {
            prefs[KEY_FOCUS_STOP_REQUESTED_AT] = nowMillis
        }
    }

    suspend fun cancelFocusStop() = edit { it.remove(KEY_FOCUS_STOP_REQUESTED_AT) }

    /**
     * Spends one emergency pass. The check and the decrement share a single edit so two
     * taps in quick succession cannot both get through on the last remaining use.
     * Returns false when none are left. Callers are expected to have already checked focus-mode
     * and the anti-mash cooldown (see [RuntimeState.focusActiveAt] and
     * [com.tubelimiter.app.limit.emergencyGrantCooldownRemainingMillis]) - this only guards the
     * use-count allowance itself.
     */
    suspend fun consumeEmergency(nowMillis: Long, endMillis: Long): Boolean {
        var granted = false
        context.dataStore.edit { prefs ->
            val remaining = prefs[KEY_EMERGENCY_REMAINING] ?: 0
            if (remaining <= 0) return@edit
            prefs[KEY_EMERGENCY_REMAINING] = remaining - 1
            prefs[KEY_EMERGENCY_END] = endMillis
            prefs[KEY_LAST_EMERGENCY_GRANTED_AT] = nowMillis
            granted = true
        }
        return granted
    }

    suspend fun clearEmergency() = edit { it.remove(KEY_EMERGENCY_END) }

    suspend fun resetEmergencyAllowance(resetKey: String, allowance: Int) = edit { prefs ->
        prefs[KEY_EMERGENCY_RESET_KEY] = resetKey
        prefs[KEY_EMERGENCY_REMAINING] = allowance
    }

    /** Records that [dateKey]'s usage was reported up to [syncedMillis], and the server's combined total. */
    suspend fun recordDailyUsageSync(
        dateKey: String,
        syncedMillis: Long,
        combinedMillis: Long,
        emergencySyncedMillis: Long = 0L,
    ) = edit { prefs ->
        prefs[KEY_DAILY_USAGE_SYNC_DATE] = dateKey
        prefs[KEY_DAILY_USAGE_SYNCED_MILLIS] = syncedMillis
        prefs[KEY_DAILY_USAGE_COMBINED_MILLIS] = combinedMillis
        prefs[KEY_DAILY_USAGE_EMERGENCY_SYNCED_MILLIS] = emergencySyncedMillis
    }

    suspend fun setScheduleBlockWasActive(active: Boolean) = edit { it[KEY_SCHEDULE_BLOCK_WAS_ACTIVE] = active }

    suspend fun setScheduleStartNotifiedDate(dateKey: String) = edit { it[KEY_SCHEDULE_START_NOTIFIED_DATE] = dateKey }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
