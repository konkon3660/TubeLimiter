package com.tubelimiter.app.limit

import com.tubelimiter.app.data.decodeLongMap
import com.tubelimiter.app.data.encodeLongMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 히트맵이 과거 날짜를 "지금 설정된 한도"로 소급 판정하지 않게 하는 기록/조회 규칙을 고정한다.
 * 한도를 30분에서 2시간으로 올려도 예전에 초과했던 날은 초과인 채로 남아야 한다.
 *
 * 확장 `test/limitHistory.test.js`와 같은 경계를 본다 — 규칙이 갈라지면 같은 계정을 두
 * 클라이언트로 볼 때 히트맵이 서로 다르게 나온다.
 */
class LimitHistoryTest {

    private val minute = 60_000L

    // 2026-09-02는 수요일, 2026-09-06은 일요일.
    private val wednesday = LocalDate.parse("2026-09-02")
    private val sunday = LocalDate.parse("2026-09-06")

    private val thirtyMin = LimitConfig(dailyLimitMinutes = 30, frequency = LimitFrequency.DAILY)
    private val twoHours = LimitConfig(dailyLimitMinutes = 120, frequency = LimitFrequency.DAILY)

    private val keepAll = setOf(wednesday.toString(), sunday.toString())

    @Test
    fun `기록이 있는 날은 지금 설정을 바꿔도 그날 한도로 판정한다`() {
        val history = mapOf(wednesday.toString() to 30 * minute)
        val resolved = resolveLimitForDate(history, twoHours, wednesday)
        assertEquals(30 * minute, resolved.limitMillis)
        assertFalse(resolved.estimated)
    }

    @Test
    fun `기록이 없는 옛 날짜는 현재 설정으로 근사 판정하고 추정으로 표시된다`() {
        val resolved = resolveLimitForDate(emptyMap(), thirtyMin, wednesday)
        assertEquals(30 * minute, resolved.limitMillis)
        assertTrue(resolved.estimated)
    }

    @Test
    fun `무제한은 센티널로 저장되고 UNLIMITED_MILLIS로 되읽힌다`() {
        assertEquals(UNLIMITED_LIMIT_SENTINEL, serializeLimitMillis(UNLIMITED_MILLIS))

        val plan = planLimitHistoryUpdate(
            history = emptyMap(),
            updates = listOf(LimitHistoryEntry(wednesday.toString(), UNLIMITED_MILLIS)),
            keepKeys = keepAll,
        )
        assertEquals(UNLIMITED_LIMIT_SENTINEL, plan.history[wednesday.toString()])

        val resolved = resolveLimitForDate(plan.history, thirtyMin, wednesday)
        assertEquals(UNLIMITED_MILLIS, resolved.limitMillis)
        assertFalse(resolved.estimated)
    }

    @Test
    fun `요일별 무제한 설정도 같은 센티널 하나로 접힌다`() {
        // -1분(UNLIMITED_MINUTES)은 computeLimitMillis가 UNLIMITED_MILLIS로 바꾸고, 저장은 -1이 된다.
        val byDay = LimitConfig(
            byDayMinutes = List(7) { UNLIMITED_MINUTES },
            frequency = LimitFrequency.BY_DAY,
        )
        val plan = planLimitHistoryUpdate(
            history = emptyMap(),
            updates = listOf(LimitHistoryEntry(sunday.toString(), computeLimitMillis(byDay, sunday))),
            keepKeys = keepAll,
        )
        assertEquals(UNLIMITED_LIMIT_SENTINEL, plan.history[sunday.toString()])
    }

    @Test
    fun `한도 0은 무제한이 아니라 0으로 남는다`() {
        val plan = planLimitHistoryUpdate(
            history = emptyMap(),
            updates = listOf(LimitHistoryEntry(sunday.toString(), 0L)),
            keepKeys = keepAll,
        )
        assertEquals(0L, plan.history[sunday.toString()])
        assertEquals(0L, resolveLimitForDate(plan.history, thirtyMin, sunday).limitMillis)
    }

    @Test
    fun `요일별 한도 오버라이드도 그날 기록된 값이 우선한다`() {
        val byDay = LimitConfig(
            byDayMinutes = listOf(10, 20, 30, 40, 50, 60, 70),
            frequency = LimitFrequency.BY_DAY,
        )
        // 일요일(dayIndex 0) 기록이 남아 있으면 요일 테이블(10분)이 아니라 기록된 45분으로 판정한다.
        val history = mapOf(sunday.toString() to 45 * minute)
        assertEquals(45 * minute, resolveLimitForDate(history, byDay, sunday).limitMillis)
        // 기록이 없는 수요일(dayIndex 3)만 요일 테이블로 근사한다.
        val wednesdayResolved = resolveLimitForDate(history, byDay, wednesday)
        assertEquals(40 * minute, wednesdayResolved.limitMillis)
        assertTrue(wednesdayResolved.estimated)
    }

    @Test
    fun `손상된 인코딩은 무제한이 아니라 근사치로 떨어진다`() {
        // decodeLongMap이 값이 숫자가 아닌 항목을 버리므로 여기서는 "기록 없음"으로 보인다.
        // 무제한으로 넘겨버리면 그날이 조용히 "무조건 성공"이 된다.
        val decoded = decodeLongMap("$wednesday=broken;$sunday=${45 * minute}")
        val resolved = resolveLimitForDate(decoded, thirtyMin, wednesday)
        assertEquals(30 * minute, resolved.limitMillis)
        assertTrue(resolved.estimated)
        // 같은 문자열의 멀쩡한 항목은 그대로 살아 있어야 한다.
        assertEquals(45 * minute, resolveLimitForDate(decoded, thirtyMin, sunday).limitMillis)
    }

    @Test
    fun `기존 문자열 인코딩으로 왕복해도 값이 그대로다`() {
        val history = mapOf(
            wednesday.toString() to 30 * minute,
            sunday.toString() to UNLIMITED_LIMIT_SENTINEL,
        )
        assertEquals(history, decodeLongMap(encodeLongMap(history)))
    }

    @Test
    fun `같은 값을 다시 기록하면 changed가 false다`() {
        val history = mapOf(wednesday.toString() to 30 * minute)
        val plan = planLimitHistoryUpdate(
            history = history,
            updates = listOf(LimitHistoryEntry(wednesday.toString(), 30 * minute)),
            keepKeys = keepAll,
        )
        assertFalse(plan.changed)
        assertEquals(history, plan.history)
    }

    @Test
    fun `오늘 한도를 바꾸면 그날 기록이 마지막 값으로 갱신된다`() {
        val plan = planLimitHistoryUpdate(
            history = mapOf(wednesday.toString() to 30 * minute),
            updates = listOf(LimitHistoryEntry(wednesday.toString(), 120 * minute)),
            keepKeys = keepAll,
        )
        assertTrue(plan.changed)
        assertEquals(120 * minute, plan.history[wednesday.toString()])
    }

    @Test
    fun `keepExisting은 지난 날짜의 기록을 덮어쓰지 않는다`() {
        val plan = planLimitHistoryUpdate(
            history = mapOf(wednesday.toString() to 30 * minute),
            updates = listOf(LimitHistoryEntry(wednesday.toString(), 120 * minute, keepExisting = true)),
            keepKeys = keepAll,
        )
        assertFalse(plan.changed)
        assertEquals(30 * minute, plan.history[wednesday.toString()])
    }

    @Test
    fun `keepExisting이어도 기록이 없는 날은 채워준다`() {
        val plan = planLimitHistoryUpdate(
            history = emptyMap(),
            updates = listOf(LimitHistoryEntry(wednesday.toString(), 30 * minute, keepExisting = true)),
            keepKeys = keepAll,
        )
        assertTrue(plan.changed)
        assertEquals(30 * minute, plan.history[wednesday.toString()])
    }

    @Test
    fun `원본 맵은 변형하지 않는다`() {
        val history = mapOf(wednesday.toString() to 30 * minute)
        val plan = planLimitHistoryUpdate(
            history = history,
            updates = listOf(LimitHistoryEntry(sunday.toString(), 45 * minute)),
            keepKeys = keepAll,
        )
        assertEquals(mapOf(wednesday.toString() to 30 * minute), history)
        assertEquals(45 * minute, plan.history[sunday.toString()])
    }

    @Test
    fun `보관 기간 밖의 날짜는 정리된다`() {
        val plan = planLimitHistoryUpdate(
            history = mapOf("2026-01-01" to 30 * minute, wednesday.toString() to 30 * minute),
            updates = emptyList(),
            keepKeys = setOf(wednesday.toString()),
        )
        assertTrue(plan.changed)
        assertEquals(setOf(wednesday.toString()), plan.history.keys)
    }

    @Test
    fun `기록도 정리도 없으면 changed가 false다`() {
        val plan = planLimitHistoryUpdate(
            history = mapOf(wednesday.toString() to 30 * minute),
            updates = emptyList(),
            keepKeys = keepAll,
        )
        assertFalse(plan.changed)
    }

    @Test
    fun `음수 한도는 무제한으로 오해되지 않게 0으로 접힌다`() {
        assertEquals(0L, serializeLimitMillis(-5_000L))
    }

    // --- 롤오버 정산 (UsageMonitorService.settleFinishedDays가 쓰는 계획) ---
    //
    // 회귀 방지 대상: 스트릭 판정이 그날 기록된 한도가 아니라 "현재 설정"으로 이뤄지면, 며칠 앱을
    // 안 켠 사이 한도를 바꿨을 때 히트맵(기록값)과 스트릭(현재 설정)이 같은 날을 반대로 판정한다.
    // 확장 `checkDateRolloverInner`도 같은 규칙(resolveLimitForDate)으로 맞춰져 있다.

    // 정산 대상: 목~토. 오늘은 일요일이라 정산에서 빠진다.
    private val thursday = LocalDate.parse("2026-09-03")
    private val friday = LocalDate.parse("2026-09-04")
    private val saturday = LocalDate.parse("2026-09-05")
    private val settled = listOf(thursday, friday, saturday)
    private val retainedAll = (settled + sunday).map { it.toString() }.toSet()

    @Test
    fun `기록이 있는 날은 지금 설정이 아니라 그날 한도로 정산한다`() {
        val plan = planRolloverLimits(
            limitHistory = mapOf(thursday.toString() to 30 * minute),
            config = twoHours,
            settledDates = settled,
            today = sunday,
            retained = retainedAll,
        )
        // 히트맵이 읽는 값과 같아야 한다 — 지금 설정(2시간)이 아니라 기록된 30분.
        assertEquals(30 * minute, plan.limitsByDate.getValue(thursday.toString()))
        assertEquals(
            30 * minute,
            resolveLimitForDate(
                mapOf(thursday.toString() to 30 * minute),
                twoHours,
                thursday,
            ).limitMillis,
        )
    }

    @Test
    fun `기록이 없던 날은 방금 남긴 값과 정확히 같은 값으로 정산한다`() {
        val plan = planRolloverLimits(
            limitHistory = emptyMap(),
            config = thirtyMin,
            settledDates = settled,
            today = sunday,
            retained = retainedAll,
        )
        settled.forEach { date ->
            assertEquals(30 * minute, plan.limitsByDate.getValue(date.toString()))
        }
        // 그리고 그 값이 그대로 기록으로 남아 히트맵도 같은 판정을 하게 된다.
        val recorded = plan.historyUpdates.associate { it.date to it.limitMillis }
        settled.forEach { date -> assertEquals(30 * minute, recorded[date.toString()]) }
    }

    @Test
    fun `무제한으로 기록된 날은 정산에서도 무제한이다`() {
        val plan = planRolloverLimits(
            limitHistory = mapOf(friday.toString() to UNLIMITED_LIMIT_SENTINEL),
            config = thirtyMin,
            settledDates = settled,
            today = sunday,
            retained = retainedAll,
        )
        assertEquals(UNLIMITED_MILLIS, plan.limitsByDate.getValue(friday.toString()))
    }

    @Test
    fun `지난 날짜 기록은 keepExisting이고 오늘은 덮어쓴다`() {
        val plan = planRolloverLimits(
            limitHistory = emptyMap(),
            config = thirtyMin,
            settledDates = settled,
            today = sunday,
            retained = retainedAll,
        )
        val bySettleDate = plan.historyUpdates.associateBy { it.date }
        settled.forEach { date -> assertTrue(bySettleDate.getValue(date.toString()).keepExisting) }
        assertFalse(bySettleDate.getValue(sunday.toString()).keepExisting)
    }

    @Test
    fun `보관 기간 밖의 날은 기록하지 않고 현재 설정으로 근사 판정한다`() {
        val plan = planRolloverLimits(
            limitHistory = emptyMap(),
            config = twoHours,
            settledDates = settled,
            today = sunday,
            // 목요일은 이미 usage_history가 버린 날이라 기록할 곳이 없다.
            retained = setOf(friday.toString(), saturday.toString(), sunday.toString()),
        )
        assertFalse(plan.historyUpdates.any { it.date == thursday.toString() })
        // 판정은 그래도 이뤄져야 한다 — 기록이 없으니 현재 설정 추정치.
        assertEquals(120 * minute, plan.limitsByDate.getValue(thursday.toString()))
    }

    @Test
    fun `정산할 날이 없으면 오늘 한도만 남는다`() {
        val plan = planRolloverLimits(
            limitHistory = emptyMap(),
            config = thirtyMin,
            settledDates = emptyList(),
            today = sunday,
            retained = retainedAll,
        )
        assertTrue(plan.limitsByDate.isEmpty())
        assertEquals(listOf(sunday.toString()), plan.historyUpdates.map { it.date })
    }
}
