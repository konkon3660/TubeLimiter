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

/**
 * 서버 `settings` 행에서 내려받는 값 = 계정에서 온 값. 계정이 바뀌면
 * [AppSettings.resetSyncedToDefaults]가 정확히 이만큼만 지운다 — 특히 `hardcore_mode`가 여기
 * 있는 게 핵심이다. 남겨두면 새로 로그인한 계정이 **직전 계정의 하드코어 잠금을 물려받는다**
 * (documents/BACKEND.md "계정 전환 — 무엇을 지우고 무엇을 남기나" 7번).
 *
 * 컬럼 목록은 [com.tubelimiter.app.sync.SETTINGS_COLUMNS]와 짝이다.
 */
internal val SYNCED_SETTINGS_KEYS: List<Preferences.Key<*>> = listOf(
    KEY_LIMIT_MINUTES,
    KEY_LIMIT_BY_DAY,
    KEY_LIMIT_FREQUENCY,
    KEY_EMERGENCY_ALLOWANCE,
    KEY_EMERGENCY_RESET,
    KEY_ALARM_INTERVAL,
    KEY_ALARM_MILESTONES,
    KEY_HARDCORE,
    KEY_HARDCORE_DISABLE_AT,
    KEY_SCHEDULE_WINDOWS,
)

/**
 * 계정과 무관한 이 기기만의 값. 계정이 바뀌어도 **남긴다** — 확장이 계정 전환에서
 * `dashboardChartRangeDays`를 보존하는 것과 같은 판단이다.
 *
 * [KEY_MONITORING]이 여기 있는 이유: [replaceAll]이 이 키도 쓰긴 하지만 서버에는 이 컬럼이
 * 없어서([com.tubelimiter.app.sync.RemoteSettings.toSettings]가 로컬 값을 그대로 되돌려준다)
 * 계정에서 온 값이 아니다. 감시 대상 목록·차트 범위와 같은 부류다.
 */
internal val DEVICE_LOCAL_SETTINGS_KEYS: List<Preferences.Key<*>> = listOf(
    KEY_MONITORING,
    KEY_CHART_RANGE_DAYS,
    KEY_WATCH_MUSIC,
)

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

    /**
     * 감시 켜기/끄기. 서버 컬럼이 아니라 이 기기만의 값이라
     * [com.tubelimiter.app.sync.SyncRepository.saveSettings] 관문을 타지 않는다
     * ([DEVICE_LOCAL_SETTINGS_KEYS] 참고).
     */
    suspend fun setMonitoringEnabled(enabled: Boolean) = edit { it[KEY_MONITORING] = enabled }

    /**
     * 하드코어를 실제로 끈다. **유일한 호출자는 쿨다운이 끝났을 때의
     * [com.tubelimiter.app.service.UsageMonitorService]다** — 사용자가 직접 부르는 경로가
     * 아니므로 하드코어 관문([com.tubelimiter.app.limit.isHardcoreChangeAllowed])을 타지 않는다.
     * 해제는 1시간 쿨다운이라는 별개 관문이 이미 지키고 있다.
     */
    suspend fun setHardcoreMode(enabled: Boolean) = edit {
        it[KEY_HARDCORE] = enabled
        it.remove(KEY_HARDCORE_DISABLE_AT)
    }

    /** 대시보드 표시 설정. 동기화되지 않는 기기별 값이다. */
    suspend fun setChartRangeDays(days: Int) = edit { it[KEY_CHART_RANGE_DAYS] = days }

    /** 감시 대상에 YouTube Music을 넣을지. 기기별 값이라 동기화하지 않는다([Settings.watchYouTubeMusic]). */
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
        (SYNCED_SETTINGS_KEYS + DEVICE_LOCAL_SETTINGS_KEYS).forEach { prefs.remove(it) }
    }

    /**
     * 계정이 바뀌었을 때 쓰는 좁은 버전: **계정에서 온 설정만** 기본값으로 되돌리고 기기별
     * 값(감시 켜짐 여부, 차트 범위, 감시 대상)은 남긴다.
     *
     * [resetToDefaults]와 갈라놓은 이유는 계정 전환과 계정 삭제가 다른 사건이기 때문이다.
     * 삭제는 이 기기에서 그 계정의 흔적을 통째로 지우는 것이고, 전환은 "주인이 바뀌었다"일
     * 뿐이라 기기 자신의 취향까지 초기화할 이유가 없다(확장이 계정 전환에서
     * `dashboardChartRangeDays`를 남기는 것과 같은 판단).
     *
     * 여기서 `hardcore_mode`를 반드시 지워야 한다 — 서버에 settings 행이 없는 갓 가입 계정으로
     * 로그인했을 때 직전 계정의 잠금이 그대로 살아남는 경로가 이것이다
     * (documents/BACKEND.md "계정 전환" 7번). 지운 직후 서버 pull이 새 주인의 값을 채운다.
     */
    suspend fun resetSyncedToDefaults() = edit { prefs ->
        SYNCED_SETTINGS_KEYS.forEach { prefs.remove(it) }
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
