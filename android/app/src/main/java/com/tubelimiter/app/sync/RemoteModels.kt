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
)

val SETTINGS_COLUMNS = listOf(
    "daily_limit_ms",
    "daily_limit_by_day",
    "daily_limit_reset_frequency",
    "emergency_config",
    "alarm_interval_minutes",
    "alarm_milestones_enabled",
    "hardcore_mode",
    "hardcore_disable_requested_at",
    "scheduled_blocks",
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
 */
fun Settings.toRemoteJson(userId: String): JsonObject = buildJsonObject {
    put("user_id", userId)
    put("daily_limit_ms", limit.dailyLimitMinutes * 60_000L)
    put(
        "daily_limit_by_day",
        buildJsonObject {
            limit.byDayMinutes.forEachIndexed { index, minutes -> put(index.toString(), minutes) }
        },
    )
    put("daily_limit_reset_frequency", encodeFrequency(limit.frequency))
    put(
        "emergency_config",
        buildJsonObject {
            put("dailyUses", emergencyAllowance)
            put("resetFrequency", emergencyResetFrequency.name.lowercase())
        },
    )
    put("alarm_interval_minutes", alarmIntervalMinutes)
    put("alarm_milestones_enabled", alarmMilestonesEnabled)
    put("hardcore_mode", hardcoreMode)
    putNullable("hardcore_disable_requested_at", formatTimestampMillis(hardcoreDisableRequestedAt))
    put("scheduled_blocks", encodeScheduleWindowsJson(scheduleWindows))
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
