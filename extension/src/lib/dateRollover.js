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
 * 리셋 키는 동시에 그 버킷의 "시작일"이기도 하다. 서버 daily_usage는 날짜별 행이라 weekly/monthly
 * 설정에서 기기 간 합계를 내려면 이 날짜 이후 행들의 emergency_uses를 합산해야 하는데, 그 구간
 * 기준을 따로 만들지 않고 이 함수 하나로 통일한다 — 규칙이 두 군데로 갈라지면 리셋 시점과
 * 합산 구간이 어긋나 잔여 횟수가 조용히 틀어진다.
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
 * 저장·비교에 쓸 주기 값. 모르는 값과 누락은 emergencyResetDate와 같게 daily로 접는다 —
 * 여기서 접지 않으면 `undefined`와 `'daily'`가 "주기가 바뀌었다"로 읽힌다.
 */
export function normalizeEmergencyFrequency(frequency) {
  return frequency === 'weekly' || frequency === 'monthly' ? frequency : 'daily';
}

/**
 * 긴급 시청 횟수를 지금 리셋해야 하는지 판정한다.
 *
 * ## 왜 "키가 달라졌다"만으로는 안 되는가 (QA_REVIEW §10.2)
 *
 * 버킷 키는 주기에 따라 오늘/주 시작일/월 시작일이라, **주기를 바꾸는 것만으로도** 키가
 * 달라진다. 예전 규칙(`lastResetDate !== resetDate`면 리셋)에서는 하드코어를 켠 채 daily→weekly
 * →monthly로 "조이기만" 해도 그 자리에서 횟수가 두 번 리필됐다 — 하드코어 게이트는 조이는
 * 방향을 통과시키므로 잠금 안에서 뚫리는 구멍이었다.
 *
 * 그래서 **"시간이 흘러 새 버킷이 시작된 것"과 "주기가 바뀐 것"을 구별한다.** 구별 수단은
 * 마지막으로 적용된 주기를 같이 저장해두는 것이다(`last_emergency_date` 옆의
 * `last_emergency_frequency`, 안드로이드는 `emergency_reset_bucket_frequency`):
 *
 *   - **그때 주기로 다시 계산한 오늘의 키**가 저장된 키와 다르다 → 날짜가 흘러 버킷이 끝났다 →
 *     리셋(reset).
 *   - 같다 → 아직 같은 버킷 안이다. 키가 달라진 이유는 주기 변경뿐이므로 **이미 쓴 횟수를 새
 *     버킷이 이어받는다**(carryOver: 키와 주기만 새 값으로 갱신, 남은 횟수는 그대로).
 *
 * 결과적으로 주기 변경은 어느 방향이든 리필하지 않고, 주기를 바꾼 뒤 **실제로** 새 버킷이
 * 시작되면 그때 정상적으로 리셋된다. 두 클라이언트가 같은 규칙이어야 하며(안드로이드
 * `planEmergencyReset` in limit/BlockDecision.kt), 어긋나면 두 기기가 다른 잔여를 보여준다.
 *
 * 서버 합산 구간은 여전히 `emergencyResetDate` 하나로 정해진다 — carryOver로 키가 주 시작일로
 * 넓어지면 합산 구간도 같이 넓어지고, 그 구간에서 내가 이미 보고한 몫은
 * `reportedEmergencyUsesInBucket`이 같은 구간으로 빼주므로 이중 차감이 생기지 않는다.
 *
 * @param {object} inputs
 * @param {string|null|undefined} inputs.lastResetDate 저장돼 있던 last_emergency_date
 * @param {string|null|undefined} inputs.lastResetFrequency 그 키를 만들 때 적용됐던 주기.
 *   이 값이 생기기 전 저장소에는 없다(null) — 그때는 "지금 주기와 같았다"고 보고 예전과 똑같이
 *   판정하되, 다음 판정부터 구별이 되도록 표식만 남긴다(carryOver).
 * @param {string|undefined} inputs.frequency emergency_config.resetFrequency
 * @param {number|null|undefined} inputs.dailyUses emergency_config.dailyUses
 * @param {string} inputs.today
 * @param {string} inputs.weekStart
 * @param {string} inputs.monthStart
 * @returns {{action: 'reset'|'carryOver'|'none', shouldReset: boolean, resetDate: string,
 *   resetFrequency: string, uses: number}} action이 'none'이면 저장소를 건드릴 필요가 없다.
 */
export function planEmergencyReset({
  lastResetDate,
  lastResetFrequency,
  frequency,
  dailyUses,
  today,
  weekStart,
  monthStart
}) {
  const dates = { today, weekStart, monthStart };
  const resetFrequency = normalizeEmergencyFrequency(frequency);
  const resetDate = emergencyResetDate(resetFrequency, dates);
  const uses = dailyUses ?? DEFAULT_EMERGENCY_USES;
  const plan = (action) => ({
    action,
    shouldReset: action === 'reset',
    resetDate,
    resetFrequency,
    uses
  });

  // 기록이 아예 없다(설치 직후·계정 삭제 후) = 깔아줄 첫 버킷이다.
  if (!lastResetDate) return plan('reset');

  // 저장된 키를 **그때 주기로** 다시 계산해 오늘과 맞춰본다. 다르면 날짜가 흘러간 것이다.
  const previousFrequency = normalizeEmergencyFrequency(lastResetFrequency ?? resetFrequency);
  if (lastResetDate !== emergencyResetDate(previousFrequency, dates)) return plan('reset');

  // 여기부터는 "아직 같은 버킷 안". 키·주기가 달라졌으면 표식만 새 버킷으로 옮긴다.
  // 표식이 아예 없던 저장소(lastResetFrequency == null)도 이 길로 한 번 들어와 표식을 남긴다.
  if (lastResetDate !== resetDate || lastResetFrequency !== resetFrequency) {
    return plan('carryOver');
  }
  return plan('none');
}
