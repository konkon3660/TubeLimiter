package com.tubelimiter.app.limit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

private val LIMIT = minutesToMillis(60)

class BlockDecisionTest {

    @Test
    fun `an emergency overlap counts only the shared stretch of the window`() {
        val grantedAt = 1_000_000L
        // Fully inside the pass.
        assertEquals(60_000L, emergencyOverlapMillis(grantedAt + 60_000L, grantedAt + 120_000L, grantedAt))
        // Starts before the pass was granted.
        assertEquals(30_000L, emergencyOverlapMillis(grantedAt - 30_000L, grantedAt + 30_000L, grantedAt))
        // Runs past the end of the pass.
        val end = grantedAt + EMERGENCY_DURATION_MILLIS
        assertEquals(60_000L, emergencyOverlapMillis(end - 60_000L, end + 120_000L, grantedAt))
        // Entirely outside it, and with no pass ever granted.
        assertEquals(0L, emergencyOverlapMillis(end + 1L, end + 60_000L, grantedAt))
        assertEquals(0L, emergencyOverlapMillis(grantedAt, grantedAt + 60_000L, null))
    }

    private fun inputs(
        used: Long = 0L,
        limit: Long = LIMIT,
        emergency: Boolean = false,
        focus: Boolean = false,
        schedule: Boolean = false,
        manual: Boolean = false,
    ) = BlockInputs(used, limit, emergency, focus, schedule, manual)

    @Test
    fun `under the limit is not blocked`() {
        assertNull(inputs(used = minutesToMillis(59)).blockReason())
    }

    @Test
    fun `exactly at the limit blocks`() {
        assertEquals(BlockReason.USAGE_LIMIT, inputs(used = LIMIT).blockReason())
    }

    @Test
    fun `an unlimited day never blocks on usage`() {
        assertNull(inputs(used = minutesToMillis(600), limit = UNLIMITED_MILLIS).blockReason())
    }

    @Test
    fun `emergency pass beats a manual block and the limit`() {
        val state = inputs(used = LIMIT, emergency = true, manual = true)
        assertNull(state.blockReason())
    }

    @Test
    fun `focus mode outranks the emergency bypass`() {
        // Focus mode blocks "regardless of the limit" by design - an emergency pass must not
        // be able to cut it short, even if one happened to already be active (e.g. focus mode
        // was started mid-emergency-window).
        assertEquals(BlockReason.FOCUS_MODE, inputs(used = 0L, focus = true, emergency = true, manual = true).blockReason())
    }

    @Test
    fun `a scheduled block outranks the emergency bypass and a manual block`() {
        assertEquals(
            BlockReason.SCHEDULED,
            inputs(used = 0L, schedule = true, emergency = true, manual = true).blockReason(),
        )
    }

    @Test
    fun `focus mode still wins the displayed reason when a schedule window is also active`() {
        // Same tier by design (both outrank emergency), but focus mode keeps the message
        // priority when both happen to be true at once.
        assertEquals(BlockReason.FOCUS_MODE, inputs(used = 0L, focus = true, schedule = true).blockReason())
    }

    @Test
    fun `focus mode outranks a manual block and the limit`() {
        assertEquals(BlockReason.FOCUS_MODE, inputs(used = LIMIT, focus = true, manual = true).blockReason())
    }

    @Test
    fun `manual block outranks the limit`() {
        assertEquals(BlockReason.MANUAL, inputs(used = LIMIT, manual = true).blockReason())
    }

    @Test
    fun `focus mode blocks well under the limit`() {
        assertEquals(BlockReason.FOCUS_MODE, inputs(used = 0L, focus = true).blockReason())
    }

    @Test
    fun `a scheduled block blocks well under the limit`() {
        assertEquals(BlockReason.SCHEDULED, inputs(used = 0L, schedule = true).blockReason())
    }

    @Test
    fun `a scheduled block outranks a manual block`() {
        assertEquals(BlockReason.SCHEDULED, inputs(used = 0L, schedule = true, manual = true).blockReason())
    }

    @Test
    fun `emergency allowance buckets by the chosen period`() {
        val wednesday = LocalDate.of(2026, 9, 2)
        val thursday = LocalDate.of(2026, 9, 3)

        assertEquals("2026-09-02", emergencyResetKey(EmergencyResetFrequency.DAILY, wednesday))
        // Both weekdays sit in the same Monday-started week, so the bucket is unchanged.
        assertEquals(
            emergencyResetKey(EmergencyResetFrequency.WEEKLY, wednesday),
            emergencyResetKey(EmergencyResetFrequency.WEEKLY, thursday),
        )
        assertEquals("2026-08-31", emergencyResetKey(EmergencyResetFrequency.WEEKLY, wednesday))
        assertEquals("2026-09-01", emergencyResetKey(EmergencyResetFrequency.MONTHLY, wednesday))
    }

    @Test
    fun `the bucket start date is what the reset key is built from`() {
        val wednesday = LocalDate.of(2026, 9, 2)
        EmergencyResetFrequency.entries.forEach { frequency ->
            assertEquals(
                emergencyResetKey(frequency, wednesday),
                emergencyBucketStartDate(frequency, wednesday).toString(),
            )
        }
    }

    @Test
    fun `a weekly bucket rolls over between Sunday and Monday`() {
        val sunday = LocalDate.of(2026, 9, 6)
        val monday = LocalDate.of(2026, 9, 7)
        // 일요일은 아직 지난 월요일에 시작한 버킷 안이고, 월요일부터 새 버킷이다.
        assertEquals(LocalDate.of(2026, 8, 31), emergencyBucketStartDate(EmergencyResetFrequency.WEEKLY, sunday))
        assertEquals(monday, emergencyBucketStartDate(EmergencyResetFrequency.WEEKLY, monday))
    }

    @Test
    fun `a monthly bucket rolls over between the last and the first`() {
        val lastOfAugust = LocalDate.of(2026, 8, 31)
        val firstOfSeptember = LocalDate.of(2026, 9, 1)
        assertEquals(
            LocalDate.of(2026, 8, 1),
            emergencyBucketStartDate(EmergencyResetFrequency.MONTHLY, lastOfAugust),
        )
        assertEquals(
            firstOfSeptember,
            emergencyBucketStartDate(EmergencyResetFrequency.MONTHLY, firstOfSeptember),
        )
    }

    @Test
    fun `bucket date keys run from the bucket start through today`() {
        val wednesday = LocalDate.of(2026, 9, 2)

        // 일간이면 오늘 하나 - 서버 행도 하나만 보면 된다.
        assertEquals(listOf("2026-09-02"), emergencyBucketDateKeys(EmergencyResetFrequency.DAILY, wednesday))
        assertEquals(
            listOf("2026-08-31", "2026-09-01", "2026-09-02"),
            emergencyBucketDateKeys(EmergencyResetFrequency.WEEKLY, wednesday),
        )
        assertEquals(
            listOf("2026-09-01", "2026-09-02"),
            emergencyBucketDateKeys(EmergencyResetFrequency.MONTHLY, wednesday),
        )
    }

    @Test
    fun `the first day of a bucket lists only itself`() {
        val monday = LocalDate.of(2026, 9, 7)
        assertEquals(listOf("2026-09-07"), emergencyBucketDateKeys(EmergencyResetFrequency.WEEKLY, monday))
        val firstOfSeptember = LocalDate.of(2026, 9, 1)
        assertEquals(listOf("2026-09-01"), emergencyBucketDateKeys(EmergencyResetFrequency.MONTHLY, firstOfSeptember))
    }

    @Test
    fun `remaining uses fall back to the local count when nothing came from the server`() {
        // 오프라인/로그아웃: 다른 기기 몫이 0이라 기존 로컬 전용 동작 그대로여야 한다.
        assertEquals(3, effectiveEmergencyRemaining(localRemaining = null, allowance = 3, otherDeviceUses = 0))
        assertEquals(1, effectiveEmergencyRemaining(localRemaining = 1, allowance = 3, otherDeviceUses = 0))
    }

    @Test
    fun `other devices' uses come off the remaining count`() {
        assertEquals(1, effectiveEmergencyRemaining(localRemaining = 3, allowance = 3, otherDeviceUses = 2))
        // PC에서 3회를 다 쓴 뒤 폰에서 처음 여는 상황 - 로컬은 3회가 남았다고 보지만 실제로는 0회.
        assertEquals(0, effectiveEmergencyRemaining(localRemaining = null, allowance = 3, otherDeviceUses = 3))
    }

    @Test
    fun `remaining uses never go negative`() {
        assertEquals(0, effectiveEmergencyRemaining(localRemaining = 1, allowance = 3, otherDeviceUses = 5))
    }

    @Test
    fun `hardcore stays on until the cooldown elapses`() {
        val requestedAt = 1_000_000L
        assertEquals(false, shouldDisableHardcore(requestedAt, requestedAt))
        assertEquals(
            false,
            shouldDisableHardcore(requestedAt, requestedAt + HARDCORE_DISABLE_COOLDOWN_MILLIS - 1),
        )
        assertTrue(shouldDisableHardcore(requestedAt, requestedAt + HARDCORE_DISABLE_COOLDOWN_MILLIS))
    }

    @Test
    fun `no pending request means no cooldown`() {
        assertEquals(false, shouldDisableHardcore(null, 5_000L))
        assertEquals(0L, hardcoreCooldownRemainingMillis(null, 5_000L))
    }

    @Test
    fun `no stop request means the natural end time wins unchanged`() {
        assertEquals(5_000L, resolveFocusStopTime(5_000L, null))
        assertNull(resolveFocusStopTime(null, null))
    }

    @Test
    fun `a stop request with no natural end resolves to the cooldown end`() {
        assertEquals(
            1_000L + FOCUS_STOP_COOLDOWN_MILLIS,
            resolveFocusStopTime(null, 1_000L, FOCUS_STOP_COOLDOWN_MILLIS),
        )
    }

    @Test
    fun `cooldown end wins when the natural end is further away`() {
        val requestedAt = 1_000_000L
        val naturalEnd = requestedAt + FOCUS_STOP_COOLDOWN_MILLIS + 60_000L
        assertEquals(
            requestedAt + FOCUS_STOP_COOLDOWN_MILLIS,
            resolveFocusStopTime(naturalEnd, requestedAt, FOCUS_STOP_COOLDOWN_MILLIS),
        )
    }

    @Test
    fun `natural end wins when it arrives before the cooldown would`() {
        val requestedAt = 1_000_000L
        val naturalEnd = requestedAt + 60_000L
        assertEquals(naturalEnd, resolveFocusStopTime(naturalEnd, requestedAt, FOCUS_STOP_COOLDOWN_MILLIS))
    }

    @Test
    fun `does not extend a session past its natural end just because a stop is pending`() {
        val requestedAt = 1_000_000L
        val naturalEnd = requestedAt + 1_000L
        val resolved = resolveFocusStopTime(naturalEnd, requestedAt, FOCUS_STOP_COOLDOWN_MILLIS)
        assertTrue(resolved!! <= naturalEnd)
    }

    @Test
    fun `no emergency grant yet means no cooldown`() {
        assertEquals(0L, emergencyGrantCooldownRemainingMillis(null, 5_000L))
    }

    @Test
    fun `emergency grant cooldown counts down and then clears`() {
        val grantedAt = 1_000_000L
        assertEquals(
            EMERGENCY_GRANT_COOLDOWN_MILLIS,
            emergencyGrantCooldownRemainingMillis(grantedAt, grantedAt, EMERGENCY_GRANT_COOLDOWN_MILLIS),
        )
        assertEquals(
            1L,
            emergencyGrantCooldownRemainingMillis(
                grantedAt,
                grantedAt + EMERGENCY_GRANT_COOLDOWN_MILLIS - 1,
                EMERGENCY_GRANT_COOLDOWN_MILLIS,
            ),
        )
        assertEquals(
            0L,
            emergencyGrantCooldownRemainingMillis(
                grantedAt,
                grantedAt + EMERGENCY_GRANT_COOLDOWN_MILLIS,
                EMERGENCY_GRANT_COOLDOWN_MILLIS,
            ),
        )
    }
}
