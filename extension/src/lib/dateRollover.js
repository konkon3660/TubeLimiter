// 날짜가 넘어갈 때 무엇을 정산/리셋해야 하는지를 계산하는 순수 로직.
// service-worker.js의 checkDateRollover / checkAndResetEmergencyUses에서 판정만 떼어냈다 —
// Supabase 롤오버 반영(applyDayRollover)과 storage 쓰기는 호출자 몫이다.
// 안드로이드의 UsageMonitorService.settleFinishedDays / resetEmergencyAllowanceIfDue,
// BlockDecision.kt의 emergencyResetKey 대응.
//
// 긴급 시청 횟수 리셋도 결국 "날짜 버킷이 바뀌었나" 판정이라 같은 파일에 둔다
// (emergency.js는 시간 겹침 계산 전용이라 성격이 다르다).

import { addDaysToDate } from './time.js';

/** 저장소가 손상돼 local_current_date가 비정상일 때 롤오버 루프가 무한히 도는 걸 막는 상한. */
export const MAX_ROLLOVER_DAYS = 400;

/**
 * 마지막으로 정산한 날짜(local_current_date)와 오늘을 비교해 무엇을 해야 하는지 계산한다.
 *
 * @param {string|null|undefined} storedDate 저장돼 있던 local_current_date ('YYYY-MM-DD')
 * @param {string} today getTodayDate() (4시 컷오프 기준)
 * @param {number} maxDays 한 번에 정산할 최대 일수
 * @returns {{action: 'init'|'upToDate'|'clockWentBackwards'|'rollover', dates: string[], nextStoredDate: string|null}}
 *   dates는 정산해야 할 날짜들(오늘은 아직 안 끝났으므로 제외). nextStoredDate가 null이면
 *   local_current_date를 건드릴 필요가 없다는 뜻.
 */
export function planDateRollover(storedDate, today, maxDays = MAX_ROLLOVER_DAYS) {
  // 설치 직후 등 기준점이 아예 없으면 정산할 지난 날도 없다 — 오늘로 기준만 잡는다.
  if (!storedDate) return { action: 'init', dates: [], nextStoredDate: today };
  if (storedDate === today) return { action: 'upToDate', dates: [], nextStoredDate: null };

  // 시계가 되돌아간 경우(시스템 시각 조정, DST 등) 저장된 날짜가 오늘보다 미래일 수 있다.
  // 앞으로만 증가하는 루프라 이 경우 today를 영영 못 만나 무한루프에 빠진다 —
  // 롤오버 없이 today로 재동기화만 한다.
  if (storedDate > today) return { action: 'clockWentBackwards', dates: [], nextStoredDate: today };

  // 브라우저를 며칠 안 켰어도 그 사이 날짜들을 하루씩 순회하며 정산한다.
  // 접속 안 한 날은 사용량 0이라 한도 이내 = 성공으로 취급된다 (스트릭이 부당하게 안 끊기게).
  const dates = [];
  let date = storedDate;
  while (date !== today && dates.length < maxDays) {
    dates.push(date);
    date = addDaysToDate(date, 1);
  }
  // 상한에 걸려 다 못 돌았어도 기준점은 오늘로 옮긴다 (기존 동작 유지 — 안 그러면 매 틱마다
  // 같은 400일을 다시 돌게 된다).
  return { action: 'rollover', dates, nextStoredDate: today };
}

/**
 * 긴급 시청 횟수가 담기는 버킷의 키. 이 값이 바뀌면 남은 횟수가 리셋된다.
 * 안드로이드 emergencyResetKey()와 같은 규칙.
 *
 * @param {string|undefined} frequency 'daily' | 'weekly' | 'monthly' (그 외/누락이면 daily)
 * @param {{today: string, weekStart: string, monthStart: string}} dates
 */
export function emergencyResetDate(frequency, { today, weekStart, monthStart }) {
  if (frequency === 'weekly') return weekStart;
  if (frequency === 'monthly') return monthStart;
  return today;
}

/** 긴급 시청 횟수 기본값. 설정이 비어 있을 때 쓰는 값. */
export const DEFAULT_EMERGENCY_USES = 3;

/**
 * 긴급 시청 횟수를 지금 리셋해야 하는지 판정한다.
 *
 * @param {object} inputs
 * @param {string|null|undefined} inputs.lastResetDate 저장돼 있던 last_emergency_date
 * @param {string|undefined} inputs.frequency emergency_config.resetFrequency
 * @param {number|null|undefined} inputs.dailyUses emergency_config.dailyUses
 * @param {string} inputs.today
 * @param {string} inputs.weekStart
 * @param {string} inputs.monthStart
 * @returns {{shouldReset: boolean, resetDate: string, uses: number}}
 */
export function planEmergencyReset({ lastResetDate, frequency, dailyUses, today, weekStart, monthStart }) {
  const resetDate = emergencyResetDate(frequency, { today, weekStart, monthStart });
  return {
    shouldReset: lastResetDate !== resetDate,
    resetDate,
    uses: dailyUses ?? DEFAULT_EMERGENCY_USES
  };
}
