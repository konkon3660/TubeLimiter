package com.tubelimiter.app.gamification

import com.tubelimiter.app.R
import com.tubelimiter.app.limit.isUnlimited

val STREAK_MILESTONES = listOf(3, 7, 14, 30, 60, 100, 365)

/** Perfect days = limit kept without spending a single emergency pass. */
val PERFECT_MILESTONES = listOf(7, 30, 100)

private const val UNUSED_MINUTES_PER_XP = 10
private const val ONE_DAY_MILLIS = 24 * 60 * 60 * 1000L
private const val PERFECT_DAY_XP = 10

/**
 * Usage is settled a tick at a time, so the stretch that crosses the limit is recorded whole
 * before blocking kicks in — a day watched right up to the block lands slightly over the limit
 * through measurement granularity alone, which must not read as a failure. Mirrors the
 * extension's `TRACKING_TICK_GRACE_MS`.
 */
private const val TRACKING_TICK_GRACE_MILLIS = 60_000L

fun milestoneAchievementKey(days: Int): String = "streak_$days"

fun perfectAchievementKey(days: Int): String = "perfect_$days"

fun milestoneXpBonus(days: Int): Int = days * 5

/** XP for time left unspent that day — 1 XP per 10 unused minutes. */
fun unusedTimeXpBonus(usedMillis: Long, limitMillis: Long): Int {
    val dayBase = if (isUnlimited(limitMillis)) ONE_DAY_MILLIS else limitMillis
    val unused = (dayBase - usedMillis).coerceAtLeast(0L)
    return (unused / (UNUSED_MINUTES_PER_XP * 60_000L)).toInt()
}

/** [key] is the extension's stable identifier; [titleRes] is what the dashboard shows. */
data class LevelTier(val key: String, val titleRes: Int, val emoji: String)

private val LEVEL_TIERS = listOf(
    50 to LevelTier("legend", R.string.level_tier_legend, "👑"),
    20 to LevelTier("master", R.string.level_tier_master, "⭐"),
    10 to LevelTier("skilled", R.string.level_tier_skilled, "🔥"),
    5 to LevelTier("trainee", R.string.level_tier_trainee, "🌿"),
    1 to LevelTier("seed", R.string.level_tier_seed, "🌱"),
)

fun levelTier(level: Int): LevelTier =
    LEVEL_TIERS.firstOrNull { level >= it.first }?.second ?: LEVEL_TIERS.last().second

data class LevelProgress(val level: Int, val xpIntoLevel: Int, val xpForNextLevel: Int)

/** Reaching level n costs a cumulative 100 * n(n+1)/2 XP, so each level is dearer than the last. */
private fun levelThreshold(level: Int): Int {
    val n = level - 1
    return 100 * (n * (n + 1)) / 2
}

fun levelProgress(totalXp: Int): LevelProgress {
    var level = 1
    while (totalXp >= levelThreshold(level + 1)) level += 1
    val floor = levelThreshold(level)
    val next = levelThreshold(level + 1)
    return LevelProgress(level, totalXp - floor, next - floor)
}

/**
 * Time watched on an emergency pass ([emergencyMillis]) is left in the day's total usage but taken
 * back out here: an emergency pass is an allowance the user spends, not a broken limit, so ending
 * a streak over it would punish the same minute twice. Such a day instead loses only its
 * [isPerfectDay] standing — see the extension's `isDaySuccess` for the shared rule.
 */
fun isDaySuccess(usedMillis: Long, limitMillis: Long, emergencyMillis: Long = 0L): Boolean {
    if (isUnlimited(limitMillis)) return true
    val ownUsage = (usedMillis - emergencyMillis.coerceAtLeast(0L)).coerceAtLeast(0L)
    return ownUsage <= limitMillis + TRACKING_TICK_GRACE_MILLIS
}

/** A successful day that spent no emergency pass at all — the perfect-day badge/count basis. */
fun isPerfectDay(
    usedMillis: Long,
    limitMillis: Long,
    emergencyMillis: Long = 0L,
    emergencyUses: Int = 0,
): Boolean = isDaySuccess(usedMillis, limitMillis, emergencyMillis) &&
    emergencyMillis <= 0L &&
    emergencyUses <= 0

data class StreakRecord(
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val lastResultDate: String? = null,
    val totalSuccessDays: Int = 0,
    val xp: Int = 0,
    val perfectDays: Int = 0,
    val currentPerfectStreak: Int = 0,
    val bestPerfectStreak: Int = 0,
)

data class RolloverResult(
    val success: Boolean,
    val perfect: Boolean,
    val record: StreakRecord,
    val unlockedMilestones: List<Int>,
    val unlockedPerfectMilestones: List<Int> = emptyList(),
) {
    /** Achievement keys for everything unlocked this rollover, ready for storage/sync. */
    val unlockedKeys: Set<String>
        get() = (
            unlockedMilestones.map(::milestoneAchievementKey) +
                unlockedPerfectMilestones.map(::perfectAchievementKey)
            ).toSet()
}

/**
 * Settles one finished day. A failed day resets the running streak but keeps
 * [StreakRecord.bestStreak] and [StreakRecord.totalSuccessDays]. A day that spent an emergency
 * pass still counts as a success — only the perfect run ([StreakRecord.currentPerfectStreak])
 * breaks.
 *
 * Pure port of the extension's `applyDayRollover` — the caller persists the result.
 */
fun applyDayRollover(
    previous: StreakRecord,
    dateKey: String,
    usedMillis: Long,
    limitMillis: Long,
    emergencyMillis: Long = 0L,
    emergencyUses: Int = 0,
): RolloverResult {
    val success = isDaySuccess(usedMillis, limitMillis, emergencyMillis)
    val perfect = isPerfectDay(usedMillis, limitMillis, emergencyMillis, emergencyUses)

    // Guard against settling the same day twice (overlapping ticks around the rollover).
    if (previous.lastResultDate == dateKey) {
        return RolloverResult(success, perfect, previous, emptyList())
    }

    val nextStreak = if (success) previous.currentStreak + 1 else 0
    val nextPerfectStreak = if (perfect) previous.currentPerfectStreak + 1 else 0
    val milestones = if (success) STREAK_MILESTONES.filter { it == nextStreak } else emptyList()
    val perfectMilestones =
        if (perfect) PERFECT_MILESTONES.filter { it == nextPerfectStreak } else emptyList()

    val gainedXp = unusedTimeXpBonus(usedMillis, limitMillis) +
        (if (success) nextStreak else 0) +
        (if (perfect) PERFECT_DAY_XP else 0) +
        milestones.sumOf { milestoneXpBonus(it) } +
        perfectMilestones.sumOf { milestoneXpBonus(it) }

    val record = StreakRecord(
        currentStreak = nextStreak,
        bestStreak = maxOf(previous.bestStreak, nextStreak),
        lastResultDate = dateKey,
        totalSuccessDays = previous.totalSuccessDays + if (success) 1 else 0,
        xp = previous.xp + gainedXp,
        perfectDays = previous.perfectDays + if (perfect) 1 else 0,
        currentPerfectStreak = nextPerfectStreak,
        bestPerfectStreak = maxOf(previous.bestPerfectStreak, nextPerfectStreak),
    )
    return RolloverResult(success, perfect, record, milestones, perfectMilestones)
}
