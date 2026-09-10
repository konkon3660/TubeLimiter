package com.tubelimiter.app.limit

import com.tubelimiter.app.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// 규칙의 원본은 확장 `extension/src/lib/hardcoreLock.js`이고, 여기 케이스는
// `extension/test/hardcoreLock.test.js`와 같은 상황을 덮는다 — 한쪽만 바뀌면 같은 계정에서
// 폰과 PC의 판정이 갈라진다(documents/BACKEND.md "하드코어 잠금 범위").

private val ALL_DAYS = List(7) { true }
private val MON_ONLY = listOf(false, true, false, false, false, false, false)

private fun locked(
    dailyLimitMinutes: Int = 30,
    byDayMinutes: List<Int> = List(7) { 30 },
    frequency: LimitFrequency = LimitFrequency.DAILY,
    emergencyAllowance: Int = 3,
    emergencyResetFrequency: EmergencyResetFrequency = EmergencyResetFrequency.DAILY,
    scheduleWindows: List<ScheduleWindow> = emptyList(),
    hardcoreMode: Boolean = true,
) = Settings(
    limit = LimitConfig(dailyLimitMinutes, byDayMinutes, frequency),
    emergencyAllowance = emergencyAllowance,
    emergencyResetFrequency = emergencyResetFrequency,
    scheduleWindows = scheduleWindows,
    hardcoreMode = hardcoreMode,
)

private fun window(
    id: String = "w",
    days: List<Boolean> = ALL_DAYS,
    startMinute: Int = 22 * 60,
    endMinute: Int = 7 * 60,
    enabled: Boolean = true,
) = ScheduleWindow(id, "밤 시간", days, startMinute, endMinute, enabled)

/** 관문이 돌려주는 거부 사유만 꺼내 쓴다 — allowed 플래그는 아래 전용 테스트가 따로 본다. */
private fun violations(previous: Settings, next: Settings): List<HardcoreViolation> =
    isHardcoreChangeAllowed(previous, next).violations

class HardcoreLockTest {

    @Test
    fun `hardcore off means nothing is judged at all`() {
        val previous = locked(dailyLimitMinutes = 15, hardcoreMode = false)
        val next = previous.copy(limit = previous.limit.copy(dailyLimitMinutes = 240))
        assertEquals(emptyList<HardcoreViolation>(), violations(previous, next))
    }

    @Test
    fun `raising the daily limit is refused, lowering it is not`() {
        val previous = locked(dailyLimitMinutes = 30)
        assertEquals(
            listOf(HardcoreViolation.DAILY_LIMIT),
            violations(previous, previous.copy(limit = previous.limit.copy(dailyLimitMinutes = 31))),
        )
        assertEquals(
            emptyList<HardcoreViolation>(),
            violations(previous, previous.copy(limit = previous.limit.copy(dailyLimitMinutes = 29))),
        )
    }

    @Test
    fun `switching the daily limit to unlimited is refused - zero is the loosest value`() {
        val previous = locked(dailyLimitMinutes = 30)
        val toZero = previous.copy(limit = previous.limit.copy(dailyLimitMinutes = 0))
        assertTrue(HardcoreViolation.DAILY_LIMIT in violations(previous, toZero))

        // 반대 방향: 무제한이던 값에 한도를 거는 건 강화라 통과한다.
        val fromZero = locked(dailyLimitMinutes = 0)
        assertEquals(
            emptyList<HardcoreViolation>(),
            violations(fromZero, fromZero.copy(limit = fromZero.limit.copy(dailyLimitMinutes = 30))),
        )
    }

    @Test
    fun `raising a single day of the per-day limit is refused`() {
        val previous = locked(byDayMinutes = listOf(30, 30, 30, 30, 30, 30, 30))
        val next = previous.copy(
            limit = previous.limit.copy(byDayMinutes = listOf(30, 30, 30, 45, 30, 30, 30)),
        )
        assertEquals(listOf(HardcoreViolation.BY_DAY_LIMIT), violations(previous, next))
    }

    @Test
    fun `the unlimited sentinel counts as the loosest per-day value`() {
        val previous = locked(byDayMinutes = List(7) { 30 })
        val next = previous.copy(
            limit = previous.limit.copy(
                byDayMinutes = listOf(30, 30, 30, 30, 30, 30, UNLIMITED_MINUTES),
            ),
        )
        assertTrue(HardcoreViolation.BY_DAY_LIMIT in violations(previous, next))
    }

    @Test
    fun `a per-day list shorter than a week falls back to the daily limit for the missing days`() {
        // 확장이 빠진 요일 키를 기본 한도로 채우는 것과 같은 규칙. 옛 빌드가 남긴 짧은 목록이
        // "판정 대상 없음"으로 조용히 통과하면 안 된다.
        val previous = locked(dailyLimitMinutes = 30, byDayMinutes = listOf(30, 30))
        val next = previous.copy(
            limit = previous.limit.copy(dailyLimitMinutes = 30, byDayMinutes = listOf(30, 30, 90)),
        )
        assertTrue(HardcoreViolation.BY_DAY_LIMIT in violations(previous, next))
    }

    @Test
    fun `switching to the per-day mode is refused when it would raise the real limit`() {
        // 값은 하나도 안 건드리고 모드만 넘기는 우회. 확장에는 없는 안드로이드 전용 항목이다
        // (설정 화면에 모드 칩이 있어서 그 자리가 곧 우회로가 된다).
        val previous = locked(
            dailyLimitMinutes = 15,
            byDayMinutes = List(7) { 120 },
            frequency = LimitFrequency.DAILY,
        )
        val next = previous.copy(limit = previous.limit.copy(frequency = LimitFrequency.BY_DAY))
        assertEquals(listOf(HardcoreViolation.LIMIT_MODE), violations(previous, next))
    }

    @Test
    fun `switching modes is allowed when every day ends up tighter`() {
        val previous = locked(
            dailyLimitMinutes = 60,
            byDayMinutes = List(7) { 60 },
            frequency = LimitFrequency.DAILY,
        )
        val next = previous.copy(
            limit = previous.limit.copy(byDayMinutes = List(7) { 20 }, frequency = LimitFrequency.BY_DAY),
        )
        // 요일별 값이 내려갔으므로 BY_DAY_LIMIT도, 실제 한도가 줄었으므로 LIMIT_MODE도 걸리지 않는다.
        assertEquals(emptyList<HardcoreViolation>(), violations(previous, next))
    }

    @Test
    fun `raising the emergency allowance is refused, lowering it is not`() {
        val previous = locked(emergencyAllowance = 3)
        assertEquals(
            listOf(HardcoreViolation.EMERGENCY),
            violations(previous, previous.copy(emergencyAllowance = 4)),
        )
        assertEquals(
            emptyList<HardcoreViolation>(),
            violations(previous, previous.copy(emergencyAllowance = 2)),
        )
    }

    @Test
    fun `shortening the emergency reset period is refused, lengthening it is not`() {
        // monthly < weekly < daily 순으로 느슨하다 — 자주 리셋될수록 총 허용 횟수가 늘어난다.
        val monthly = locked(emergencyResetFrequency = EmergencyResetFrequency.MONTHLY)
        assertEquals(
            listOf(HardcoreViolation.EMERGENCY),
            violations(monthly, monthly.copy(emergencyResetFrequency = EmergencyResetFrequency.WEEKLY)),
        )
        assertEquals(
            listOf(HardcoreViolation.EMERGENCY),
            violations(monthly, monthly.copy(emergencyResetFrequency = EmergencyResetFrequency.DAILY)),
        )

        val daily = locked(emergencyResetFrequency = EmergencyResetFrequency.DAILY)
        assertEquals(
            emptyList<HardcoreViolation>(),
            violations(daily, daily.copy(emergencyResetFrequency = EmergencyResetFrequency.MONTHLY)),
        )
    }

    @Test
    fun `deleting or disabling a scheduled block is refused`() {
        val previous = locked(scheduleWindows = listOf(window(id = "a")))
        assertEquals(
            listOf(HardcoreViolation.SCHEDULE),
            violations(previous, previous.copy(scheduleWindows = emptyList())),
        )
        assertEquals(
            listOf(HardcoreViolation.SCHEDULE),
            violations(
                previous,
                previous.copy(scheduleWindows = listOf(window(id = "a", enabled = false))),
            ),
        )
    }

    @Test
    fun `shrinking a scheduled block is refused whether by days or by span`() {
        val previous = locked(scheduleWindows = listOf(window(id = "a")))
        val fewerDays = previous.copy(scheduleWindows = listOf(window(id = "a", days = MON_ONLY)))
        assertEquals(listOf(HardcoreViolation.SCHEDULE), violations(previous, fewerDays))

        // 22:00~07:00(9시간)을 23:00~07:00(8시간)으로 줄이는 것.
        val shorterSpan = previous.copy(scheduleWindows = listOf(window(id = "a", startMinute = 23 * 60)))
        assertEquals(listOf(HardcoreViolation.SCHEDULE), violations(previous, shorterSpan))
    }

    @Test
    fun `widening or adding a scheduled block is allowed`() {
        val previous = locked(scheduleWindows = listOf(window(id = "a", days = MON_ONLY)))
        val widened = previous.copy(scheduleWindows = listOf(window(id = "a", days = ALL_DAYS)))
        assertEquals(emptyList<HardcoreViolation>(), violations(previous, widened))

        val added = previous.copy(
            scheduleWindows = listOf(window(id = "a", days = MON_ONLY), window(id = "b")),
        )
        assertEquals(emptyList<HardcoreViolation>(), violations(previous, added))
    }

    @Test
    fun `a wrapping window's blocked span is measured across midnight`() {
        // 22:00~07:00은 wrap을 펴면 540분이다. 09:00~17:00(480분)으로 바꾸면 시각만 보면
        // "늦게 시작해 늦게 끝난다"지만 실제 막는 분량은 줄어들므로 거부돼야 한다.
        val previous = locked(scheduleWindows = listOf(window(id = "a", startMinute = 22 * 60, endMinute = 7 * 60)))
        val next = previous.copy(
            scheduleWindows = listOf(window(id = "a", startMinute = 9 * 60, endMinute = 17 * 60)),
        )
        assertEquals(listOf(HardcoreViolation.SCHEDULE), violations(previous, next))
    }

    @Test
    fun `requesting the hardcore turn-off is never blocked here`() {
        // 해제는 1시간 쿨다운이라는 별개 관문이 담당한다. 여기서 또 막으면 요청 자체를 저장할 수 없다.
        val previous = locked()
        assertEquals(
            emptyList<HardcoreViolation>(),
            violations(previous, previous.copy(hardcoreDisableRequestedAt = 1_700_000_000_000L)),
        )
        assertEquals(
            emptyList<HardcoreViolation>(),
            violations(previous, previous.copy(hardcoreMode = false)),
        )
    }

    @Test
    fun `several weakenings in one save are all reported`() {
        val previous = locked(
            dailyLimitMinutes = 30,
            emergencyAllowance = 1,
            scheduleWindows = listOf(window(id = "a")),
        )
        val next = previous.copy(
            limit = previous.limit.copy(dailyLimitMinutes = 120),
            emergencyAllowance = 5,
            scheduleWindows = emptyList(),
        )
        assertEquals(
            listOf(HardcoreViolation.DAILY_LIMIT, HardcoreViolation.EMERGENCY, HardcoreViolation.SCHEDULE),
            violations(previous, next),
        )
    }

    @Test
    fun `the allowed flag and the violation list always agree`() {
        val previous = locked(dailyLimitMinutes = 30)
        val weakening = isHardcoreChangeAllowed(
            previous,
            previous.copy(limit = previous.limit.copy(dailyLimitMinutes = 120)),
        )
        assertFalse(weakening.allowed)
        assertTrue(weakening.violations.isNotEmpty())

        val tightening = isHardcoreChangeAllowed(
            previous,
            previous.copy(limit = previous.limit.copy(dailyLimitMinutes = 10)),
        )
        assertTrue(tightening.allowed)
        assertTrue(tightening.violations.isEmpty())
    }

    @Test
    fun `every violation names a settings column this client actually owns`() {
        // 거부 사유가 브라우저 전용 컬럼을 가리키면 안 된다 — 안드로이드는 whitelist /
        // always_block_shorts / shorts_limit_ms를 읽지도 쓰지도 않는다.
        val owned = com.tubelimiter.app.sync.SETTINGS_COLUMNS
        HardcoreViolation.entries.forEach { violation ->
            assertTrue(violation.name, violation.column in owned)
        }
    }
}
