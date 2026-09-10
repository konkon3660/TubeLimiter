package com.tubelimiter.app.sync

import com.tubelimiter.app.data.Settings
import com.tubelimiter.app.gamification.StreakRecord
import com.tubelimiter.app.limit.DEFAULT_LIMIT_MINUTES
import com.tubelimiter.app.limit.EmergencyResetFrequency
import com.tubelimiter.app.limit.LimitConfig
import com.tubelimiter.app.limit.LimitFrequency
import com.tubelimiter.app.limit.ScheduleWindow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Mirrors the columns of `settings` that this client owns. `always_block_shorts` and
 * `whitelist` are deliberately absent: they are browser-only concepts, and leaving them
 * out of both the read and the write keeps the extension's values intact.
 */
@Serializable
data class RemoteSettings(
    @SerialName("daily_limit_ms") val dailyLimitMs: Long? = null,
    @SerialName("daily_limit_by_day") val dailyLimitByDay: JsonObject? = null,
    @SerialName("daily_limit_reset_frequency") val resetFrequency: String? = null,
    @SerialName("emergency_config") val emergencyConfig: JsonObject? = null,
    @SerialName("alarm_interval_minutes") val alarmIntervalMinutes: Int? = null,
    @SerialName("alarm_milestones_enabled") val alarmMilestonesEnabled: Boolean? = null,
    @SerialName("hardcore_mode") val hardcoreMode: Boolean? = null,
    @SerialName("hardcore_disable_requested_at") val hardcoreDisableRequestedAt: String? = null,
    @SerialName("scheduled_blocks") val scheduledBlocks: JsonArray? = null,
)

@Serializable
data class RemoteStreak(
    @SerialName("current_streak") val currentStreak: Int = 0,
    @SerialName("best_streak") val bestStreak: Int = 0,
    @SerialName("last_result_date") val lastResultDate: String? = null,
    @SerialName("total_success_days") val totalSuccessDays: Int = 0,
    @SerialName("xp") val xp: Int = 0,
    @SerialName("perfect_days") val perfectDays: Int = 0,
    @SerialName("current_perfect_streak") val currentPerfectStreak: Int = 0,
    @SerialName("best_perfect_streak") val bestPerfectStreak: Int = 0,
)

@Serializable
data class RemoteAchievement(
    @SerialName("key") val key: String,
)

/** Result of `increment_daily_usage` — the row's new totals after this device's delta was added. */
@Serializable
data class RemoteDailyUsageTotal(
    @SerialName("usage_ms") val usageMs: Long = 0L,
    @SerialName("shorts_ms") val shortsMs: Long = 0L,
    @SerialName("emergency_ms") val emergencyMs: Long = 0L,
    /** 그날 긴급 시청을 쓴 횟수의 계정 전체 합계. 시간과 달리 예전에는 서버로 올라가지 않아
     * 기기를 바꾸면 허용 횟수가 되살아났다(우회 구멍). */
    @SerialName("emergency_uses") val emergencyUses: Int = 0,
)

/**
 * `daily_usage`에서 날짜별 긴급 시청 횟수만 읽어오는 행. 주간/월간 리셋일 때 버킷 합계를
 * 내려면 RPC가 돌려주는 오늘 행 하나로는 부족해서 범위를 통째로 select 한다.
 */
@Serializable
data class RemoteEmergencyUsesRow(
    @SerialName("date") val date: String,
    @SerialName("emergency_uses") val emergencyUses: Int = 0,
)

/**
 * 대시보드가 히트맵/차트를 그릴 때 읽는 `daily_usage` 행. [RemoteEmergencyUsesRow]보다 넓게
 * 읽는 이유는 목적이 달라서다 — 저쪽은 잔여 횟수 게이트용이고, 이쪽은 "재설치했거나 다른
 * 기기에서만 본 날"을 화면에 되살리기 위한 것이다.
 *
 * `shorts_ms`는 뽑지 않는다. 안드로이드는 Shorts를 기록하지도 그리지도 않아 응답만 커진다.
 */
@Serializable
data class RemoteDailyUsageRow(
    @SerialName("date") val date: String,
    @SerialName("usage_ms") val usageMs: Long = 0L,
    @SerialName("emergency_ms") val emergencyMs: Long = 0L,
    @SerialName("emergency_uses") val emergencyUses: Int = 0,
)

val EMERGENCY_USES_COLUMNS = listOf("date", "emergency_uses")

val DAILY_USAGE_HISTORY_COLUMNS = listOf("date", "usage_ms", "emergency_ms", "emergency_uses")

/**
 * 이 클라이언트가 소유한 `settings` 컬럼 이름. 오프라인 대기분([PendingSettings])과
 * 하드코어 거부 사유([com.tubelimiter.app.limit.HardcoreViolation.column])가 같은 어휘를 쓰도록
 * 상수로 뽑아둔다 — 세 곳이 문자열을 따로 적으면 오타 하나가 조용히 컬럼 하나를 빠뜨린다.
 */
object SettingsColumn {
    const val DAILY_LIMIT_MS = "daily_limit_ms"
    const val DAILY_LIMIT_BY_DAY = "daily_limit_by_day"
    const val DAILY_LIMIT_RESET_FREQUENCY = "daily_limit_reset_frequency"
    const val EMERGENCY_CONFIG = "emergency_config"
    const val ALARM_INTERVAL_MINUTES = "alarm_interval_minutes"
    const val ALARM_MILESTONES_ENABLED = "alarm_milestones_enabled"
    const val HARDCORE_MODE = "hardcore_mode"
    const val HARDCORE_DISABLE_REQUESTED_AT = "hardcore_disable_requested_at"
    const val SCHEDULED_BLOCKS = "scheduled_blocks"
}

val SETTINGS_COLUMNS = listOf(
    SettingsColumn.DAILY_LIMIT_MS,
    SettingsColumn.DAILY_LIMIT_BY_DAY,
    SettingsColumn.DAILY_LIMIT_RESET_FREQUENCY,
    SettingsColumn.EMERGENCY_CONFIG,
    SettingsColumn.ALARM_INTERVAL_MINUTES,
    SettingsColumn.ALARM_MILESTONES_ENABLED,
    SettingsColumn.HARDCORE_MODE,
    SettingsColumn.HARDCORE_DISABLE_REQUESTED_AT,
    SettingsColumn.SCHEDULED_BLOCKS,
)

val STREAK_COLUMNS = listOf(
    "current_streak",
    "best_streak",
    "last_result_date",
    "total_success_days",
    "xp",
    "perfect_days",
    "current_perfect_streak",
    "best_perfect_streak",
)

// --- settings ---

/** [local] supplies anything the server does not carry, such as the monitoring toggle. */
fun RemoteSettings.toSettings(local: Settings): Settings = Settings(
    limit = LimitConfig(
        dailyLimitMinutes = dailyLimitMs?.let { (it / 60_000L).toInt() } ?: local.limit.dailyLimitMinutes,
        byDayMinutes = decodeByDay(dailyLimitByDay, local.limit.byDayMinutes),
        frequency = decodeFrequency(resetFrequency, local.limit.frequency),
    ),
    monitoringEnabled = local.monitoringEnabled,
    emergencyAllowance = emergencyConfig?.get("dailyUses")?.jsonPrimitive?.intOrNull
        ?: local.emergencyAllowance,
    emergencyResetFrequency = decodeEmergencyReset(emergencyConfig, local.emergencyResetFrequency),
    alarmIntervalMinutes = alarmIntervalMinutes ?: local.alarmIntervalMinutes,
    alarmMilestonesEnabled = alarmMilestonesEnabled ?: local.alarmMilestonesEnabled,
    hardcoreMode = hardcoreMode ?: local.hardcoreMode,
    // A pending disable request is made locally and only then pushed, so if that push failed
    // (offline) the column is still empty on the server. Falling back to the local value keeps
    // the request alive instead of silently restarting its 1-hour cooldown from scratch.
    hardcoreDisableRequestedAt = parseTimestampMillis(hardcoreDisableRequestedAt)
        ?: local.hardcoreDisableRequestedAt,
    chartRangeDays = local.chartRangeDays,
    // Unlike daily_limit_by_day's empty-object case, an empty array here is a real, meaningful
    // state (no schedule windows configured) rather than something to fall back away from -
    // only a genuinely absent column (null) keeps the local value.
    scheduleWindows = scheduledBlocks?.let { decodeScheduleWindowsJson(it) } ?: local.scheduleWindows,
)

/**
 * Only the owned columns go on the wire. PostgREST's upsert updates exactly the keys it
 * receives, so the extension's `whitelist` and `always_block_shorts` survive untouched.
 *
 * [columns]를 주면 그 컬럼만 담는다(`user_id`는 항상 남는다 — 없으면 어느 행인지 모른다).
 * 오프라인 대기분을 올릴 때 쓰는 길인데, 이유는 같은 성질이다: **보낸 키만 갱신된다.**
 * 오프라인이던 사이 다른 기기가 바꾼 항목까지 옛 값으로 되돌리지 않으려면 못 올린 컬럼만
 * 보내야 한다(documents/BACKEND.md "오프라인 편집 대기분" 5번).
 *
 * 전체를 담고 나서 거르는 건 일부러다 — 조립과 필터가 갈라져 있으면 컬럼이 하나 늘었을 때
 * 한쪽만 고쳐 조용히 빠지는 일이 생긴다.
 */
fun Settings.toRemoteJson(userId: String, columns: Set<String>? = null): JsonObject {
    val full = buildJsonObject {
        put("user_id", userId)
        put(SettingsColumn.DAILY_LIMIT_MS, limit.dailyLimitMinutes * 60_000L)
        put(
            SettingsColumn.DAILY_LIMIT_BY_DAY,
            buildJsonObject {
                limit.byDayMinutes.forEachIndexed { index, minutes -> put(index.toString(), minutes) }
            },
        )
        put(SettingsColumn.DAILY_LIMIT_RESET_FREQUENCY, encodeFrequency(limit.frequency))
        put(
            SettingsColumn.EMERGENCY_CONFIG,
            buildJsonObject {
                put("dailyUses", emergencyAllowance)
                put("resetFrequency", emergencyResetFrequency.name.lowercase())
            },
        )
        put(SettingsColumn.ALARM_INTERVAL_MINUTES, alarmIntervalMinutes)
        put(SettingsColumn.ALARM_MILESTONES_ENABLED, alarmMilestonesEnabled)
        put(SettingsColumn.HARDCORE_MODE, hardcoreMode)
        putNullable(
            SettingsColumn.HARDCORE_DISABLE_REQUESTED_AT,
            formatTimestampMillis(hardcoreDisableRequestedAt),
        )
        put(SettingsColumn.SCHEDULED_BLOCKS, encodeScheduleWindowsJson(scheduleWindows))
    }
    if (columns == null) return full
    return JsonObject(full.filterKeys { it == "user_id" || it in columns })
}

/**
 * 두 설정 사이에 **어느 `settings` 컬럼이 달라졌는가**. 오프라인 저장이 실패했을 때 무엇을
 * 대기분에 넣을지 정하는 데 쓴다.
 *
 * 기기별 값(감시 켜짐 여부, 차트 범위, 감시 대상)은 서버 컬럼이 아니라 여기 나오지 않는다 —
 * [com.tubelimiter.app.data.DEVICE_LOCAL_SETTINGS_KEYS] 참고.
 */
fun changedSettingsColumns(previous: Settings, next: Settings): Set<String> = buildSet {
    if (previous.limit.dailyLimitMinutes != next.limit.dailyLimitMinutes) add(SettingsColumn.DAILY_LIMIT_MS)
    if (previous.limit.byDayMinutes != next.limit.byDayMinutes) add(SettingsColumn.DAILY_LIMIT_BY_DAY)
    if (previous.limit.frequency != next.limit.frequency) add(SettingsColumn.DAILY_LIMIT_RESET_FREQUENCY)
    // 두 값이 한 jsonb 컬럼에 같이 들어가므로 하나만 바뀌어도 컬럼 전체가 달라진 것이다.
    if (previous.emergencyAllowance != next.emergencyAllowance ||
        previous.emergencyResetFrequency != next.emergencyResetFrequency
    ) {
        add(SettingsColumn.EMERGENCY_CONFIG)
    }
    if (previous.alarmIntervalMinutes != next.alarmIntervalMinutes) add(SettingsColumn.ALARM_INTERVAL_MINUTES)
    if (previous.alarmMilestonesEnabled != next.alarmMilestonesEnabled) add(SettingsColumn.ALARM_MILESTONES_ENABLED)
    if (previous.hardcoreMode != next.hardcoreMode) add(SettingsColumn.HARDCORE_MODE)
    if (previous.hardcoreDisableRequestedAt != next.hardcoreDisableRequestedAt) {
        add(SettingsColumn.HARDCORE_DISABLE_REQUESTED_AT)
    }
    if (previous.scheduleWindows != next.scheduleWindows) add(SettingsColumn.SCHEDULED_BLOCKS)
}

/**
 * [columns]에 해당하는 값만 [source]에서 가져온 사본. [changedSettingsColumns]의 역방향이라
 * 바로 옆에 둔다 — 컬럼이 하나 늘었을 때 두 함수를 같이 보게 하려는 것이다.
 *
 * 쓰이는 곳은 대기분 재검증([planPendingSettingsPush])이다: "서버 값 + 대기분 컬럼만 로컬 값"이
 * 하드코어 판정의 `next`이고, 거부된 컬럼을 서버 값으로 되돌리는 것이 그 반대 방향이다.
 */
fun Settings.withColumnsFrom(source: Settings, columns: Set<String>): Settings {
    var result = this
    if (SettingsColumn.DAILY_LIMIT_MS in columns) {
        result = result.copy(limit = result.limit.copy(dailyLimitMinutes = source.limit.dailyLimitMinutes))
    }
    if (SettingsColumn.DAILY_LIMIT_BY_DAY in columns) {
        result = result.copy(limit = result.limit.copy(byDayMinutes = source.limit.byDayMinutes))
    }
    if (SettingsColumn.DAILY_LIMIT_RESET_FREQUENCY in columns) {
        result = result.copy(limit = result.limit.copy(frequency = source.limit.frequency))
    }
    // 두 값이 한 jsonb 컬럼에 같이 들어간다(changedSettingsColumns의 같은 자리 참고).
    if (SettingsColumn.EMERGENCY_CONFIG in columns) {
        result = result.copy(
            emergencyAllowance = source.emergencyAllowance,
            emergencyResetFrequency = source.emergencyResetFrequency,
        )
    }
    if (SettingsColumn.ALARM_INTERVAL_MINUTES in columns) {
        result = result.copy(alarmIntervalMinutes = source.alarmIntervalMinutes)
    }
    if (SettingsColumn.ALARM_MILESTONES_ENABLED in columns) {
        result = result.copy(alarmMilestonesEnabled = source.alarmMilestonesEnabled)
    }
    if (SettingsColumn.HARDCORE_MODE in columns) {
        result = result.copy(hardcoreMode = source.hardcoreMode)
    }
    if (SettingsColumn.HARDCORE_DISABLE_REQUESTED_AT in columns) {
        result = result.copy(hardcoreDisableRequestedAt = source.hardcoreDisableRequestedAt)
    }
    if (SettingsColumn.SCHEDULED_BLOCKS in columns) {
        result = result.copy(scheduleWindows = source.scheduleWindows)
    }
    return result
}

private fun decodeByDay(raw: JsonObject?, fallback: List<Int>): List<Int> {
    if (raw == null || raw.isEmpty()) return fallback
    return List(7) { index ->
        raw[index.toString()]?.jsonPrimitive?.intOrNull
            ?: fallback.getOrElse(index) { DEFAULT_LIMIT_MINUTES }
    }
}

private fun decodeFrequency(raw: String?, fallback: LimitFrequency): LimitFrequency = when (raw) {
    "by_day" -> LimitFrequency.BY_DAY
    "daily" -> LimitFrequency.DAILY
    else -> fallback
}

private fun encodeFrequency(frequency: LimitFrequency): String = when (frequency) {
    LimitFrequency.BY_DAY -> "by_day"
    LimitFrequency.DAILY -> "daily"
}

/** Days arrive as 0/1 ints (Sunday=0), matching `daily_limit_by_day`'s convention. */
private fun decodeScheduleWindowsJson(raw: JsonArray): List<ScheduleWindow> =
    raw.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val label = obj["label"]?.jsonPrimitive?.contentOrNull ?: ""
        val days = (obj["days"] as? JsonArray)?.map { (it.jsonPrimitive.intOrNull ?: 0) != 0 }
            ?: return@mapNotNull null
        if (days.size < 7) return@mapNotNull null
        val startMinute = obj["startMinute"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val endMinute = obj["endMinute"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        val enabled = obj["enabled"]?.jsonPrimitive?.booleanOrNull ?: true
        ScheduleWindow(id, label, days, startMinute, endMinute, enabled)
    }

private fun encodeScheduleWindowsJson(windows: List<ScheduleWindow>): JsonArray = buildJsonArray {
    windows.forEach { window ->
        add(
            buildJsonObject {
                put("id", window.id)
                put("label", window.label)
                put("days", buildJsonArray { window.days.forEach { add(if (it) 1 else 0) } })
                put("startMinute", window.startMinute)
                put("endMinute", window.endMinute)
                put("enabled", window.enabled)
            },
        )
    }
}

private fun decodeEmergencyReset(
    config: JsonObject?,
    fallback: EmergencyResetFrequency,
): EmergencyResetFrequency {
    val primitive = config?.get("resetFrequency")?.jsonPrimitive ?: return fallback
    val raw = if (primitive.isString) primitive.content else return fallback
    return EmergencyResetFrequency.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
        ?: fallback
}

// --- streaks ---

fun RemoteStreak.toStreakRecord() = StreakRecord(
    currentStreak = currentStreak,
    bestStreak = bestStreak,
    lastResultDate = lastResultDate,
    totalSuccessDays = totalSuccessDays,
    xp = xp,
    perfectDays = perfectDays,
    currentPerfectStreak = currentPerfectStreak,
    bestPerfectStreak = bestPerfectStreak,
)

fun StreakRecord.toRemoteJson(userId: String): JsonObject = buildJsonObject {
    put("user_id", userId)
    put("current_streak", currentStreak)
    put("best_streak", bestStreak)
    putNullable("last_result_date", lastResultDate)
    put("total_success_days", totalSuccessDays)
    put("xp", xp)
    put("perfect_days", perfectDays)
    put("current_perfect_streak", currentPerfectStreak)
    put("best_perfect_streak", bestPerfectStreak)
}

/**
 * Whichever record has settled more days has seen more history, so it wins outright.
 * Without this, signing in on a second device would silently reset a streak.
 */
fun mergeStreaks(local: StreakRecord, remote: StreakRecord): StreakRecord = when {
    remote.totalSuccessDays > local.totalSuccessDays -> remote
    local.totalSuccessDays > remote.totalSuccessDays -> local
    // Same ground covered: keep the better figure from each side.
    else -> local.copy(
        currentStreak = maxOf(local.currentStreak, remote.currentStreak),
        bestStreak = maxOf(local.bestStreak, remote.bestStreak),
        xp = maxOf(local.xp, remote.xp),
        lastResultDate = laterDate(local.lastResultDate, remote.lastResultDate),
        perfectDays = maxOf(local.perfectDays, remote.perfectDays),
        currentPerfectStreak = maxOf(local.currentPerfectStreak, remote.currentPerfectStreak),
        bestPerfectStreak = maxOf(local.bestPerfectStreak, remote.bestPerfectStreak),
    )
}

private fun laterDate(a: String?, b: String?): String? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}

// --- timestamps ---

fun parseTimestampMillis(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    return runCatching { Instant.parse(raw).toEpochMilli() }.getOrElse {
        // Postgres renders timestamptz as "2026-09-03 05:00:00+00", which Instant rejects.
        runCatching {
            OffsetDateTime.parse(raw.replace(' ', 'T'), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                .toInstant()
                .toEpochMilli()
        }.getOrNull()
    }
}

fun formatTimestampMillis(millis: Long?): String? = millis?.let { Instant.ofEpochMilli(it).toString() }

private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
    put(key, if (value == null) JsonNull else JsonPrimitive(value))
}
