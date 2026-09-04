import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  STREAK_MILESTONES,
  PERFECT_MILESTONES,
  milestoneAchievementKey,
  perfectAchievementKey,
  milestoneXpBonus,
  unusedTimeXpBonus,
  getLevelTier,
  getLevelProgress,
  isDaySuccess,
  isPerfectDay,
  applyDayRollover
} from '../src/lib/gamification.js';

// Cross-checked against android/app/src/test/java/com/tubelimiter/app/gamification/GamificationTest.kt
// for equivalent scenarios (streak increment/reset, milestone XP, level thresholds).
//
// applyDayRollover takes a supabase client as a dependency-injected argument specifically so it
// is testable (see documents/PLATFORM_PLAN.md). It is exercised below against a minimal stub that
// implements only the chained calls the function actually makes
// (.from().select().eq().maybeSingle() and .from().upsert()) — not a real Supabase client shape.

const MIN_MS = 60 * 1000;
const HOUR_MS = 60 * MIN_MS;

function makeSupabaseStub({ existingStreak = null, achievementsUpsertError = null } = {}) {
  const calls = { streaksUpsert: [], achievementsUpsert: [] };
  const supabase = {
    from(table) {
      if (table === 'streaks') {
        return {
          select() {
            return this;
          },
          eq() {
            return this;
          },
          async maybeSingle() {
            return { data: existingStreak };
          },
          async upsert(row) {
            calls.streaksUpsert.push(row);
            return { error: null };
          }
        };
      }
      if (table === 'achievements') {
        return {
          async upsert(rows, opts) {
            calls.achievementsUpsert.push({ rows, opts });
            return { error: achievementsUpsertError };
          }
        };
      }
      throw new Error(`unexpected table in stub: ${table}`);
    }
  };
  return { supabase, calls };
}

// ---- pure calculation logic ----

test('milestoneAchievementKey matches the expected format', () => {
  assert.equal(milestoneAchievementKey(30), 'streak_30');
});

test('perfectAchievementKey is a separate badge namespace from streak milestones', () => {
  assert.equal(perfectAchievementKey(30), 'perfect_30');
});

test('milestoneXpBonus is 5 XP per streak day', () => {
  assert.equal(milestoneXpBonus(3), 15);
  assert.equal(milestoneXpBonus(365), 1825);
});

test('unusedTimeXpBonus pays 1 XP per 10 unused minutes against the daily limit', () => {
  assert.equal(unusedTimeXpBonus(30 * MIN_MS, 60 * MIN_MS), 3);
});

test('unusedTimeXpBonus floors to zero once usage meets or exceeds the limit', () => {
  assert.equal(unusedTimeXpBonus(90 * MIN_MS, 60 * MIN_MS), 0);
});

test('unusedTimeXpBonus scores an unlimited day against a 24h base', () => {
  // 24h minus 10h leaves 14h unused: 840 minutes / 10 = 84 XP. Same figure as the Kotlin suite.
  assert.equal(unusedTimeXpBonus(10 * HOUR_MS, Infinity), 84);
});

test('isDaySuccess is always true when the limit is unlimited', () => {
  assert.equal(isDaySuccess(600 * HOUR_MS, Infinity), true);
});

test('isDaySuccess forgives up to one tracking tick past the limit', () => {
  // 사용량은 usageTick(1분) 단위로 정산되므로 한도를 넘는 순간이 든 구간은 통째로 기록된 뒤에야
  // 차단이 걸린다. 즉 "한도까지 보고 차단당한 날"은 측정 granularity 때문에 한도를 조금 넘긴
  // 상태로 남는데, 그걸 실패로 치면 안 된다.
  assert.equal(isDaySuccess(60 * MIN_MS, 60 * MIN_MS), true);
  assert.equal(isDaySuccess(61 * MIN_MS, 60 * MIN_MS), true);
  assert.equal(isDaySuccess(61 * MIN_MS + 1, 60 * MIN_MS), false);
});

test('isDaySuccess subtracts emergency-viewing time before comparing to the limit', () => {
  // 30분 한도 + 긴급 시청 5분 = usage_history엔 35분이 쌓이지만, 긴급 시청분은 스트릭을 안 끊는다.
  assert.equal(isDaySuccess(35 * MIN_MS, 30 * MIN_MS, 5 * MIN_MS), true);
  // 긴급 시청분을 빼고도 한도(+1틱)를 넘겼으면 그건 진짜 초과라 실패.
  assert.equal(isDaySuccess(38 * MIN_MS, 30 * MIN_MS, 5 * MIN_MS), false);
  // 긴급 시청 시간이 보고되지 않으면 예전 규칙 그대로.
  assert.equal(isDaySuccess(35 * MIN_MS, 30 * MIN_MS), false);
});

test('isPerfectDay requires a successful day with no emergency use at all', () => {
  assert.equal(isPerfectDay(20 * MIN_MS, 30 * MIN_MS), true);
  // 긴급 시청으로 넘긴 날은 성공이지만 완벽하진 않다.
  assert.equal(isPerfectDay(35 * MIN_MS, 30 * MIN_MS, 5 * MIN_MS, 1), false);
  // 발급만 받고 실제로 안 본 날(ms=0)도 완벽한 날에서는 빠진다.
  assert.equal(isPerfectDay(20 * MIN_MS, 30 * MIN_MS, 0, 1), false);
  // 한도를 진짜로 초과한 날은 애초에 성공이 아니라 완벽할 수도 없다.
  assert.equal(isPerfectDay(90 * MIN_MS, 30 * MIN_MS), false);
});

test('getLevelProgress follows the triangular thresholds', () => {
  assert.equal(getLevelProgress(0).level, 1);
  assert.equal(getLevelProgress(99).level, 1);
  assert.equal(getLevelProgress(100).level, 2);
  assert.equal(getLevelProgress(300).level, 3);

  const progress = getLevelProgress(150);
  assert.equal(progress.level, 2);
  assert.equal(progress.xpIntoLevel, 50);
  assert.equal(progress.xpForNextLevel, 200);
});

test('getLevelTier steps up with level and never falls off the end', () => {
  assert.equal(getLevelTier(1).key, 'seed');
  assert.equal(getLevelTier(5).key, 'trainee');
  assert.equal(getLevelTier(999).key, 'legend');
  // Below the lowest tier's minimum, .find() returns nothing and the code falls back to the
  // last entry in LEVEL_TIERS, which is also the 'seed' tier — not a special-cased default.
  assert.equal(getLevelTier(0).key, 'seed');
});

// ---- applyDayRollover (DB calls stubbed) ----

test('applyDayRollover: staying inside the limit is a success and extends the streak', async () => {
  const existingStreak = {
    user_id: 'u1',
    current_streak: 4,
    best_streak: 4,
    last_result_date: '2026-09-01',
    total_success_days: 4,
    xp: 0,
    perfect_days: 4,
    current_perfect_streak: 4,
    best_perfect_streak: 4
  };
  const { supabase, calls } = makeSupabaseStub({ existingStreak });

  const result = await applyDayRollover(supabase, 'u1', {
    date: '2026-09-02',
    usageMs: 30 * MIN_MS,
    limitMs: 60 * MIN_MS
  });

  assert.equal(result.success, true);
  assert.equal(result.perfect, true);
  assert.equal(result.streak.current_streak, 5);
  assert.equal(result.streak.best_streak, 5);
  assert.equal(result.streak.total_success_days, 5);
  assert.equal(result.streak.current_perfect_streak, 5);
  assert.equal(result.streak.perfect_days, 5);
  // 30 unused minutes -> 3 XP, a streak-length bonus of 5 (no milestone at day 5),
  // and 10 XP for the perfect day.
  assert.equal(result.streak.xp, 3 + 5 + 10);
  assert.deepEqual(result.unlockedAchievements, []);
  assert.equal(calls.streaksUpsert.length, 1);
  assert.equal(calls.achievementsUpsert.length, 0);
});

test('applyDayRollover: exceeding the limit resets the streak but keeps best and totals', async () => {
  const existingStreak = {
    user_id: 'u1',
    current_streak: 9,
    best_streak: 12,
    last_result_date: '2026-09-01',
    total_success_days: 40,
    xp: 50,
    perfect_days: 30,
    current_perfect_streak: 9,
    best_perfect_streak: 12
  };
  const { supabase } = makeSupabaseStub({ existingStreak });

  const result = await applyDayRollover(supabase, 'u1', {
    date: '2026-09-02',
    usageMs: 90 * MIN_MS,
    limitMs: 60 * MIN_MS
  });

  assert.equal(result.success, false);
  assert.equal(result.streak.current_streak, 0);
  assert.equal(result.streak.best_streak, 12);
  assert.equal(result.streak.total_success_days, 40);
  assert.equal(result.streak.current_perfect_streak, 0);
  assert.equal(result.streak.best_perfect_streak, 12);
  assert.equal(result.streak.perfect_days, 30);
  // Over the limit: no unused-time XP, no streak bonus, no perfect-day bonus.
  assert.equal(result.streak.xp, 50);
});

test('applyDayRollover: an emergency-viewing day keeps the streak but breaks the perfect run', async () => {
  const existingStreak = {
    user_id: 'u1',
    current_streak: 5,
    best_streak: 5,
    last_result_date: '2026-09-01',
    total_success_days: 5,
    xp: 100,
    perfect_days: 5,
    current_perfect_streak: 5,
    best_perfect_streak: 5
  };
  const { supabase } = makeSupabaseStub({ existingStreak });

  // 30분 한도인 날에 긴급 시청으로 10분을 더 봐서 usage_history엔 40분이 쌓인 상태.
  const result = await applyDayRollover(supabase, 'u1', {
    date: '2026-09-02',
    usageMs: 40 * MIN_MS,
    limitMs: 30 * MIN_MS,
    emergencyMs: 10 * MIN_MS,
    emergencyUses: 2
  });

  assert.equal(result.success, true, '긴급 시청은 스트릭을 끊지 않는다');
  assert.equal(result.perfect, false, '대신 완벽한 날은 아니다');
  assert.equal(result.streak.current_streak, 6);
  assert.equal(result.streak.total_success_days, 6);
  assert.equal(result.streak.current_perfect_streak, 0);
  assert.equal(result.streak.best_perfect_streak, 5, '완벽한 날 최고 기록은 보존');
  assert.equal(result.streak.perfect_days, 5, '완벽한 날 누적은 안 늘어남');
  // 한도를 다 쓴 날이라 unused XP는 0, 스트릭 보너스 6만 받고 완벽한 날 보너스는 없다.
  assert.equal(result.streak.xp, 100 + 6);
});

test('applyDayRollover: settling the same day twice changes nothing', async () => {
  const existingStreak = {
    user_id: 'u1',
    current_streak: 3,
    best_streak: 3,
    last_result_date: '2026-09-02',
    total_success_days: 3,
    xp: 10,
    perfect_days: 3,
    current_perfect_streak: 3,
    best_perfect_streak: 3
  };
  const { supabase, calls } = makeSupabaseStub({ existingStreak });

  const result = await applyDayRollover(supabase, 'u1', {
    date: '2026-09-02',
    usageMs: 0,
    limitMs: 60 * MIN_MS
  });

  assert.equal(result.streak, existingStreak, 'should return the same record unchanged, not a copy');
  assert.deepEqual(result.unlockedAchievements, []);
  assert.equal(calls.streaksUpsert.length, 0, 'must not write when the day was already settled');
  assert.equal(calls.achievementsUpsert.length, 0);
});

test('applyDayRollover: hitting a milestone unlocks it once and pays a bonus', async () => {
  const existingStreak = {
    user_id: 'u1',
    current_streak: 2,
    best_streak: 2,
    last_result_date: '2026-09-01',
    total_success_days: 2,
    xp: 0,
    perfect_days: 2,
    current_perfect_streak: 2,
    best_perfect_streak: 2
  };
  const { supabase, calls } = makeSupabaseStub({ existingStreak });
  const limit = 60 * MIN_MS;

  const result = await applyDayRollover(supabase, 'u1', {
    date: '2026-09-02',
    usageMs: limit, // used exactly the limit -> zero unused-time XP, isolates the milestone math
    limitMs: limit
  });

  assert.equal(result.streak.current_streak, 3);
  // 완벽한 날 스트릭도 3일째지만 PERFECT_MILESTONES의 첫 관문은 7일이라 아직 안 열린다.
  assert.deepEqual(result.unlockedAchievements, ['streak_3']);
  // Streak bonus (3) + milestone bonus (3 * 5 = 15) + perfect day (10), no unused-time XP.
  assert.equal(result.streak.xp, 3 + 15 + 10);
  assert.equal(calls.achievementsUpsert.length, 1);
  assert.equal(calls.achievementsUpsert[0].rows[0].key, 'streak_3');
  assert.deepEqual(calls.achievementsUpsert[0].opts, { onConflict: 'user_id,key', ignoreDuplicates: true });
});

test('applyDayRollover: a perfect run milestone unlocks its own badge', async () => {
  const existingStreak = {
    user_id: 'u1',
    current_streak: 9, // 다음 날은 10일차 - 스트릭 마일스톤은 안 걸리고 완벽한 날 쪽만 걸린다
    best_streak: 9,
    last_result_date: '2026-09-01',
    total_success_days: 9,
    xp: 0,
    perfect_days: 6,
    current_perfect_streak: 6,
    best_perfect_streak: 6
  };
  const { supabase, calls } = makeSupabaseStub({ existingStreak });
  const limit = 60 * MIN_MS;

  const result = await applyDayRollover(supabase, 'u1', {
    date: '2026-09-02',
    usageMs: limit,
    limitMs: limit
  });

  assert.equal(result.streak.current_perfect_streak, 7);
  assert.deepEqual(result.unlockedAchievements, ['perfect_7']);
  // 스트릭 보너스 10 + 완벽한 날 10 + 완벽 마일스톤 7 * 5 = 35.
  assert.equal(result.streak.xp, 10 + 10 + 35);
  assert.equal(calls.achievementsUpsert[0].rows[0].key, 'perfect_7');
});

test('applyDayRollover: an achievements upsert failure withholds the unlocked-achievement report', async () => {
  const existingStreak = {
    user_id: 'u1',
    current_streak: 2,
    best_streak: 2,
    last_result_date: '2026-09-01',
    total_success_days: 2,
    xp: 0,
    perfect_days: 2,
    current_perfect_streak: 2,
    best_perfect_streak: 2
  };
  const { supabase } = makeSupabaseStub({ existingStreak, achievementsUpsertError: new Error('boom') });
  const limit = 60 * MIN_MS;

  const result = await applyDayRollover(supabase, 'u1', { date: '2026-09-02', usageMs: limit, limitMs: limit });

  // The streak itself still advances and still earns the XP...
  assert.equal(result.streak.current_streak, 3);
  assert.equal(result.streak.xp, 3 + 15 + 10);
  // ...but the achievement is not reported as unlocked since the write failed.
  assert.deepEqual(result.unlockedAchievements, []);
});

test('applyDayRollover: a non-milestone day pays only the unused-time, streak and perfect bonus', async () => {
  const existingStreak = {
    user_id: 'u1',
    current_streak: 0,
    best_streak: 0,
    last_result_date: '2026-09-01',
    total_success_days: 0,
    xp: 100,
    perfect_days: 0,
    current_perfect_streak: 0,
    best_perfect_streak: 0
  };
  const { supabase } = makeSupabaseStub({ existingStreak });

  const result = await applyDayRollover(supabase, 'u1', {
    date: '2026-09-02',
    usageMs: 20 * MIN_MS,
    limitMs: 60 * MIN_MS
  });

  // 20 minutes used of 60 leaves 40 unused: 4 XP, plus 1 for the (now length-1) streak,
  // plus 10 for the perfect day.
  assert.equal(result.streak.xp, 100 + 4 + 1 + 10);
  assert.deepEqual(result.unlockedAchievements, []);
});

test('applyDayRollover: with no existing streak row, a fresh default record is used', async () => {
  const { supabase, calls } = makeSupabaseStub({ existingStreak: null });

  const result = await applyDayRollover(supabase, 'u1', {
    date: '2026-09-02',
    usageMs: 10 * MIN_MS,
    limitMs: 60 * MIN_MS
  });

  assert.equal(result.success, true);
  assert.equal(result.streak.current_streak, 1);
  assert.equal(result.streak.best_streak, 1);
  assert.equal(result.streak.total_success_days, 1);
  assert.equal(result.streak.current_perfect_streak, 1);
  assert.equal(result.streak.perfect_days, 1);
  assert.equal(calls.streaksUpsert.length, 1);
});

test('applyDayRollover: a row from before the perfect-day columns existed still rolls over', async () => {
  // 마이그레이션 전에 만들어진 행에는 perfect_* 값이 없다(undefined) - 0으로 취급해야 한다.
  const existingStreak = {
    user_id: 'u1',
    current_streak: 2,
    best_streak: 2,
    last_result_date: '2026-09-01',
    total_success_days: 2,
    xp: 0
  };
  const { supabase } = makeSupabaseStub({ existingStreak });

  const result = await applyDayRollover(supabase, 'u1', {
    date: '2026-09-02',
    usageMs: 10 * MIN_MS,
    limitMs: 60 * MIN_MS
  });

  assert.equal(result.streak.current_perfect_streak, 1);
  assert.equal(result.streak.best_perfect_streak, 1);
  assert.equal(result.streak.perfect_days, 1);
});

test('STREAK_MILESTONES matches the documented milestone days', () => {
  assert.deepEqual(STREAK_MILESTONES, [3, 7, 14, 30, 60, 100, 365]);
});

test('PERFECT_MILESTONES matches the documented perfect-run days', () => {
  assert.deepEqual(PERFECT_MILESTONES, [7, 30, 100]);
});
