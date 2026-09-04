// 자동 스트릭/XP/업적 규칙. 수동 입력 없음 — 그날 사용량이 한도 이내면 "성공".

export const STREAK_MILESTONES = [3, 7, 14, 30, 60, 100, 365];
// 완벽한 날(= 긴급 시청을 한 번도 안 쓰고 한도를 지킨 날) 연속 기록 뱃지
export const PERFECT_MILESTONES = [7, 30, 100];
// 유튜브 안 쓴 시간 10분당 1XP
const UNUSED_MINUTES_PER_XP = 10;
const ONE_DAY_MS = 24 * 60 * 60 * 1000;
// 완벽한 날 하루당 추가 XP
const PERFECT_DAY_XP = 10;

// 사용량은 usageTick(1분) 단위로 정산되므로, 한도를 넘는 순간이 포함된 마지막 구간은 통째로
// 기록된 뒤에야 차단이 걸린다. 즉 "한도까지 보고 차단당한 날"도 한도를 최대 1틱만큼 초과한
// 상태로 남는데, 이건 사용자가 더 본 게 아니라 측정 granularity라 성공 판정에서 봐준다.
const TRACKING_TICK_GRACE_MS = 60 * 1000;

export function milestoneAchievementKey(days) {
  return `streak_${days}`;
}

export function perfectAchievementKey(days) {
  return `perfect_${days}`;
}

export function milestoneXpBonus(days) {
  return days * 5;
}

// 그날 안 쓴 시간(한도 기준, 무제한이면 24시간 기준) 만큼 XP 지급
export function unusedTimeXpBonus(usageMs, limitMs) {
  const dayBaseMs = Number.isFinite(limitMs) ? limitMs : ONE_DAY_MS;
  const unusedMs = Math.max(0, dayBaseMs - usageMs);
  return Math.floor(unusedMs / (UNUSED_MINUTES_PER_XP * 60 * 1000));
}

// 레벨 티어: 레벨업 시 코스메틱(색상/칭호)만 바뀐다 — 한도/긴급시청 등 실질 기능엔 영향 없음
const LEVEL_TIERS = [
  { min: 50, key: 'legend', title: '전설', emoji: '👑' },
  { min: 20, key: 'master', title: '마스터', emoji: '⭐' },
  { min: 10, key: 'skilled', title: '숙련자', emoji: '🔥' },
  { min: 5, key: 'trainee', title: '수련생', emoji: '🌿' },
  { min: 1, key: 'seed', title: '새싹', emoji: '🌱' }
];

export function getLevelTier(level) {
  return LEVEL_TIERS.find((t) => level >= t.min) || LEVEL_TIERS[LEVEL_TIERS.length - 1];
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

/**
 * 스트릭 판정. 긴급 시청으로 본 시간(emergencyMs)은 빼고 본다.
 *
 * 긴급 시청 시간도 usage_history에는 그대로 쌓이므로(총 시청시간은 사실대로 기록) 빼주지 않으면
 * 긴급 시청을 쓴 날은 무조건 한도 초과가 되어 스트릭이 끊긴다. 하지만 긴급 시청은 남은 횟수를
 * 소모해서 산 "허용된 예외"라, 그걸로 스트릭까지 끊으면 이중 처벌이다. 대신 그 날은
 * isPerfectDay가 false가 되어 완벽한 날 뱃지만 못 받는 식으로 이원화한다.
 */
export function isDaySuccess(usageMs, limitMs, emergencyMs = 0) {
  if (!Number.isFinite(limitMs)) return true; // 무제한 설정이면 항상 성공
  const ownUsageMs = Math.max(0, usageMs - Math.max(0, emergencyMs));
  return ownUsageMs <= limitMs + TRACKING_TICK_GRACE_MS;
}

/** 성공한 날 중에서도 긴급 시청을 한 번도 쓰지 않은 날. 완벽한 날 뱃지/카운트 기준. */
export function isPerfectDay(usageMs, limitMs, emergencyMs = 0, emergencyUses = 0) {
  return isDaySuccess(usageMs, limitMs, emergencyMs) && emergencyMs <= 0 && emergencyUses <= 0;
}

/**
 * 하루가 끝났을 때(자정 롤오버) 스트릭/XP/업적을 갱신한다.
 * 실패 시 스트릭은 0으로 리셋되지만 total_success_days와 best_streak은 보존된다.
 * 완벽한 날 스트릭(perfect)도 같은 방식으로 별도 관리된다 — 긴급 시청을 쓴 날은 성공이지만
 * 완벽하진 않으므로 current_streak은 이어지고 current_perfect_streak만 0으로 끊긴다.
 */
export async function applyDayRollover(supabase, userId, { date, usageMs, limitMs, emergencyMs = 0, emergencyUses = 0 }) {
  const success = isDaySuccess(usageMs, limitMs, emergencyMs);
  const perfect = isPerfectDay(usageMs, limitMs, emergencyMs, emergencyUses);

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
    xp: 0,
    perfect_days: 0,
    current_perfect_streak: 0,
    best_perfect_streak: 0
  };

  // 같은 날짜를 중복 처리하지 않도록 방어
  if (prev.last_result_date === date) {
    return { success, perfect, streak: prev, unlockedAchievements: [] };
  }

  const nextStreak = success ? prev.current_streak + 1 : 0;
  const nextBest = Math.max(prev.best_streak, nextStreak);
  const nextTotalSuccess = prev.total_success_days + (success ? 1 : 0);
  // 완벽한 날 관련 필드는 기존 행(스키마 마이그레이션 전에 생긴 행)에 없을 수 있어 기본값 처리
  const nextPerfectStreak = perfect ? (prev.current_perfect_streak || 0) + 1 : 0;
  const nextBestPerfect = Math.max(prev.best_perfect_streak || 0, nextPerfectStreak);
  const nextPerfectDays = (prev.perfect_days || 0) + (perfect ? 1 : 0);
  // 안 쓴 시간만큼 XP + 스트릭 이어가는 중이면 스트릭 일수만큼 추가 XP
  const streakBonusXp = success ? nextStreak : 0;
  let nextXp = prev.xp + unusedTimeXpBonus(usageMs, limitMs) + streakBonusXp + (perfect ? PERFECT_DAY_XP : 0);

  const newlyHitMilestones = success
    ? STREAK_MILESTONES.filter((m) => m === nextStreak)
    : [];
  for (const m of newlyHitMilestones) {
    nextXp += milestoneXpBonus(m);
  }
  const newlyHitPerfectMilestones = perfect
    ? PERFECT_MILESTONES.filter((m) => m === nextPerfectStreak)
    : [];
  for (const m of newlyHitPerfectMilestones) {
    nextXp += milestoneXpBonus(m);
  }

  const updated = {
    user_id: userId,
    current_streak: nextStreak,
    best_streak: nextBest,
    last_result_date: date,
    total_success_days: nextTotalSuccess,
    xp: nextXp,
    perfect_days: nextPerfectDays,
    current_perfect_streak: nextPerfectStreak,
    best_perfect_streak: nextBestPerfect
  };

  await supabase.from('streaks').upsert(updated);

  const unlockedAchievements = [];
  const rows = [
    ...newlyHitMilestones.map((m) => ({ user_id: userId, key: milestoneAchievementKey(m), unlocked_at: new Date().toISOString() })),
    ...newlyHitPerfectMilestones.map((m) => ({ user_id: userId, key: perfectAchievementKey(m), unlocked_at: new Date().toISOString() }))
  ];
  if (rows.length > 0) {
    const { error } = await supabase.from('achievements').upsert(rows, { onConflict: 'user_id,key', ignoreDuplicates: true });
    if (!error) unlockedAchievements.push(...rows.map((r) => r.key));
  }

  return { success, perfect, streak: updated, unlockedAchievements };
}
