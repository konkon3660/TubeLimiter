// 하드코어 모드가 "무엇을 못 바꾸게 하는가"의 규칙. 순수 함수라 node:test에서 그대로 돈다.
//
// ## 왜 입력 disabled만으로는 부족한가
//
// 예전 잠금은 옵션 화면에서 한도 입력 세 개를 disabled 처리하는 게 전부였다. 그래서 하드코어를
// 켜둔 채로도 화이트리스트 추가, 긴급 시청 횟수 상향, 예약 차단 삭제, Shorts 항상 차단 해제가
// 그대로 됐다 — 넷 다 대기 시간 0초로 차단을 무력화하는 경로다(documents/QA_REVIEW.md §1.2).
// 화면에서 가리는 것과 규칙으로 막는 것은 다르고, 이 파일은 후자다. 저장 직전에 한 번 더
// 판정하므로 화면 상태가 어떻든(다른 탭에서 연 옵션 페이지, 화면 갱신 전 상태 등) 통과 못 한다.
//
// ## 잠그는 기준: "약화만 금지"
//
// 전부 disabled로 막는 것도 방법이지만, 그러면 하드코어 중에는 규칙을 **더 세게** 만들 수도
// 없다 — 한도를 줄이거나 예약 차단을 추가하는 것까지 막히면, 스스로를 더 옥죄려는 사용자가
// 하드코어를 끄고(1시간 대기 + 스트릭 리셋) 다시 켜야 한다. 커밋먼트 장치가 커밋먼트를
// 방해하는 셈이라, 기준을 "차단이 약해지는 방향"으로 잡는다.
//
// 판단이 애매한 값은 **약화로 본다**(막는다). 잘못 막으면 사용자가 1시간 기다렸다 바꾸면
// 그만이지만, 잘못 열어주면 그게 곧 우회로다.

/** 화면에 띄울 거부 사유. 값은 _locales 메시지 키다(문구는 UI가 붙인다). */
export const HARDCORE_VIOLATION = Object.freeze({
  dailyLimit: 'options_hardcore_violation_daily_limit',
  byDayLimit: 'options_hardcore_violation_by_day_limit',
  shortsLimit: 'options_hardcore_violation_shorts_limit',
  whitelist: 'options_hardcore_violation_whitelist',
  emergencyUses: 'options_hardcore_violation_emergency',
  alwaysBlockShorts: 'options_hardcore_violation_shorts_toggle',
  scheduledBlocks: 'options_hardcore_violation_schedule'
});

/** 무제한(0 또는 없음)은 가장 느슨한 값이므로 비교에서 Infinity로 접는다. */
function limitValue(ms) {
  const n = Number(ms);
  if (!Number.isFinite(n) || n <= 0) return Infinity;
  return n;
}

function byDayMap(value) {
  return value && typeof value === 'object' ? value : {};
}

function toArray(value) {
  return Array.isArray(value) ? value : [];
}

/** 예약 차단 하나가 실제로 막는 분량. 꺼져 있으면 0이고, 자정을 넘는 구간은 wrap을 편다. */
function scheduleWeight(block) {
  if (!block || block.enabled === false) return 0;
  const start = Number(block.startMinute);
  const end = Number(block.endMinute);
  if (!Number.isFinite(start) || !Number.isFinite(end)) return 0;
  const span = end > start ? end - start : 1440 - start + end;
  const days = toArray(block.days).filter(Boolean).length;
  return span * (days || 0);
}

function scheduleById(blocks) {
  const map = new Map();
  for (const block of toArray(blocks)) {
    if (block && block.id != null) map.set(String(block.id), block);
  }
  return map;
}

/**
 * 하드코어 모드에서 금지되는 변경(= 차단이 약해지는 방향)을 찾아낸다.
 *
 * @param {object} previous 서버에 저장돼 있는 현재 설정
 * @param {object} next 저장하려는 설정
 * @returns {Array<{field: string, messageKey: string}>} 비어 있으면 저장해도 된다
 */
export function findHardcoreViolations(previous, next) {
  const prev = previous || {};
  const draft = next || {};
  const violations = [];
  const add = (field, messageKey) => violations.push({ field, messageKey });

  // 한도: 커지면(=더 오래 볼 수 있으면) 약화. 무제한으로 바꾸는 것도 여기 걸린다.
  if (limitValue(draft.daily_limit_ms) > limitValue(prev.daily_limit_ms)) {
    add('daily_limit_ms', HARDCORE_VIOLATION.dailyLimit);
  }

  // 요일별 한도: 요일 하나라도 커지면 약화. 요일별 한도를 통째로 끄는 것도 그 요일의 한도가
  // 기본 한도로 풀리는 것이므로, 지워진 요일은 기본 한도와 비교한다.
  const prevByDay = byDayMap(prev.daily_limit_by_day);
  const nextByDay = byDayMap(draft.daily_limit_by_day);
  for (const day of new Set([...Object.keys(prevByDay), ...Object.keys(nextByDay)])) {
    const before = limitValue(prevByDay[day] ?? prev.daily_limit_ms);
    const after = limitValue(nextByDay[day] ?? draft.daily_limit_ms);
    if (after > before) {
      add('daily_limit_by_day', HARDCORE_VIOLATION.byDayLimit);
      break;
    }
  }

  if (limitValue(draft.shorts_limit_ms) > limitValue(prev.shorts_limit_ms)) {
    add('shorts_limit_ms', HARDCORE_VIOLATION.shortsLimit);
  }

  // 화이트리스트: 추가만 금지하고 삭제는 허용한다(삭제는 차단이 세지는 방향).
  const prevWhitelist = new Set(toArray(prev.whitelist).map(String));
  const added = toArray(draft.whitelist)
    .map(String)
    .filter((entry) => !prevWhitelist.has(entry));
  if (added.length > 0) add('whitelist', HARDCORE_VIOLATION.whitelist);

  // 긴급 시청: 허용 횟수를 늘리는 것과 리셋 주기를 짧게 만드는 것(daily가 monthly보다 느슨하다)
  // 둘 다 약화다. 주기 순서를 모르는 값이 오면 비교하지 않는다 — 임의 값으로 사고 내지 않게.
  const prevUses = Number(prev.emergency_config?.dailyUses);
  const nextUses = Number(draft.emergency_config?.dailyUses);
  if (Number.isFinite(prevUses) && Number.isFinite(nextUses) && nextUses > prevUses) {
    add('emergency_config', HARDCORE_VIOLATION.emergencyUses);
  }
  // 값이 클수록 **느슨하다**(daily = 매일 3회 = 월 90회 > monthly = 월 3회). 이름을
  // strictness로 적으면 다음에 읽는 사람이 부호를 뒤집기 쉬워서 looseness로 못 박는다
  // (QA_REVIEW §10.5). 안드로이드 짝은 HardcoreLock.kt의 emergencyResetLooseness.
  const RESET_LOOSENESS = { monthly: 0, weekly: 1, daily: 2 };
  const prevReset = RESET_LOOSENESS[prev.emergency_config?.resetFrequency];
  const nextReset = RESET_LOOSENESS[draft.emergency_config?.resetFrequency];
  if (prevReset != null && nextReset != null && nextReset > prevReset) {
    add('emergency_config', HARDCORE_VIOLATION.emergencyUses);
  }

  // Shorts 항상 차단: 켜져 있던 걸 끄는 것만 금지.
  if (prev.always_block_shorts && !draft.always_block_shorts) {
    add('always_block_shorts', HARDCORE_VIOLATION.alwaysBlockShorts);
  }

  // 예약 차단: 삭제·비활성화·구간 축소가 전부 약화다. 추가와 확대는 허용한다.
  const prevBlocks = scheduleById(prev.scheduled_blocks);
  const nextBlocks = scheduleById(draft.scheduled_blocks);
  for (const [id, before] of prevBlocks) {
    const after = nextBlocks.get(id);
    if (!after || scheduleWeight(after) < scheduleWeight(before)) {
      add('scheduled_blocks', HARDCORE_VIOLATION.scheduledBlocks);
      break;
    }
  }

  return violations;
}

/**
 * 하드코어가 켜져 있을 때만 위 규칙을 적용한다.
 * 하드코어 자체를 끄는 건 여기서 막지 않는다 — 그건 1시간 쿨다운(lib/hardcore.js)이 담당하는
 * 별개의 관문이고, 여기서 또 막으면 해제 요청 자체를 저장할 수 없게 된다.
 */
export function isHardcoreChangeAllowed(previous, next) {
  if (!previous?.hardcore_mode) return { allowed: true, violations: [] };
  const violations = findHardcoreViolations(previous, next);
  return { allowed: violations.length === 0, violations };
}
