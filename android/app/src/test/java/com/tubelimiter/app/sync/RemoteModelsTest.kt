package com.tubelimiter.app.sync

import com.tubelimiter.app.data.Settings
import com.tubelimiter.app.gamification.StreakRecord
import com.tubelimiter.app.limit.EmergencyResetFrequency
import com.tubelimiter.app.limit.LimitConfig
import com.tubelimiter.app.limit.LimitFrequency
import com.tubelimiter.app.limit.ScheduleWindow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String): RemoteSettings = json.decodeFromString(raw)

    private fun obj(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `a row written by the extension decodes into local settings`() {
        val remote = parse(
            """
            {
              "daily_limit_ms": 2700000,
              "daily_limit_by_day": {"0": 15, "1": 30, "2": 30, "3": 45, "4": 45, "5": 60, "6": -1},
              "daily_limit_reset_frequency": "by_day",
              "emergency_config": {"dailyUses": 5, "resetFrequency": "weekly"},
              "alarm_interval_minutes": 10,
              "alarm_milestones_enabled": false,
              "hardcore_mode": true,
              "hardcore_disable_requested_at": null,
              "scheduled_blocks": [
                {"id": "1", "label": "밤 시간", "days": [0,1,1,1,1,1,0], "startMinute": 1320, "endMinute": 420, "enabled": true}
              ]
            }
            """.trimIndent(),
        )

        val settings = remote.toSettings(Settings())

        assertEquals(45, settings.limit.dailyLimitMinutes)
        assertEquals(listOf(15, 30, 30, 45, 45, 60, -1), settings.limit.byDayMinutes)
        assertEquals(LimitFrequency.BY_DAY, settings.limit.frequency)
        assertEquals(5, settings.emergencyAllowance)
        assertEquals(EmergencyResetFrequency.WEEKLY, settings.emergencyResetFrequency)
        assertEquals(10, settings.alarmIntervalMinutes)
        assertEquals(false, settings.alarmMilestonesEnabled)
        assertEquals(true, settings.hardcoreMode)
        assertNull(settings.hardcoreDisableRequestedAt)
        assertEquals(
            listOf(
                ScheduleWindow(
                    id = "1",
                    label = "밤 시간",
                    days = listOf(false, true, true, true, true, true, false),
                    startMinute = 1320,
                    endMinute = 420,
                    enabled = true,
                ),
            ),
            settings.scheduleWindows,
        )
    }

    @Test
    fun `absent columns keep the local value`() {
        val local = Settings(
            limit = LimitConfig(dailyLimitMinutes = 90, frequency = LimitFrequency.BY_DAY),
            alarmIntervalMinutes = 30,
            monitoringEnabled = false,
            scheduleWindows = listOf(ScheduleWindow("1", "x", List(7) { true }, 0, 60, true)),
        )
        val settings = parse("{}").toSettings(local)

        assertEquals(90, settings.limit.dailyLimitMinutes)
        assertEquals(LimitFrequency.BY_DAY, settings.limit.frequency)
        assertEquals(30, settings.alarmIntervalMinutes)
        assertEquals(local.scheduleWindows, settings.scheduleWindows)
    }

    @Test
    fun `an empty scheduled_blocks array is a real state, not something to fall back away from`() {
        val local = Settings(scheduleWindows = listOf(ScheduleWindow("1", "x", List(7) { true }, 0, 60, true)))
        val settings = parse("""{"scheduled_blocks": []}""").toSettings(local)
        assertTrue(settings.scheduleWindows.isEmpty())
    }

    @Test
    fun `a hardcore disable requested while offline survives the next pull`() {
        // The request is written locally first and only then pushed. If that push failed there
        // is nothing in the column yet, and wiping the local value would silently restart the
        // 1-hour cooldown the user is already partway through.
        val requestedAt = 1_760_000_000_000L
        val local = Settings(hardcoreMode = true, hardcoreDisableRequestedAt = requestedAt)
        val settings = parse("""{"hardcore_mode": true, "hardcore_disable_requested_at": null}""")
            .toSettings(local)

        assertEquals(requestedAt, settings.hardcoreDisableRequestedAt)
        assertEquals(true, settings.hardcoreMode)
    }

    @Test
    fun `a disable request that did reach the server wins over the local one`() {
        val local = Settings(hardcoreMode = true, hardcoreDisableRequestedAt = 1_760_000_000_000L)
        val settings = parse("""{"hardcore_disable_requested_at": "2026-09-03 05:00:00+00"}""")
            .toSettings(local)

        assertEquals(parseTimestampMillis("2026-09-03T05:00:00Z"), settings.hardcoreDisableRequestedAt)
    }

    @Test
    fun `monitoring is device-local and never taken from the server`() {
        val local = Settings(monitoringEnabled = false)
        assertEquals(false, parse("""{"hardcore_mode": true}""").toSettings(local).monitoringEnabled)
    }

    @Test
    fun `an empty by-day object falls back rather than zeroing every day`() {
        val local = Settings(limit = LimitConfig(byDayMinutes = listOf(1, 2, 3, 4, 5, 6, 7)))
        val settings = parse("""{"daily_limit_by_day": {}}""").toSettings(local)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), settings.limit.byDayMinutes)
    }

    @Test
    fun `the payload carries only the columns this client owns`() {
        val payload = Settings().toRemoteJson("user-1")
        assertEquals(
            setOf(
                "user_id",
                "daily_limit_ms",
                "daily_limit_by_day",
                "daily_limit_reset_frequency",
                "emergency_config",
                "alarm_interval_minutes",
                "alarm_milestones_enabled",
                "hardcore_mode",
                "hardcore_disable_requested_at",
                "scheduled_blocks",
            ),
            payload.keys,
        )
        // Browser-only columns must stay out so the extension's values survive the upsert.
        // documents/BACKEND.md가 "이 세 컬럼"이라고 적어둔 그 셋이다 — shorts_limit_ms까지
        // 명시해야 새 브라우저 전용 컬럼이 payload에 섞여 들어갈 때 이 테스트가 잡아낸다.
        assertTrue("whitelist" !in payload.keys)
        assertTrue("always_block_shorts" !in payload.keys)
        assertTrue("shorts_limit_ms" !in payload.keys)
    }

    @Test
    fun `minutes are written back as milliseconds`() {
        val payload = Settings(limit = LimitConfig(dailyLimitMinutes = 45)).toRemoteJson("user-1")
        assertEquals(2_700_000L, payload["daily_limit_ms"]?.jsonPrimitive?.longOrNull)
    }

    @Test
    fun `frequency uses the extension's snake_case wire values`() {
        val byDay = Settings(limit = LimitConfig(frequency = LimitFrequency.BY_DAY)).toRemoteJson("u")
        assertEquals("by_day", byDay["daily_limit_reset_frequency"]?.jsonPrimitive?.content)

        val daily = Settings(limit = LimitConfig(frequency = LimitFrequency.DAILY)).toRemoteJson("u")
        assertEquals("daily", daily["daily_limit_reset_frequency"]?.jsonPrimitive?.content)
    }

    @Test
    fun `emergency config keeps the extension's camelCase keys`() {
        val payload = Settings(
            emergencyAllowance = 5,
            emergencyResetFrequency = EmergencyResetFrequency.MONTHLY,
        ).toRemoteJson("u")

        val config = payload["emergency_config"]!!.jsonObject
        assertEquals(5, config["dailyUses"]?.jsonPrimitive?.intOrNull)
        assertEquals("monthly", config["resetFrequency"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a settings round trip is lossless`() {
        val original = Settings(
            limit = LimitConfig(
                dailyLimitMinutes = 90,
                byDayMinutes = listOf(15, 30, 45, 60, 90, 120, -1),
                frequency = LimitFrequency.BY_DAY,
            ),
            emergencyAllowance = 1,
            emergencyResetFrequency = EmergencyResetFrequency.MONTHLY,
            alarmIntervalMinutes = 30,
            alarmMilestonesEnabled = false,
            hardcoreMode = true,
            hardcoreDisableRequestedAt = 1_760_000_000_000L,
            scheduleWindows = listOf(
                ScheduleWindow("1", "밤 시간", listOf(false, true, true, true, true, true, false), 1320, 420, true),
                ScheduleWindow("2", "점심", List(7) { true }, 720, 780, enabled = false),
            ),
        )
        val wire = original.toRemoteJson("u").toString()
        val restored = parse(wire).toSettings(Settings())
        assertEquals(original.copy(monitoringEnabled = Settings().monitoringEnabled), restored)
    }

    @Test
    fun `schedule windows round trip through the wire JSON with days keyed Sunday first`() {
        val payload = Settings(
            scheduleWindows = listOf(ScheduleWindow("1", "밤", listOf(true, false, false, false, false, false, true), 1320, 420, true)),
        ).toRemoteJson("u")

        val blocks = payload["scheduled_blocks"]!!.jsonArray
        assertEquals(1, blocks.size)
        val block = blocks[0].jsonObject
        assertEquals("1", block["id"]?.jsonPrimitive?.content)
        assertEquals("밤", block["label"]?.jsonPrimitive?.content)
        assertEquals(listOf(1, 0, 0, 0, 0, 0, 1), block["days"]!!.jsonArray.map { it.jsonPrimitive.intOrNull })
        assertEquals(1320, block["startMinute"]?.jsonPrimitive?.intOrNull)
        assertEquals(420, block["endMinute"]?.jsonPrimitive?.intOrNull)
        assertEquals(true, block["enabled"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `by-day payload is keyed by day index with Sunday first`() {
        val payload = Settings(
            limit = LimitConfig(byDayMinutes = listOf(10, 20, 30, 40, 50, 60, 70)),
        ).toRemoteJson("u")

        val byDay = payload["daily_limit_by_day"]!!.jsonObject
        assertEquals(10, byDay["0"]?.jsonPrimitive?.intOrNull)
        assertEquals(70, byDay["6"]?.jsonPrimitive?.intOrNull)
    }

    @Test
    fun `hardcore mode survives as a boolean, not a string`() {
        val payload = Settings(hardcoreMode = true).toRemoteJson("u")
        assertEquals(true, payload["hardcore_mode"]?.jsonPrimitive?.booleanOrNull)
    }

    @Test
    fun `postgres and ISO timestamps both parse`() {
        val iso = parseTimestampMillis("2026-09-03T05:00:00Z")
        val postgres = parseTimestampMillis("2026-09-03 05:00:00+00")
        assertEquals(iso, postgres)
        assertNull(parseTimestampMillis(null))
        assertNull(parseTimestampMillis("   "))
        assertNull(parseTimestampMillis("not a date"))
    }

    @Test
    fun `timestamps round trip`() {
        val millis = 1_760_000_000_000L
        assertEquals(millis, parseTimestampMillis(formatTimestampMillis(millis)))
    }

    @Test
    fun `the record that settled more days wins the merge`() {
        val local = StreakRecord(currentStreak = 2, bestStreak = 3, totalSuccessDays = 4, xp = 100)
        val remote = StreakRecord(currentStreak = 9, bestStreak = 9, totalSuccessDays = 40, xp = 900)
        assertEquals(remote, mergeStreaks(local, remote))
        assertEquals(remote, mergeStreaks(remote, local))
    }

    @Test
    fun `an even merge keeps the better figure from each side`() {
        val local = StreakRecord(
            currentStreak = 5,
            bestStreak = 5,
            totalSuccessDays = 10,
            xp = 300,
            lastResultDate = "2026-09-01",
        )
        val remote = StreakRecord(
            currentStreak = 3,
            bestStreak = 12,
            totalSuccessDays = 10,
            xp = 250,
            lastResultDate = "2026-09-03",
        )
        val merged = mergeStreaks(local, remote)

        assertEquals(5, merged.currentStreak)
        assertEquals(12, merged.bestStreak)
        assertEquals(300, merged.xp)
        assertEquals("2026-09-03", merged.lastResultDate)
    }

    @Test
    fun `an even merge keeps the better perfect-day figures too`() {
        val local = StreakRecord(
            totalSuccessDays = 10,
            perfectDays = 8,
            currentPerfectStreak = 4,
            bestPerfectStreak = 4,
        )
        val remote = StreakRecord(
            totalSuccessDays = 10,
            perfectDays = 6,
            currentPerfectStreak = 2,
            bestPerfectStreak = 9,
        )
        val merged = mergeStreaks(local, remote)

        assertEquals(8, merged.perfectDays)
        assertEquals(4, merged.currentPerfectStreak)
        assertEquals(9, merged.bestPerfectStreak)
    }

    @Test
    fun `a streak payload matches the schema's column names`() {
        val payload = StreakRecord(
            currentStreak = 3,
            bestStreak = 7,
            lastResultDate = "2026-09-02",
            totalSuccessDays = 20,
            xp = 480,
            perfectDays = 15,
            currentPerfectStreak = 3,
            bestPerfectStreak = 9,
        ).toRemoteJson("user-1")

        assertEquals(
            setOf(
                "user_id",
                "current_streak",
                "best_streak",
                "last_result_date",
                "total_success_days",
                "xp",
                "perfect_days",
                "current_perfect_streak",
                "best_perfect_streak",
            ),
            payload.keys,
        )
        assertEquals("2026-09-02", payload["last_result_date"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a streak with no settled day writes an explicit null date`() {
        val payload = StreakRecord().toRemoteJson("user-1")
        assertTrue(payload["last_result_date"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `increment_daily_usage returns the use count alongside the millisecond totals`() {
        val decoded = json.decodeFromString<RemoteDailyUsageTotal>(
            """{"usage_ms":1800000,"shorts_ms":0,"emergency_ms":300000,"emergency_uses":2}""",
        )
        assertEquals(1_800_000L, decoded.usageMs)
        assertEquals(300_000L, decoded.emergencyMs)
        assertEquals(2, decoded.emergencyUses)
    }

    @Test
    fun `a bucket row keeps its date so weekly and monthly totals can be summed`() {
        val rows = json.decodeFromString<List<RemoteEmergencyUsesRow>>(
            """[{"date":"2026-08-31","emergency_uses":1},{"date":"2026-09-02","emergency_uses":2}]""",
        )
        assertEquals(mapOf("2026-08-31" to 1, "2026-09-02" to 2), rows.associate { it.date to it.emergencyUses })
    }
}
