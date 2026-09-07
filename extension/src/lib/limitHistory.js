// "그날 실제로 적용됐던 한도"를 날짜별로 남기고 되읽는 순수 로직.
//
// 왜 필요한가: 대시보드 히트맵/막대그래프는 원래 과거 날짜에도 computeLimitForDate로 "지금
// 설정된 한도"를 소급 적용해 성공/실패를 판정했다. 그래서 한도를 30분에서 2시간으로 올리면
// 예전에 초과했던 날들이 한꺼번에 성공으로 바뀌어, 기록이 사실이 아니게 된다. 한도가 쓰이는
// 그때그때 값을 남겨두면 나중에 설정을 바꿔도 지난 판정이 흔들리지 않는다.
//
// 저장은 chrome.storage의 limit_history 맵({ 'YYYY-MM-DD': ms })이고, 날짜 키는 usage_history와
// 같은 lib/time.js getTodayDate() (새벽 4시 컷오프) 기준이다 — 다른 기준으로 만들면 같은 날의
// 사용량과 한도가 서로 다른 칸에 들어간다.
//
// 이 파일은 chrome.*를 참조하지 않으므로 node:test에서 그대로 돌릴 수 있다 (읽기/쓰기는
// 호출자인 service-worker.js 몫 — lib/dateRollover.js와 같은 구조).

import { computeLimitForDate } from './limits.js';
import { addDaysToDate } from './time.js';

// chrome.storage는 JSON 직렬화라 Infinity를 그대로 담지 못하고 null로 만들어버린다. 무제한은
// daily_limit_by_day와 같은 컨벤션인 -1 센티널로 적는다 (안드로이드 UNLIMITED_MILLIS와 같은 발상).
export const UNLIMITED_LIMIT_SENTINEL = -1;

// 히트맵 28일 + 막대그래프 30일을 다 채우고도 남게 잡는다. usage_history_hourly의 보관 기간과
// 같은 값이라 "대시보드용 기록은 60일"이라는 기준 하나만 기억하면 된다.
export const LIMIT_HISTORY_RETENTION_DAYS = 60;

/** 저장용 값으로 변환. 무제한(Infinity)과 계산이 깨진 값은 센티널로 접는다. */
export function serializeLimitMs(limitMs) {
  if (!Number.isFinite(limitMs)) return UNLIMITED_LIMIT_SENTINEL;
  return Math.max(0, limitMs);
}

/**
 * 그날 판정에 쓸 한도.
 *
 * 기록이 있으면 그 값이 정답이고, 없으면(이 기능 이전 날짜, 또는 다른 기기에서만 기록돼
 * 서버로만 내려온 날) 지금 설정으로 계산한 근사치로 떨어진다. 근사치인지 여부(estimated)를
 * 같이 돌려주는 이유는 화면에서 "추정"이라고 밝혀야 하기 때문이다 — 근사치를 사실처럼 보여주면
 * 원래 있던 소급 적용 문제를 그대로 두면서 티만 안 나게 하는 셈이 된다.
 *
 * 저장된 값이 숫자가 아니면(저장소 손상) 기록이 없는 것으로 보고 근사치로 간다. 무제한으로
 * 넘겨버리면 그날이 조용히 "무조건 성공"이 된다.
 *
 * @param {object|null|undefined} limitHistory { 'YYYY-MM-DD': ms } 맵
 * @param {object|null|undefined} settings settingsCache (근사치 계산용)
 * @param {string} date 'YYYY-MM-DD'
 * @returns {{limitMs: number, estimated: boolean}} 무제한은 Infinity로 되돌아온다.
 */
export function resolveLimitForDate(limitHistory, settings, date) {
  const recorded = (limitHistory || {})[date];
  if (recorded !== undefined && recorded !== null) {
    const value = Number(recorded);
    if (Number.isFinite(value)) {
      return { limitMs: value < 0 ? Infinity : value, estimated: false };
    }
  }
  return { limitMs: computeLimitForDate(settings, date), estimated: true };
}

/**
 * 기록을 갱신하고 보관 기간이 지난 날짜를 정리한 새 맵을 만든다 (원본은 건드리지 않는다).
 *
 * updates 원소: { date, limitMs, keepExisting }
 *  - keepExisting=true면 이미 기록이 있는 날은 그대로 둔다. 롤오버가 지난 날짜를 채울 때 쓴다 —
 *    그 시점의 설정은 이미 바뀌었을 수 있어서, 그날 남겨둔 값이 언제나 더 정확하다.
 *  - 기본(false)은 덮어쓰기. 오늘 날짜는 하루 사이에도 한도를 바꿀 수 있으니 마지막 값으로
 *    수렴시킨다 — 롤오버 정산(gamification.applyDayRollover)이 쓰는 값과 같은 기준이라
 *    히트맵과 스트릭 판정이 어긋나지 않는다.
 *
 * changed가 false면 저장할 게 없다는 뜻이다. 이 함수는 매 틱마다 불리므로, 값이 그대로일 때
 * 굳이 storage에 다시 쓰지 않게 하려는 신호다.
 *
 * @param {object|null|undefined} history 기존 맵
 * @param {Array<{date: string, limitMs: number, keepExisting?: boolean}>} updates
 * @param {string} today 정리 기준일 (getTodayDate())
 * @param {number} retentionDays 보관 일수
 * @returns {{history: object, changed: boolean}}
 */
export function planLimitHistoryUpdate(history, updates, today, retentionDays = LIMIT_HISTORY_RETENTION_DAYS) {
  const next = { ...(history || {}) };
  let changed = false;

  for (const update of updates || []) {
    if (!update || !update.date) continue;
    const has = Object.prototype.hasOwnProperty.call(next, update.date);
    if (update.keepExisting && has) continue;
    const value = serializeLimitMs(update.limitMs);
    if (has && next[update.date] === value) continue;
    next[update.date] = value;
    changed = true;
  }

  const cutoff = addDaysToDate(today, -retentionDays);
  for (const date of Object.keys(next)) {
    if (date < cutoff) {
      delete next[date];
      changed = true;
    }
  }

  return { history: next, changed };
}
