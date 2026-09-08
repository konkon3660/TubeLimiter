package com.tubelimiter.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.tubelimiter.app.limit.DEFAULT_LIMIT_MINUTES
import com.tubelimiter.app.limit.EmergencyResetFrequency
import com.tubelimiter.app.limit.LimitConfig
import com.tubelimiter.app.limit.LimitFrequency
import com.tubelimiter.app.limit.ScheduleWindow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tubelimiter_settings")

private val KEY_LIMIT_MINUTES = intPreferencesKey("daily_limit_minutes")
private val KEY_LIMIT_BY_DAY = stringPreferencesKey("daily_limit_by_day")
private val KEY_LIMIT_FREQUENCY = stringPreferencesKey("daily_limit_frequency")
private val KEY_MONITORING = booleanPreferencesKey("monitoring_enabled")
private val KEY_EMERGENCY_ALLOWANCE = intPreferencesKey("emergency_allowance")
private val KEY_EMERGENCY_RESET = stringPreferencesKey("emergency_reset_frequency")
private val KEY_ALARM_INTERVAL = intPreferencesKey("alarm_interval_minutes")
private val KEY_ALARM_MILESTONES = booleanPreferencesKey("alarm_milestones_enabled")
private val KEY_HARDCORE = booleanPreferencesKey("hardcore_mode")
private val KEY_HARDCORE_DISABLE_AT = longPreferencesKey("hardcore_disable_requested_at")
private val KEY_CHART_RANGE_DAYS = intPreferencesKey("chart_range_days")
private val KEY_SCHEDULE_WINDOWS = stringPreferencesKey("scheduled_blocks")
private val KEY_WATCH_MUSIC = booleanPreferencesKey("watch_youtube_music")

const val DEFAULT_EMERGENCY_ALLOWANCE = 3

/** Default and only presets for the dashboard chart's selectable date range. */
const val DEFAULT_CHART_RANGE_DAYS = 14
val CHART_RANGE_PRESETS_DAYS = listOf(7, 14, 30)

data class Settings(
    val limit: LimitConfig = LimitConfig(),
    val monitoringEnabled: Boolean = true,
    val emergencyAllowance: Int = DEFAULT_EMERGENCY_ALLOWANCE,
    val emergencyResetFrequency: EmergencyResetFrequency = EmergencyResetFrequency.DAILY,
    val alarmIntervalMinutes: Int = 0,
    val alarmMilestonesEnabled: Boolean = true,
    val hardcoreMode: Boolean = false,
    val hardcoreDisableRequestedAt: Long? = null,
    /** Device-local dashboard display preference; deliberately not part of the synced settings row. */
    val chartRangeDays: Int = DEFAULT_CHART_RANGE_DAYS,
    /** Recurring time-of-day curfew windows; synced (unlike whitelist/always_block_shorts, a
     * time-of-day block applies to this native app too). See [com.tubelimiter.app.limit.isScheduleActive]. */
    val scheduleWindows: List<ScheduleWindow> = emptyList(),
    /**
     * YouTube Music도 감시 대상에 넣을지. **기본은 꺼짐** — 이유는
     * [com.tubelimiter.app.usage.watchedPackages] 주석에 있다(작업용 BGM으로 영상 한도가 깎이면
     * 안 된다).
     *
     * 확장 쪽 짝인 화이트리스트가 동기화되지 않는 기기별 값이라, 이것도 동기화하지 않는다
     * ([replaceAll]에 없는 이유). 폰에는 뮤직 앱이 있고 PC에는 없는 식으로 갈리는 값이다.
     */
    val watchYouTubeMusic: Boolean = false,
)

class AppSettings(private val context: Context) {

    val settings: Flow<Settings> = context.dataStore.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        limit = LimitConfig(
            dailyLimitMinutes = this[KEY_LIMIT_MINUTES] ?: DEFAULT_LIMIT_MINUTES,
            byDayMinutes = decodeIntList(this[KEY_LIMIT_BY_DAY], size = 7, default = DEFAULT_LIMIT_MINUTES),
            frequency = this[KEY_LIMIT_FREQUENCY]?.let { name ->
                LimitFrequency.entries.firstOrNull { it.name == name }
            } ?: LimitFrequency.DAILY,
        ),
        monitoringEnabled = this[KEY_MONITORING] ?: true,
        emergencyAllowance = this[KEY_EMERGENCY_ALLOWANCE] ?: DEFAULT_EMERGENCY_ALLOWANCE,
        emergencyResetFrequency = this[KEY_EMERGENCY_RESET]?.let { name ->
            EmergencyResetFrequency.entries.firstOrNull { it.name == name }
        } ?: EmergencyResetFrequency.DAILY,
        alarmIntervalMinutes = this[KEY_ALARM_INTERVAL] ?: 0,
        alarmMilestonesEnabled = this[KEY_ALARM_MILESTONES] ?: true,
        hardcoreMode = this[KEY_HARDCORE] ?: false,
        hardcoreDisableRequestedAt = this[KEY_HARDCORE_DISABLE_AT],
        chartRangeDays = this[KEY_CHART_RANGE_DAYS] ?: DEFAULT_CHART_RANGE_DAYS,
        scheduleWindows = decodeScheduleWindows(this[KEY_SCHEDULE_WINDOWS]),
        watchYouTubeMusic = this[KEY_WATCH_MUSIC] ?: false,
    )

    suspend fun setDailyLimitMinutes(minutes: Int) = edit { it[KEY_LIMIT_MINUTES] = minutes }

    suspend fun setByDayMinutes(minutes: List<Int>) =
        edit { it[KEY_LIMIT_BY_DAY] = encodeIntList(minutes) }

    suspend fun setLimitFrequency(frequency: LimitFrequency) =
        edit { it[KEY_LIMIT_FREQUENCY] = frequency.name }

    suspend fun setMonitoringEnabled(enabled: Boolean) = edit { it[KEY_MONITORING] = enabled }

    suspend fun setEmergencyAllowance(count: Int) = edit { it[KEY_EMERGENCY_ALLOWANCE] = count }

    suspend fun setEmergencyResetFrequency(frequency: EmergencyResetFrequency) =
        edit { it[KEY_EMERGENCY_RESET] = frequency.name }

    suspend fun setAlarmIntervalMinutes(minutes: Int) = edit { it[KEY_ALARM_INTERVAL] = minutes }

    suspend fun setAlarmMilestonesEnabled(enabled: Boolean) =
        edit { it[KEY_ALARM_MILESTONES] = enabled }

    /** Turning hardcore on is immediate; turning it off only records the request. */
    suspend fun setHardcoreMode(enabled: Boolean) = edit {
        it[KEY_HARDCORE] = enabled
        it.remove(KEY_HARDCORE_DISABLE_AT)
    }

    suspend fun requestHardcoreDisable(nowMillis: Long) =
        edit { it[KEY_HARDCORE_DISABLE_AT] = nowMillis }

    suspend fun cancelHardcoreDisable() = edit { it.remove(KEY_HARDCORE_DISABLE_AT) }

    suspend fun setChartRangeDays(days: Int) = edit { it[KEY_CHART_RANGE_DAYS] = days }

    suspend fun setScheduleWindows(windows: List<ScheduleWindow>) =
        edit { it[KEY_SCHEDULE_WINDOWS] = encodeScheduleWindows(windows) }

    suspend fun setWatchYouTubeMusic(enabled: Boolean) = edit { it[KEY_WATCH_MUSIC] = enabled }

    /** Applies a whole settings snapshot at once, used when the server hands one back. */
    suspend fun replaceAll(settings: Settings) = edit { prefs ->
        prefs[KEY_LIMIT_MINUTES] = settings.limit.dailyLimitMinutes
        prefs[KEY_LIMIT_BY_DAY] = encodeIntList(settings.limit.byDayMinutes)
        prefs[KEY_LIMIT_FREQUENCY] = settings.limit.frequency.name
        prefs[KEY_MONITORING] = settings.monitoringEnabled
        prefs[KEY_EMERGENCY_ALLOWANCE] = settings.emergencyAllowance
        prefs[KEY_EMERGENCY_RESET] = settings.emergencyResetFrequency.name
        prefs[KEY_ALARM_INTERVAL] = settings.alarmIntervalMinutes
        prefs[KEY_ALARM_MILESTONES] = settings.alarmMilestonesEnabled
        prefs[KEY_HARDCORE] = settings.hardcoreMode
        val disableAt = settings.hardcoreDisableRequestedAt
        if (disableAt == null) prefs.remove(KEY_HARDCORE_DISABLE_AT) else prefs[KEY_HARDCORE_DISABLE_AT] = disableAt
        prefs[KEY_SCHEDULE_WINDOWS] = encodeScheduleWindows(settings.scheduleWindows)
    }

    /**
     * Drops every stored setting so the reader above falls back to the declared defaults.
     * Used after the account is deleted: the settings row was mirrored from the server (see
     * [replaceAll]), so leaving it behind would keep the deleted account's configuration on the
     * device. Removes rather than writes defaults, so a later sign-in on a fresh account seeds
     * its row from the same values a first install would.
     *
     * Only touches this file's keys — [AppState] shares the same DataStore and clears its own.
     */
    suspend fun resetToDefaults() = edit { prefs ->
        prefs.remove(KEY_LIMIT_MINUTES)
        prefs.remove(KEY_LIMIT_BY_DAY)
        prefs.remove(KEY_LIMIT_FREQUENCY)
        prefs.remove(KEY_MONITORING)
        prefs.remove(KEY_EMERGENCY_ALLOWANCE)
        prefs.remove(KEY_EMERGENCY_RESET)
        prefs.remove(KEY_ALARM_INTERVAL)
        prefs.remove(KEY_ALARM_MILESTONES)
        prefs.remove(KEY_HARDCORE)
        prefs.remove(KEY_HARDCORE_DISABLE_AT)
        prefs.remove(KEY_CHART_RANGE_DAYS)
        prefs.remove(KEY_SCHEDULE_WINDOWS)
        prefs.remove(KEY_WATCH_MUSIC)
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
