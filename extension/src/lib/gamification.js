// 자동 스트릭/XP/업적 규칙. 수동 입력 없음 — 그날 사용량이 한도 이내면 "성공".

export const STREAK_MILESTONES = [3, 7, 14, 30, 60, 100, 365];
export const XP_PER_SUCCESS_DAY = 10;

export function milestoneAchievementKey(days) {
  return `streak_${days}`;
}

export function milestoneXpBonus(days) {
  return days * 5;
}

// 레벨업 공식: 레벨 n을 찍으려면 누적 XP가 100 * n(n+1)/2 필요 (삼각수, 뒤로 갈수록 더 필요)
export function getLevelProgress(totalXp) {
  let level = 1;
  while (totalXp >= levelThreshold(level + 1)) {
    level += 1;
  }
  const currentFloor = levelThreshold(level);
  const nextFloor = levelThreshold(level + 1);
  return {
    level,
    xpIntoLevel: totalXp - currentFloor,
    xpForNextLevel: nextFloor - currentFloor
  };
}

function levelThreshold(level) {
  const n = level - 1;
  return 100 * (n * (n + 1)) / 2;
}

export function isDaySuccess(usageMs, limitMs) {
  if (!Number.isFinite(limitMs)) return true; // 무제한 설정이면 항상 성공
  return usageMs <= limitMs;
}

/**
 * 하루가 끝났을 때(자정 롤오버) 스트릭/XP/업적을 갱신한다.
 * 실패 시 스트릭은 0으로 리셋되지만 total_success_days와 best_streak은 보존된다.
 */
export async function applyDayRollover(supabase, userId, { date, usageMs, limitMs }) {
  const success = isDaySuccess(usageMs, limitMs);

  const { data: existing } = await supabase
    .from('streaks')
    .select('*')
    .eq('user_id', userId)
    .maybeSingle();

  const prev = existing || {
    user_id: userId,
    current_streak: 0,
    best_streak: 0,
    last_result_date: null,
    total_success_days: 0,
    xp: 0
  };

  // 같은 날짜를 중복 처리하지 않도록 방어
  if (prev.last_result_date === date) {
    return { success, streak: prev, unlockedAchievements: [] };
  }

  const nextStreak = success ? prev.current_streak + 1 : 0;
  const nextBest = Math.max(prev.best_streak, nextStreak);
  const nextTotalSuccess = prev.total_success_days + (success ? 1 : 0);
  let nextXp = prev.xp + (success ? XP_PER_SUCCESS_DAY : 0);

  const newlyHitMilestones = success
    ? STREAK_MILESTONES.filter((m) => m === nextStreak)
    : [];
  for (const m of newlyHitMilestones) {
    nextXp += milestoneXpBonus(m);
  }

  const updated = {
    user_id: userId,
    current_streak: nextStreak,
    best_streak: nextBest,
    last_result_date: date,
    total_success_days: nextTotalSuccess,
    xp: nextXp
  };

  await supabase.from('streaks').upsert(updated);

  const unlockedAchievements = [];
  if (newlyHitMilestones.length > 0) {
    const rows = newlyHitMilestones.map((m) => ({
      user_id: userId,
      key: milestoneAchievementKey(m),
      unlocked_at: new Date().toISOString()
    }));
    const { error } = await supabase.from('achievements').upsert(rows, { onConflict: 'user_id,key', ignoreDuplicates: true });
    if (!error) unlockedAchievements.push(...newlyHitMilestones);
  }

  return { success, streak: updated, unlockedAchievements };
}
