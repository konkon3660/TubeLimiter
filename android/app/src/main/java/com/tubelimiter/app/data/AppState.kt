package com.tubelimiter.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tubelimiter.app.diagnostics.DIAGNOSTIC_CAPACITY
import com.tubelimiter.app.diagnostics.DiagnosticEvent
import com.tubelimiter.app.diagnostics.appendDiagnosticEvent
import com.tubelimiter.app.diagnostics.decodeDiagnosticEvents
import com.tubelimiter.app.diagnostics.encodeDiagnosticEvents
import com.tubelimiter.app.gamification.StreakRecord
import com.tubelimiter.app.limit.AlarmState
import com.tubelimiter.app.limit.EmergencyResetFrequency
import com.tubelimiter.app.limit.LimitHistoryEntry
import com.tubelimiter.app.limit.planLimitHistoryUpdate
import com.tubelimiter.app.permission.AppPermission
import com.tubelimiter.app.sync.PendingSettings
import com.tubelimiter.app.sync.decodePendingSettings
import com.tubelimiter.app.sync.encodePendingSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val KEY_USAGE_HISTORY = stringPreferencesKey("usage_history")
private val KEY_USAGE_HISTORY_HOURLY = stringPreferencesKey("usage_history_hourly")

/** Per-day share of [KEY_USAGE_HISTORY] that was watched on an emergency pass, and how many
 * passes that day spent. Kept apart so the rollover can excuse emergency time from the streak
 * while still barring that day from the perfect-day count. */
private val KEY_EMERGENCY_MILLIS_HISTORY = stringPreferencesKey("emergency_history")
private val KEY_EMERGENCY_USES_HISTORY = stringPreferencesKey("emergency_uses_history")

/** 그날 실제로 적용됐던 한도(날짜 -> ms, 무제한은 [com.tubelimiter.app.limit.UNLIMITED_LIMIT_SENTINEL]).
 * 확장의 `limit_history`와 같은 키 이름·같은 규칙이다 — 자세한 이유는
 * [com.tubelimiter.app.limit.planLimitHistoryUpdate] 참고. */
private val KEY_LIMIT_HISTORY = stringPreferencesKey("limit_history")
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

/** [KEY_EMERGENCY_RESET_KEY]를 만들 때 적용됐던 리셋 주기. 키와 **한 쌍으로** 저장해야
 * "날짜가 흘러 버킷이 끝난 것"과 "주기가 바뀌어 키만 달라진 것"을 구별할 수 있다 — 안 그러면
 * 주기를 바꾸는 것만으로 그 자리에서 횟수가 리필된다
 * ([com.tubelimiter.app.limit.planEmergencyReset], QA_REVIEW §10.2). */
private val KEY_EMERGENCY_RESET_FREQUENCY = stringPreferencesKey("emergency_reset_bucket_frequency")
private val KEY_LAST_EMERGENCY_GRANTED_AT = longPreferencesKey("last_emergency_granted_at")

/** 이번 버킷에서 **다른 기기가** 쓴 긴급 시청 횟수와, 그 값이 속한 버킷 키. 버킷 키를 같이
 * 두지 않으면 리셋이 지나도 옛 제한이 남아 허용 횟수를 깎는다. */
private val KEY_EMERGENCY_USES_OTHER_DEVICES = intPreferencesKey("emergency_uses_other_devices")
private val KEY_EMERGENCY_USES_OTHER_DEVICES_KEY = stringPreferencesKey("emergency_uses_other_devices_key")

private val KEY_DAILY_USAGE_SYNC_DATE = stringPreferencesKey("daily_usage_sync_date")
private val KEY_DAILY_USAGE_SYNCED_MILLIS = longPreferencesKey("daily_usage_synced_millis")
private val KEY_DAILY_USAGE_COMBINED_MILLIS = longPreferencesKey("daily_usage_combined_millis")
private val KEY_DAILY_USAGE_EMERGENCY_SYNCED_MILLIS = longPreferencesKey("daily_usage_emergency_synced_millis")
private val KEY_DAILY_USAGE_EMERGENCY_USES_SYNCED = intPreferencesKey("daily_usage_emergency_uses_synced")

/** Whether a scheduled-block window was active the last time it was checked, so `tick()` can
 * detect the start/end transition (survives process death, unlike an in-memory flag). */
private val KEY_SCHEDULE_BLOCK_WAS_ACTIVE = booleanPreferencesKey("schedule_block_was_active")

/** Date the "10 minutes until a scheduled block starts" nudge was last sent, for once-per-day dedupe. */
private val KEY_SCHEDULE_START_NOTIFIED_DATE = stringPreferencesKey("schedule_start_notified_date")

/** 동기화/인증 실패 링버퍼와 마지막 성공 시각. 성공은 시각만 덮어쓰고 이벤트를 쌓지 않는다 —
 * 30초마다 성공하는 동기화를 전부 남기면 버퍼가 노이즈로 차서 정작 실패가 밀려난다.
 * 포맷은 [com.tubelimiter.app.diagnostics.encodeDiagnosticEvents] 참고. */
private val KEY_DIAGNOSTIC_EVENTS = stringPreferencesKey("diagnostic_events")
private val KEY_LAST_SYNC_SUCCESS_AT = longPreferencesKey("last_sync_success_at")

/** 아직 서버에 못 올린 설정 편집분. 포맷은
 * [com.tubelimiter.app.sync.encodePendingSettings] 참고. */
private val KEY_PENDING_SETTINGS = stringPreferencesKey("pending_settings_sync")

/** 대기분 중 **서버 기준 재검증에서 거부돼 버려진** 컬럼. 조용히 버리면 사용자는 저장된 줄
 * 알기 때문에, 설정 화면이 사유를 띄우고 사용자가 닫을 때까지 남는다
 * ([com.tubelimiter.app.sync.planPendingSettingsPush], QA_REVIEW §10.4). */
private val KEY_PENDING_SETTINGS_REJECTED = stringPreferencesKey("pending_settings_rejected")

/** 감시 서비스가 마지막으로 한 바퀴 돈 시각과, 그때 빠져 있던 권한
 * ([com.tubelimiter.app.diagnostics.monitorWarning]가 판정한다). */
private val KEY_MONITOR_HEARTBEAT_AT = longPreferencesKey("monitor_heartbeat_at")
private val KEY_MONITOR_MISSING_PERMISSIONS = stringPreferencesKey("monitor_missing_permissions")

/**
 * 이 기기의 로컬 데이터가 **어느 계정의 것인지** 적어두는 표식. 확장 `ACCOUNT_OWNER_KEY`
 * (`accountUserId`)와 같은 역할이고, 계정 전환 판정([planAccountSwitch])의 유일한 입력이다.
 *
 * 값은 마지막으로 로그인한 user_id이고 **로그아웃해도 남긴다** — 지우면 다음 로그인이 전부
 * "표식 없는 첫 로그인"으로 보여서 가드가 통째로 무력해진다.
 */
private val KEY_ACCOUNT_OWNER = stringPreferencesKey("account_user_id")

/** How many days of usage history to keep for the dashboard. */
const val HISTORY_RETENTION_DAYS = 60

/**
 * 계정에서 온 값이라 계정이 바뀌거나 사라지면 지우는 키. [AppState.clearAccountData]가 지우는
 * 것이 정확히 이 목록이고, 목록 자체가 계약이라(documents/BACKEND.md "계정 전환 — 무엇을
 * 지우고 무엇을 남기나") 이름만 뽑아 [ACCOUNT_SWITCH_REMOVED_KEYS]로 테스트에 고정한다.
 *
 * **통째 삭제(`prefs.clear()`)를 쓰면 안 된다** — 이 DataStore에는 계정에서 온 값과 "지금 이
 * 기기를 막고 있는 상태"가 섞여 있어서, 통째로 비우면 계정 전환이 곧 차단 해제 수단이 된다
 * ([ACCOUNT_PRESERVED_STATE_KEYS] 참고).
 */
internal val ACCOUNT_DATA_KEYS: List<Preferences.Key<*>> = listOf(
    // 대시보드가 그리는 기록 + 그날 판정에 쓰인 한도 스냅샷.
    // 한도만 남기면 다음 계정의 새 기록이 지운 계정의 한도로 판정된다.
    KEY_USAGE_HISTORY,
    KEY_USAGE_HISTORY_HOURLY,
    KEY_EMERGENCY_MILLIS_HISTORY,
    KEY_EMERGENCY_USES_HISTORY,
    KEY_LIMIT_HISTORY,
    // 롤오버 기준일. 남겨두면 다음 계정의 첫 롤오버가 이 계정의 마지막 날부터 정산을 시작한다.
    KEY_LAST_ROLLOVER_DATE,

    // 스트릭·업적은 서버의 `streaks`/`achievements`에서 받아온 이 계정의 성적표다.
    KEY_STREAK_CURRENT,
    KEY_STREAK_BEST,
    KEY_STREAK_LAST_DATE,
    KEY_STREAK_TOTAL_SUCCESS,
    KEY_STREAK_XP,
    KEY_STREAK_PERFECT_DAYS,
    KEY_STREAK_PERFECT_CURRENT,
    KEY_STREAK_PERFECT_BEST,
    KEY_ACHIEVEMENTS,

    // daily_usage 동기화 마커 — "이 기기가 이미 보고한 몫"이라 계정이 바뀌면 무의미하다.
    // 남겨두면 다음 계정의 첫 델타 기준선이 되어 그날 사용량이 조용히 안 올라간다.
    KEY_DAILY_USAGE_SYNC_DATE,
    KEY_DAILY_USAGE_SYNCED_MILLIS,
    KEY_DAILY_USAGE_COMBINED_MILLIS,
    KEY_DAILY_USAGE_EMERGENCY_SYNCED_MILLIS,
    KEY_DAILY_USAGE_EMERGENCY_USES_SYNCED,

    // "이 계정의 다른 기기가 이번 버킷에서 쓴 횟수" 캐시. 주인이 바뀌면 남의 숫자라 지운다
    // (로그아웃에서는 일부러 남긴다 — 아래 clearAccountData 주석 참고).
    KEY_EMERGENCY_USES_OTHER_DEVICES,
    KEY_EMERGENCY_USES_OTHER_DEVICES_KEY,

    // 진단 기록도 이 계정과의 통신 기록이다. 남겨두면 이미 없는 계정의 실패 목록이 계속 보이고,
    // "마지막 성공" 시각도 다음 계정의 24시간 판정에 그대로 끼어든다.
    KEY_DIAGNOSTIC_EVENTS,
    KEY_LAST_SYNC_SUCCESS_AT,

    // 아직 못 올린 오프라인 편집분. 올릴 계정이 바뀌었으니 같이 버린다 — A의 오프라인 편집이
    // B의 계정으로 올라가면 안 된다([com.tubelimiter.app.sync.readPendingFor]가 user_id로 한 번
    // 더 거르긴 하지만, 그건 보험이지 보관 이유가 아니다).
    KEY_PENDING_SETTINGS,
    KEY_PENDING_SETTINGS_REJECTED,

    // 주인 표식. 계정 삭제에서는 값 자체가 사라진 계정의 user_id라 남겨둘 이유가 없고,
    // 계정 전환에서는 곧바로 새 주인으로 덮어쓴다.
    KEY_ACCOUNT_OWNER,
)

/**
 * 계정이 바뀌어도 **일부러 남기는** 키. 전부 계정이 아니라 이 기기에서 온 값이다: 지금 걸려
 * 있는 차단, 진행 중이거나 예약된 집중 세션, 이 기기의 남은 긴급 시청 횟수와 쿨다운, 알람
 * 장부, 예약 차단 마커, 감시 심박. 이걸 지우면 **"다른 계정으로 로그인"이 지금 나를 막고 있는
 * 차단에서 빠져나가는 길**이 된다.
 *
 * 실제로 지울 때 쓰이지는 않고, 분류를 못 박아 테스트가 "지우는 목록과 겹치지 않는다"를
 * 검증하는 데 쓴다. 확장 `ACCOUNT_SWITCH_PRESERVED_KEYS`와 같은 역할.
 */
internal val ACCOUNT_PRESERVED_STATE_KEYS: List<Preferences.Key<*>> = listOf(
    // 지금 걸려 있는 차단 상태
    KEY_MANUAL_BLOCK,

    // 집중 모드(진행 중 · 지연 시작 대기 · 종료 요청)
    KEY_FOCUS_END,
    KEY_FOCUS_DELAY_END,
    KEY_FOCUS_DELAY_DURATION,
    KEY_FOCUS_STOP_REQUESTED_AT,

    // 긴급 시청: 진행 중인 창, 이 기기의 남은 횟수 카운트다운과 그 버킷 키, 마지막 부여 시각.
    // 남은 횟수를 지우면 "다른 계정으로 로그인 = 횟수 리필"이 된다.
    KEY_EMERGENCY_END,
    KEY_EMERGENCY_REMAINING,
    KEY_EMERGENCY_RESET_KEY,
    KEY_EMERGENCY_RESET_FREQUENCY,
    KEY_LAST_EMERGENCY_GRANTED_AT,

    // 오늘 어떤 알림을 이미 띄웠는지 · 예약 차단 창 진입 여부
    KEY_ALARM_DATE,
    KEY_ALARM_LAST_INTERVAL,
    KEY_ALARM_MILESTONES_DONE,
    KEY_SCHEDULE_BLOCK_WAS_ACTIVE,
    KEY_SCHEDULE_START_NOTIFIED_DATE,

    // 감시 서비스의 심박. 계정이 아니라 이 기기의 감시가 언제 돌았는지이고, 지우면 그 순간
    // 경고 판정의 기준점이 사라진다.
    KEY_MONITOR_HEARTBEAT_AT,
    KEY_MONITOR_MISSING_PERMISSIONS,
)

data class RuntimeState(
    val usageHistory: Map<String, Long> = emptyMap(),
    /** Same days as [usageHistory], broken down by wall-clock hour (0-23); feeds the dashboard's
     * time-of-day pattern chart. Days recorded before this field existed are simply absent. */
    val usageHistoryHourly: Map<String, Map<Int, Long>> = emptyMap(),
    /** Per-day emergency-pass time and use count, keyed like [usageHistory]. */
    val emergencyMillisHistory: Map<String, Long> = emptyMap(),
    val emergencyUseHistory: Map<String, Long> = emptyMap(),
    /** 그날 적용됐던 한도의 스냅샷. 비어 있는 날은 대시보드가 현재 설정으로 추정한다
     * ([com.tubelimiter.app.limit.resolveLimitForDate]). */
    val limitHistory: Map<String, Long> = emptyMap(),
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
    /** [emergencyResetKey]를 만든 주기. null이면 이 표식이 생기기 전 저장소다. */
    val emergencyResetFrequency: EmergencyResetFrequency? = null,
    /** When the last emergency pass was granted, for the extra 15s anti-mash cooldown. */
    val lastEmergencyGrantedAtMillis: Long? = null,
    /** 다른 기기가 이번 버킷에 쓴 긴급 시청 횟수 (마지막 동기화 성공 시점 기준)와 그 버킷 키. */
    val emergencyUsesOtherDevices: Int = 0,
    val emergencyUsesOtherDevicesKey: String? = null,
    /** Date this device last told the server about its usage, and how much it reported. */
    val dailyUsageSyncDate: String? = null,
    val dailyUsageSyncedMillis: Long = 0L,
    /** Latest known cross-device total for [dailyUsageSyncDate] (includes this device's own contribution). */
    val dailyUsageCombinedMillis: Long = 0L,
    /** How much emergency-pass time this device already reported for [dailyUsageSyncDate]. */
    val dailyUsageEmergencySyncedMillis: Long = 0L,
    /** 같은 날짜에 대해 이 기기가 이미 보고한 긴급 시청 **횟수**. */
    val dailyUsageEmergencyUsesSynced: Int = 0,
    val scheduleBlockWasActive: Boolean = false,
    val scheduleStartNotifiedDate: String? = null,
    /** 최근 동기화·인증 실패, 최신순. 민감정보는 담기지 않는다(diagnostics/SyncDiagnostics.kt 참고). */
    val diagnosticEvents: List<DiagnosticEvent> = emptyList(),
    /** 마지막으로 서버 왕복이 성공한 시각. 한 번도 없으면 null. */
    val lastSyncSuccessAtMillis: Long? = null,
    /** 감시 서비스가 마지막으로 살아 있던 시각. 한 번도 안 돌았으면 null. */
    val monitorHeartbeatAtMillis: Long? = null,
    /**
     * 서비스가 마지막 틱에서 확인한, **빠져 있던** 권한들.
     *
     * 화면이 직접 [com.tubelimiter.app.permission.PermissionChecker]를 부르지 않고 이 값을 쓰는
     * 이유: 앱이 떠 있는 동안 다른 화면에서 권한이 꺼지면 액티비티의 스냅샷은 `ON_RESUME`까지
     * 낡은 채로 있지만, 서비스는 몇 초 안에 알아챈다. 사용자에게 "지금 안 막히고 있다"를
     * 알려야 하는 쪽은 빠른 쪽이다.
     */
    val monitorMissingPermissions: List<AppPermission> = emptyList(),
    /** 이 기기의 로컬 데이터 주인(user_id). 한 번도 로그인한 적이 없으면 null — [KEY_ACCOUNT_OWNER] 참고. */
    val accountOwnerUserId: String? = null,
    /** 아직 서버에 못 올린 설정 편집분. 없으면 null. */
    val pendingSettings: PendingSettings? = null,
    /** 재검증에서 거부돼 버려진 대기분 컬럼. 비어 있으면 화면에 아무것도 안 뜬다. */
    val pendingSettingsRejected: Set<String> = emptySet(),
) {
    fun focusActiveAt(nowMillis: Long): Boolean =
        focusEndMillis != null && nowMillis < focusEndMillis

    fun emergencyActiveAt(nowMillis: Long): Boolean =
        emergencyEndMillis != null && nowMillis < emergencyEndMillis

    fun emergencyMillisOn(dateKey: String): Long = emergencyMillisHistory[dateKey] ?: 0L

    fun emergencyUsesOn(dateKey: String): Int = (emergencyUseHistory[dateKey] ?: 0L).toInt()

    /**
     * 지금 유효한 "다른 기기 몫". 저장된 버킷 키가 현재 허용 횟수 버킷([emergencyResetKey])과
     * 어긋나면 — 리셋이 지났거나 리셋 주기 설정이 바뀐 경우 — 0으로 본다.
     */
    fun cachedOtherDeviceEmergencyUses(): Int =
        if (emergencyUsesOtherDevicesKey != null && emergencyUsesOtherDevicesKey == emergencyResetKey) {
            emergencyUsesOtherDevices
        } else {
            0
        }

    /** 날짜별 로컬 긴급 시청 횟수 — 버킷 합계를 낼 때 [com.tubelimiter.app.sync.combinedEmergencyUses]에 넘긴다. */
    fun emergencyUsesByDate(): Map<String, Int> = emergencyUseHistory.mapValues { it.value.toInt() }
}

class AppState(private val context: Context) {

    val state: Flow<RuntimeState> = context.dataStore.data.map { it.toRuntimeState() }

    private fun Preferences.toRuntimeState() = RuntimeState(
        usageHistory = decodeLongMap(this[KEY_USAGE_HISTORY]),
        usageHistoryHourly = decodeHourlyMap(this[KEY_USAGE_HISTORY_HOURLY]),
        emergencyMillisHistory = decodeLongMap(this[KEY_EMERGENCY_MILLIS_HISTORY]),
        emergencyUseHistory = decodeLongMap(this[KEY_EMERGENCY_USES_HISTORY]),
        limitHistory = decodeLongMap(this[KEY_LIMIT_HISTORY]),
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
        emergencyResetFrequency = this[KEY_EMERGENCY_RESET_FREQUENCY]?.let { name ->
            EmergencyResetFrequency.entries.firstOrNull { it.name == name }
        },
        lastEmergencyGrantedAtMillis = this[KEY_LAST_EMERGENCY_GRANTED_AT],
        emergencyUsesOtherDevices = this[KEY_EMERGENCY_USES_OTHER_DEVICES] ?: 0,
        emergencyUsesOtherDevicesKey = this[KEY_EMERGENCY_USES_OTHER_DEVICES_KEY],
        dailyUsageSyncDate = this[KEY_DAILY_USAGE_SYNC_DATE],
        dailyUsageSyncedMillis = this[KEY_DAILY_USAGE_SYNCED_MILLIS] ?: 0L,
        dailyUsageCombinedMillis = this[KEY_DAILY_USAGE_COMBINED_MILLIS] ?: 0L,
        dailyUsageEmergencySyncedMillis = this[KEY_DAILY_USAGE_EMERGENCY_SYNCED_MILLIS] ?: 0L,
        dailyUsageEmergencyUsesSynced = this[KEY_DAILY_USAGE_EMERGENCY_USES_SYNCED] ?: 0,
        scheduleBlockWasActive = this[KEY_SCHEDULE_BLOCK_WAS_ACTIVE] ?: false,
        scheduleStartNotifiedDate = this[KEY_SCHEDULE_START_NOTIFIED_DATE],
        diagnosticEvents = decodeDiagnosticEvents(this[KEY_DIAGNOSTIC_EVENTS]),
        lastSyncSuccessAtMillis = this[KEY_LAST_SYNC_SUCCESS_AT],
        monitorHeartbeatAtMillis = this[KEY_MONITOR_HEARTBEAT_AT],
        // 이 빌드가 모르는 이름은 버린다(예전 버전이 남긴 값이 열거형 파싱에서 앱을 세우면 안 된다).
        // 저장이 Set이라 순서를 잃으므로 열거형 선언 순서로 되돌린다 — 화면 문구가 실행할 때마다
        // 순서를 바꾸면 안 된다.
        monitorMissingPermissions = decodeStringSet(this[KEY_MONITOR_MISSING_PERMISSIONS])
            .let { names -> AppPermission.entries.filter { it.name in names } },
        accountOwnerUserId = this[KEY_ACCOUNT_OWNER],
        pendingSettings = decodePendingSettings(this[KEY_PENDING_SETTINGS]),
        pendingSettingsRejected = decodeStringSet(this[KEY_PENDING_SETTINGS_REJECTED]),
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

    /**
     * 그날 적용된 한도를 스냅샷으로 남긴다. 계획은 순수 함수
     * ([planLimitHistoryUpdate])가 세우고 여기서는 읽고 쓰기만 한다.
     *
     * [keepKeys]에 기록하려는 날짜를 더해서 넘기는 건 [recordUsage]와 같은 규칙이다 — 두 맵이
     * 같은 보관 기간으로 같이 잘려야 히트맵에 그려지는 날만 추정치로 떨어지는 일이 없다.
     *
     * `changed`가 false면 키를 건드리지 않는다. 이 함수는 매 틱마다 불린다.
     */
    suspend fun recordLimitHistory(updates: List<LimitHistoryEntry>, keepKeys: Set<String>) = edit { prefs ->
        val plan = planLimitHistoryUpdate(
            history = decodeLongMap(prefs[KEY_LIMIT_HISTORY]),
            updates = updates,
            keepKeys = keepKeys + updates.map { it.date },
        )
        if (plan.changed) prefs[KEY_LIMIT_HISTORY] = encodeLongMap(plan.history)
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
     *
     * 판정 기준은 로컬 카운트다운이 아니라 **계정 합계**다: 다른 기기가 이번 버킷에 쓴 몫도
     * 같은 edit 안에서 읽어 빼므로, PC에서 3회를 다 쓴 뒤 폰에서 다시 3회를 쓰는 우회가 막힌다.
     * 다른 기기 몫은 마지막 동기화 성공 때 저장된 값이라, 오프라인/로그아웃이면 0 → 기존
     * 로컬 전용 동작 그대로다.
     */
    suspend fun consumeEmergency(nowMillis: Long, endMillis: Long): Boolean {
        var granted = false
        context.dataStore.edit { prefs ->
            val remaining = prefs[KEY_EMERGENCY_REMAINING] ?: 0
            // 저장된 버킷 키가 지금 허용 횟수 버킷과 다르면(리셋 이후) 그 값은 이미 지난 버킷 것.
            val otherDevices = if (prefs[KEY_EMERGENCY_USES_OTHER_DEVICES_KEY] == prefs[KEY_EMERGENCY_RESET_KEY]) {
                prefs[KEY_EMERGENCY_USES_OTHER_DEVICES] ?: 0
            } else {
                0
            }
            if (remaining - otherDevices <= 0) return@edit
            prefs[KEY_EMERGENCY_REMAINING] = remaining - 1
            prefs[KEY_EMERGENCY_END] = endMillis
            prefs[KEY_LAST_EMERGENCY_GRANTED_AT] = nowMillis
            granted = true
        }
        return granted
    }

    suspend fun clearEmergency() = edit { it.remove(KEY_EMERGENCY_END) }

    /** 새 버킷이 시작돼 허용 횟수를 다시 채운다. 표식은 키와 주기가 **한 쌍**이다. */
    suspend fun resetEmergencyAllowance(
        resetKey: String,
        frequency: EmergencyResetFrequency,
        allowance: Int,
    ) = edit { prefs ->
        prefs[KEY_EMERGENCY_RESET_KEY] = resetKey
        prefs[KEY_EMERGENCY_RESET_FREQUENCY] = frequency.name
        prefs[KEY_EMERGENCY_REMAINING] = allowance
    }

    /**
     * 리셋 주기가 바뀌어 버킷 키만 달라졌을 때. **남은 횟수는 건드리지 않는다** — 이미 쓴
     * 횟수를 새 버킷이 그대로 이어받아야 "주기를 조이는 것만으로 무료 리필"이 막힌다
     * ([com.tubelimiter.app.limit.EmergencyResetAction.CARRY_OVER], QA_REVIEW §10.2).
     */
    suspend fun adoptEmergencyBucket(
        resetKey: String,
        frequency: EmergencyResetFrequency,
    ) = edit { prefs ->
        prefs[KEY_EMERGENCY_RESET_KEY] = resetKey
        prefs[KEY_EMERGENCY_RESET_FREQUENCY] = frequency.name
    }

    /** Records that [dateKey]'s usage was reported up to [syncedMillis], and the server's combined total. */
    suspend fun recordDailyUsageSync(
        dateKey: String,
        syncedMillis: Long,
        combinedMillis: Long,
        emergencySyncedMillis: Long = 0L,
        emergencyUsesSynced: Int = 0,
    ) = edit { prefs ->
        prefs[KEY_DAILY_USAGE_SYNC_DATE] = dateKey
        prefs[KEY_DAILY_USAGE_SYNCED_MILLIS] = syncedMillis
        prefs[KEY_DAILY_USAGE_COMBINED_MILLIS] = combinedMillis
        prefs[KEY_DAILY_USAGE_EMERGENCY_SYNCED_MILLIS] = emergencySyncedMillis
        prefs[KEY_DAILY_USAGE_EMERGENCY_USES_SYNCED] = emergencyUsesSynced
    }

    /**
     * 다른 기기가 [resetKey] 버킷에서 쓴 긴급 시청 횟수를 캐시한다. 동기화가 성공했을 때만
     * 갱신되고, [consumeEmergency]와 화면 표시가 이 값을 뺀 잔여 횟수를 쓴다.
     */
    suspend fun recordEmergencyUsesFromOtherDevices(resetKey: String, uses: Int) = edit { prefs ->
        prefs[KEY_EMERGENCY_USES_OTHER_DEVICES_KEY] = resetKey
        prefs[KEY_EMERGENCY_USES_OTHER_DEVICES] = uses.coerceAtLeast(0)
    }

    /**
     * 서버 왕복이 성공했을 때. **이벤트를 쌓지 않고 시각만 덮어쓴다** — 성공은 세는 게 아니라
     * "마지막이 언제였나"만 알면 되고, 30초마다 한 줄씩 남기면 링버퍼가 성공 기록으로만 차서
     * 정작 봐야 할 실패가 밀려난다.
     */
    suspend fun recordSyncSuccess(atMillis: Long) = edit { it[KEY_LAST_SYNC_SUCCESS_AT] = atMillis }

    /**
     * 실패 한 건을 링버퍼에 넣는다. 읽기-수정-쓰기를 한 edit 안에서 처리해, 여러 동기화 경로가
     * 동시에 실패해도 기록이 서로를 덮어쓰지 않는다.
     *
     * [code]에는 서버 에러 메시지 원문이 아니라 짧은 분류만 넘길 것
     * ([com.tubelimiter.app.diagnostics.summarizeFailure] 참고) — 토큰·이메일·user_id가
     * 이 버퍼에 들어가면 사용자가 그대로 복사해 남에게 보내게 된다.
     */
    suspend fun recordDiagnosticFailure(atMillis: Long, kind: String, code: String) = edit { prefs ->
        val updated = appendDiagnosticEvent(
            events = decodeDiagnosticEvents(prefs[KEY_DIAGNOSTIC_EVENTS]),
            event = DiagnosticEvent(atMillis = atMillis, kind = kind, code = code),
            capacity = DIAGNOSTIC_CAPACITY,
        )
        prefs[KEY_DIAGNOSTIC_EVENTS] = encodeDiagnosticEvents(updated)
    }

    /**
     * 감시 서비스의 "나 살아 있다" 한 줄. 매 틱마다 쓰지 않고 호출자가 간격을 두는 이유는
     * DataStore 쓰기가 파일 전체를 다시 쓰기 때문이다 — 유튜브가 떠 있으면 틱은 5초마다 돈다
     * ([com.tubelimiter.app.service.UsageMonitorService] 참고).
     *
     * 진단 기록과 달리 계정 삭제·로그아웃에서 지우지 않는다. 이건 계정에서 온 값이 아니라
     * 이 기기의 감시가 언제 돌았는지이고, 지우면 그 순간 경고 판정의 기준점이 사라진다.
     */
    suspend fun recordMonitorHeartbeat(atMillis: Long, missingPermissions: List<AppPermission>) =
        edit { prefs ->
            prefs[KEY_MONITOR_HEARTBEAT_AT] = atMillis
            if (missingPermissions.isEmpty()) {
                prefs.remove(KEY_MONITOR_MISSING_PERMISSIONS)
            } else {
                prefs[KEY_MONITOR_MISSING_PERMISSIONS] =
                    encodeStringSet(missingPermissions.map { it.name }.toSet())
            }
        }

    suspend fun clearDiagnostics() = edit { prefs ->
        prefs.remove(KEY_DIAGNOSTIC_EVENTS)
        prefs.remove(KEY_LAST_SYNC_SUCCESS_AT)
    }

    suspend fun setScheduleBlockWasActive(active: Boolean) = edit { it[KEY_SCHEDULE_BLOCK_WAS_ACTIVE] = active }

    suspend fun setScheduleStartNotifiedDate(dateKey: String) = edit { it[KEY_SCHEDULE_START_NOTIFIED_DATE] = dateKey }

    /**
     * 이 기기의 로컬 데이터 주인을 적어둔다. 계정 전환 판정의 기준점이라 로그인이 확인된
     * 직후에만 쓴다([com.tubelimiter.app.sync.SyncRepository.pullSettings] 참고).
     */
    suspend fun setAccountOwner(userId: String) = edit { it[KEY_ACCOUNT_OWNER] = userId }

    /** 오프라인 편집 대기분을 통째로 갈아끼운다. null이면 지운다(= 서버에 다 올렸다). */
    suspend fun savePendingSettings(pending: PendingSettings?) = edit { prefs ->
        val encoded = encodePendingSettings(pending)
        if (encoded == null) prefs.remove(KEY_PENDING_SETTINGS) else prefs[KEY_PENDING_SETTINGS] = encoded
    }

    /**
     * 재검증에서 버려진 대기분 컬럼을 남긴다(빈 집합이면 지운다 = 사용자가 확인했다).
     * 쌓지 않고 덮어쓴다 — 중요한 건 "지금 무엇이 반영되지 않았나"이고, 목록이 길어질수록
     * 화면에서 읽히지 않는다.
     */
    suspend fun savePendingSettingsRejected(columns: Set<String>) = edit { prefs ->
        if (columns.isEmpty()) {
            prefs.remove(KEY_PENDING_SETTINGS_REJECTED)
        } else {
            prefs[KEY_PENDING_SETTINGS_REJECTED] = encodeStringSet(columns)
        }
    }

    /**
     * Wipes everything the deleted account contributed: the usage history the dashboard draws,
     * the streak/XP record and unlocked achievements (both pulled from `streaks`/`achievements`
     * by [com.tubelimiter.app.sync.SyncRepository.pullStreak]), and the `daily_usage` sync
     * markers — leaving those would have the next account inherit this one's reported totals.
     *
     * Deliberately leaves the live protection state alone (manual block, a running or scheduled
     * focus session, the emergency allowance and its cooldown, alarm bookkeeping, schedule-window
     * markers). None of it came from the account, and clearing it would turn "delete my account"
     * into a way out of a focus session that is currently blocking.
     *
     * **계정 전환도 같은 이 함수를 쓴다**([planAccountSwitch]). 확장은 전환 목록에서 주인
     * 표식만 빼두지만(곧바로 새 주인으로 덮어쓰므로), 여기서는 지운 뒤 새 주인을 쓰는 순서라
     * 결과가 같다. 두 경우 모두 "이 기기에 남은 값이 더 이상 이 계정의 것이 아니다"라는 같은
     * 사실을 다룬다.
     *
     * 지우는 것과 남기는 것의 목록은 [ACCOUNT_DATA_KEYS] / [ACCOUNT_PRESERVED_STATE_KEYS]에
     * 있고, 그쪽이 계약이다 — 여기서는 목록을 훑기만 한다.
     */
    suspend fun clearAccountData() = edit { prefs ->
        ACCOUNT_DATA_KEYS.forEach { prefs.remove(it) }
    }

    private suspend fun edit(block: (MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
