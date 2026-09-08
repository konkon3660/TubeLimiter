package com.tubelimiter.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 대시보드는 원래 이 기기의 DataStore 기록만 그려서, 앱을 재설치하거나 다른 기기에서만 본 날은
 * 히트맵이 통째로 비었다. 그 구멍을 메우는 병합 규칙 — 날짜별 max(로컬, 서버) — 를 고정한다.
 * 어느 쪽이든 뒤처져 있을 수 있으므로 한쪽을 무조건 채택하거나 둘을 더하면 안 된다.
 */
class HistoryMergeTest {

    private fun row(
        date: String,
        usageMs: Long = 0L,
        emergencyMs: Long = 0L,
        emergencyUses: Int = 0,
    ) = RemoteDailyUsageRow(date, usageMs, emergencyMs, emergencyUses)

    @Test
    fun `서버만 아는 날도 병합 결과에 나온다`() {
        val merged = mergeMillisByDate(emptyMap(), listOf(row("2026-09-01", usageMs = 1_800_000))) { it.usageMs }
        assertEquals(mapOf("2026-09-01" to 1_800_000L), merged)
    }

    @Test
    fun `로컬만 아는 날은 병합 후에도 살아남는다`() {
        val merged = mergeMillisByDate(mapOf("2026-09-01" to 600_000L), emptyList()) { it.usageMs }
        assertEquals(mapOf("2026-09-01" to 600_000L), merged)
    }

    @Test
    fun `로컬이 앞서 있으면 로컬이 이긴다`() {
        // 아직 동기화 전: refreshDailyUsageSync는 30초 스로틀이다.
        val merged = mergeMillisByDate(
            mapOf("2026-09-01" to 900_000L),
            listOf(row("2026-09-01", usageMs = 600_000)),
        ) { it.usageMs }
        assertEquals(mapOf("2026-09-01" to 900_000L), merged)
    }

    @Test
    fun `서버가 앞서 있으면 서버가 이긴다`() {
        // 흔한 경우: 서버 행에는 다른 기기 몫까지 들어 있다.
        val merged = mergeMillisByDate(
            mapOf("2026-09-01" to 600_000L),
            listOf(row("2026-09-01", usageMs = 900_000)),
        ) { it.usageMs }
        assertEquals(mapOf("2026-09-01" to 900_000L), merged)
    }

    @Test
    fun `두 값을 더하지 않는다 - 더하면 이 기기 몫을 두 번 센다`() {
        val merged = mergeMillisByDate(
            mapOf("2026-09-01" to 600_000L),
            listOf(row("2026-09-01", usageMs = 600_000)),
        ) { it.usageMs }
        assertEquals(mapOf("2026-09-01" to 600_000L), merged)
    }

    @Test
    fun `양쪽이 비면 빈 맵이다`() {
        assertEquals(emptyMap<String, Long>(), mergeMillisByDate(emptyMap(), emptyList()) { it.usageMs })
    }

    @Test
    fun `이상값은 max를 오염시키지 않고 0으로 정규화된다`() {
        val merged = mergeMillisByDate(
            mapOf("2026-09-01" to -5L),
            listOf(row("2026-09-01", usageMs = -1), row("", usageMs = 100)),
        ) { it.usageMs }
        assertEquals(mapOf("2026-09-01" to 0L), merged)
    }

    @Test
    fun `긴급 시청 시간도 자기 컬럼으로 같은 규칙을 쓴다`() {
        val merged = mergeMillisByDate(
            mapOf("2026-09-01" to 120_000L),
            listOf(row("2026-09-01", usageMs = 900_000, emergencyMs = 300_000)),
        ) { it.emergencyMs }
        assertEquals(mapOf("2026-09-01" to 300_000L), merged)
    }

    @Test
    fun `서버에만 있는 날은 긴급 횟수도 서버 값을 쓴다`() {
        // 확장과 의도적으로 다른 지점: 확장 historyMerge.js는 이 경우 횟수를 null로 두지만
        // (emergency_uses가 확장 대시보드 경로에 아직 안 붙어 있어서), 안드로이드는 그 컬럼을
        // 같이 읽어 오므로 실제 횟수를 알 수 있다. 그래야 다른 기기에서 긴급 시청을 쓴 날이
        // 완벽한 날에서 제대로 걸러진다.
        val merged = mergeHistories(
            localUsage = emptyMap(),
            localEmergencyMillis = emptyMap(),
            localEmergencyUses = emptyMap(),
            serverRows = listOf(row("2026-09-01", usageMs = 900_000, emergencyMs = 300_000, emergencyUses = 2)),
        )
        assertEquals(900_000L, merged.usage["2026-09-01"])
        assertEquals(MergedEmergencyDay(millis = 300_000L, uses = 2), merged.emergency["2026-09-01"])
    }

    @Test
    fun `긴급 횟수도 날짜별 max다 - 아직 안 올린 로컬 횟수가 지워지지 않는다`() {
        val merged = mergeHistories(
            localUsage = mapOf("2026-09-01" to 900_000L),
            localEmergencyMillis = mapOf("2026-09-01" to 300_000L),
            localEmergencyUses = mapOf("2026-09-01" to 3),
            serverRows = listOf(row("2026-09-01", usageMs = 600_000, emergencyMs = 120_000, emergencyUses = 1)),
        )
        assertEquals(900_000L, merged.usage["2026-09-01"])
        assertEquals(MergedEmergencyDay(millis = 300_000L, uses = 3), merged.emergency["2026-09-01"])
    }

    @Test
    fun `사용량 기록이 있는 날은 긴급 항목이 반드시 만들어진다`() {
        // "항목이 없는 날"과 "긴급 시청이 0회인 날"을 호출부가 헷갈리지 않게.
        val merged = mergeHistories(
            localUsage = mapOf("2026-09-01" to 600_000L),
            localEmergencyMillis = emptyMap(),
            localEmergencyUses = emptyMap(),
            serverRows = emptyList(),
        )
        assertEquals(MergedEmergencyDay(millis = 0L, uses = 0), merged.emergency["2026-09-01"])
    }

    @Test
    fun `서버 조회 실패(빈 목록)면 로컬 기록 그대로 그린다`() {
        val merged = mergeHistories(
            localUsage = mapOf("2026-09-01" to 600_000L, "2026-09-02" to 60_000L),
            localEmergencyMillis = mapOf("2026-09-01" to 30_000L),
            localEmergencyUses = mapOf("2026-09-01" to 1),
            serverRows = emptyList(),
        )
        assertEquals(mapOf("2026-09-01" to 600_000L, "2026-09-02" to 60_000L), merged.usage)
        assertEquals(MergedEmergencyDay(millis = 30_000L, uses = 1), merged.emergency["2026-09-01"])
        assertEquals(MergedEmergencyDay(millis = 0L, uses = 0), merged.emergency["2026-09-02"])
    }

    @Test
    fun `로컬에만 있는 날과 서버에만 있는 날이 한 결과에 모인다`() {
        val merged = mergeHistories(
            localUsage = mapOf("2026-09-01" to 600_000L),
            localEmergencyMillis = emptyMap(),
            localEmergencyUses = emptyMap(),
            serverRows = listOf(row("2026-09-02", usageMs = 900_000)),
        )
        assertEquals(mapOf("2026-09-01" to 600_000L, "2026-09-02" to 900_000L), merged.usage)
    }
}
