package com.tubelimiter.app.gamification

import com.tubelimiter.app.limit.UNLIMITED_MILLIS
import com.tubelimiter.app.limit.minutesToMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GamificationTest {

    private val limit = minutesToMillis(60)

    @Test
    fun `staying inside the limit is a success and extends the streak`() {
        val previous = StreakRecord(currentStreak = 4, bestStreak = 4, totalSuccessDays = 4)
        val result = applyDayRollover(previous, "2026-09-02", minutesToMillis(30), limit)

        assertTrue(result.success)
        assertEquals(5, result.record.currentStreak)
        assertEquals(5, result.record.bestStreak)
        assertEquals(5, result.record.totalSuccessDays)
    }

    @Test
    fun `exceeding the limit resets the streak but keeps best and totals`() {
        val previous = StreakRecord(currentStreak = 9, bestStreak = 12, totalSuccessDays = 40)
        val result = applyDayRollover(previous, "2026-09-02", minutesToMillis(90), limit)

        assertFalse(result.success)
        assertEquals(0, result.record.currentStreak)
        assertEquals(12, result.record.bestStreak)
        assertEquals(40, result.record.totalSuccessDays)
    }

    @Test
    fun `settling the same day twice changes nothing`() {
        val previous = StreakRecord(currentStreak = 3, lastResultDate = "2026-09-02")
        val result = applyDayRollover(previous, "2026-09-02", 0L, limit)
        assertSame(previous, result.record)
        assertTrue(result.unlockedMilestones.isEmpty())
    }

    @Test
    fun `hitting a milestone unlocks it once and pays a bonus`() {
        val previous = StreakRecord(currentStreak = 2, xp = 0)
        val result = applyDayRollover(previous, "2026-09-02", limit, limit)

        assertEquals(listOf(3), result.unlockedMilestones)
        assertEquals(setOf("streak_3"), result.unlockedKeys)
        // Nothing unused, so XP is the streak bonus (3), the milestone bonus (3 * 5) and the
        // perfect-day bonus (10) — no emergency pass was spent.
        assertEquals(3 + 15 + 10, result.record.xp)
    }

    @Test
    fun `a day spent on an emergency pass keeps the streak but breaks the perfect run`() {
        val previous = StreakRecord(
            currentStreak = 5,
            bestStreak = 5,
            totalSuccessDays = 5,
            xp = 100,
            perfectDays = 5,
            currentPerfectStreak = 5,
            bestPerfectStreak = 5,
        )
        // 60분 한도인 날에 긴급 시청으로 10분을 더 봐서 총 사용량은 70분.
        val result = applyDayRollover(
            previous = previous,
            dateKey = "2026-09-02",
            usedMillis = minutesToMillis(70),
            limitMillis = limit,
            emergencyMillis = minutesToMillis(10),
            emergencyUses = 2,
        )

        assertTrue(result.success)
        assertFalse(result.perfect)
        assertEquals(6, result.record.currentStreak)
        assertEquals(6, result.record.totalSuccessDays)
        assertEquals(0, result.record.currentPerfectStreak)
        assertEquals(5, result.record.bestPerfectStreak)
        assertEquals(5, result.record.perfectDays)
        // Streak bonus only: the limit was fully used, and a perfect day it was not.
        assertEquals(100 + 6, result.record.xp)
    }

    @Test
    fun `a perfect run milestone unlocks its own badge`() {
        // Day 10 of the streak is not a streak milestone, isolating the perfect-run one.
        val previous = StreakRecord(currentStreak = 9, perfectDays = 6, currentPerfectStreak = 6)
        val result = applyDayRollover(previous, "2026-09-02", limit, limit)

        assertEquals(7, result.record.currentPerfectStreak)
        assertEquals(listOf(7), result.unlockedPerfectMilestones)
        assertEquals(setOf("perfect_7"), result.unlockedKeys)
        // Streak bonus 10 + perfect day 10 + perfect milestone 7 * 5.
        assertEquals(10 + 10 + 35, result.record.xp)
    }

    @Test
    fun `emergency time is taken back out before the limit comparison`() {
        // 60분 한도 + 긴급 시청 5분 = 65분이 쌓여도 스트릭은 안 끊긴다.
        assertTrue(isDaySuccess(minutesToMillis(65), limit, minutesToMillis(5)))
        // 긴급분을 빼고도 한도(+1틱)를 넘으면 진짜 초과.
        assertFalse(isDaySuccess(minutesToMillis(70), limit, minutesToMillis(5)))
        // 보고된 긴급 시간이 없으면 예전 규칙 그대로.
        assertFalse(isDaySuccess(minutesToMillis(65), limit))
    }

    @Test
    fun `one tracking tick past the limit is forgiven`() {
        assertTrue(isDaySuccess(limit, limit))
        assertTrue(isDaySuccess(limit + 60_000L, limit))
        assertFalse(isDaySuccess(limit + 60_001L, limit))
    }

    @Test
    fun `a perfect day needs a success with no emergency use at all`() {
        assertTrue(isPerfectDay(minutesToMillis(20), limit))
        assertFalse(isPerfectDay(minutesToMillis(65), limit, minutesToMillis(5), 1))
        // 발급만 받고 안 본 날도 완벽한 날은 아니다.
        assertFalse(isPerfectDay(minutesToMillis(20), limit, 0L, 1))
        assertFalse(isPerfectDay(minutesToMillis(90), limit))
    }

    @Test
    fun `a non-milestone day pays only the unused time, streak and perfect bonus`() {
        val previous = StreakRecord(currentStreak = 0, xp = 100)
        // 20 minutes used of 60 leaves 40 unused: 4 XP, plus 1 for the streak, plus 10 perfect.
        val result = applyDayRollover(previous, "2026-09-02", minutesToMillis(20), limit)

        assertTrue(result.unlockedMilestones.isEmpty())
        assertEquals(100 + 4 + 1 + 10, result.record.xp)
    }

    @Test
    fun `a failed day still pays nothing extra beyond unused time`() {
        val previous = StreakRecord(currentStreak = 5, xp = 50)
        val result = applyDayRollover(previous, "2026-09-02", minutesToMillis(90), limit)
        assertEquals(50, result.record.xp)
    }

    @Test
    fun `an unlimited day always succeeds and scores against a 24h base`() {
        assertTrue(isDaySuccess(minutesToMillis(600), UNLIMITED_MILLIS))
        // 24h minus 10h leaves 14h unused: 840 minutes / 10 = 84 XP.
        assertEquals(84, unusedTimeXpBonus(minutesToMillis(600), UNLIMITED_MILLIS))
    }

    @Test
    fun `levels follow the triangular thresholds`() {
        assertEquals(1, levelProgress(0).level)
        assertEquals(1, levelProgress(99).level)
        assertEquals(2, levelProgress(100).level)
        assertEquals(3, levelProgress(300).level)

        val progress = levelProgress(150)
        assertEquals(2, progress.level)
        assertEquals(50, progress.xpIntoLevel)
        assertEquals(200, progress.xpForNextLevel)
    }

    @Test
    fun `tiers step up with level and never fall off the end`() {
        assertEquals("seed", levelTier(1).key)
        assertEquals("trainee", levelTier(5).key)
        assertEquals("legend", levelTier(999).key)
        assertEquals("seed", levelTier(0).key)
    }

    @Test
    fun `achievement keys match the extension's format`() {
        assertEquals("streak_30", milestoneAchievementKey(30))
        assertEquals("perfect_30", perfectAchievementKey(30))
    }

    @Test
    fun `milestone lists match the extension`() {
        assertEquals(listOf(3, 7, 14, 30, 60, 100, 365), STREAK_MILESTONES)
        assertEquals(listOf(7, 30, 100), PERFECT_MILESTONES)
    }
}
