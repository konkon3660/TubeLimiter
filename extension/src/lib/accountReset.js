// 로그아웃 · 계정 삭제가 chrome.storage.local에서 **무엇을 지우고 무엇을 남기는가**.
//
// 목록을 화면(options.js)에서 빼내 여기 모아둔 이유는 두 가지다.
//  1. 이 목록은 안드로이드 AppState.clearAccountData와 맞춰야 하는 계약이다
//     (documents/BACKEND.md). 한쪽만 바꾸면 "PC에서 하면 되는데 폰에서는 안 되는" 우회가 생긴다.
//  2. chrome.* 없이 도는 순수 목록이라 테스트로 고정할 수 있다 — 이 파일이 지키는 규칙은
//     전부 "지우면 안 되는 것을 지워서 생기는 우회"라, 화면 코드 안에 있으면 검증할 방법이 없다.
//
// ## 로그아웃과 계정 삭제는 의도적으로 비대칭이다
//
// 로그아웃은 **긴급 시청 관련 값을 하나도 건드리지 않는다.** 남은 횟수 판정은 로컬 카운터
// (emergency_uses_today)에서 "다른 기기가 이 버킷에서 이미 쓴 몫"(emergencyUsesBucket*)을 뺀
// 값인데, 그 버킷 캐시를 지우면 getEffectiveEmergencyUses가 로컬 값으로 폴백해서 폰이 이미 쓴
// 횟수가 통째로 되살아난다. 즉 로그아웃 버튼이 곧 "횟수 리필 버튼"이 된다. 안드로이드도 같은
// 이유로 로그아웃 때는 이 캐시를 남기고 버킷이 끝날 때까지 유지한다("로그아웃이 탈출구가 되면
// 안 된다"). 확장에서는 캐시에 붙은 버킷 시작일(emergencyUsesBucketDate)이 지금 버킷과 다르면
// 자동으로 무시되므로, 그냥 남겨두는 것만으로 "버킷이 끝나면 사라진다"가 성립한다.
//
// 반대로 계정 **삭제**는 버킷 캐시를 지운다. 계정 행이 cascade로 사라진 마당에 남겨두면 이제
// 존재하지도 않는 기기 때문에 횟수가 깎인다. 대신 이 기기 자신의 카운트다운
// (emergency_uses_today / last_emergency_date)은 남긴다 — 그것까지 지우면 "탈퇴 후 재가입"이
// 허용 횟수를 full로 되돌리는 우회가 된다. 안드로이드 clearAccountData의 주석과 같은 판단이다.
//
// ## 세 번째 경우: 계정 전환
//
// 로그아웃/삭제 어느 쪽도 아닌 채로 주인이 바뀌는 경로가 있다 — A가 로그아웃하고 B가 로그인하는
// 공용 PC. 로그아웃이 지우는 건 진단 기록뿐이라, 그 상태로 B가 들어오면 B의 대시보드에 A의 28일
// 기록이 그대로 뜨고(프라이버시), A가 이미 서버에 보고한 몫(dailyUsageSync*)이 B의 첫 델타
// 기준선이 되어 B의 그날 사용량이 조용히 안 올라가고, A의 settingsCache가 남아 B가 A의 하드코어
// 잠금을 물려받는다(documents/QA_REVIEW.md §1.3).
//
// 그렇다고 로그아웃 때 지울 수는 없다. 같은 계정으로 다시 들어오는 게 로그아웃의 정상 경로이고,
// 로컬 기록은 서버에서 되받아올 수 없어 지우면 영영 사라진다. 그래서 "언제 지우는가"의 기준을
// 로그아웃이 아니라 **주인이 바뀌었는가**로 잡는다 — 이 기기의 로컬 데이터가 어느 user_id의
// 것인지 표식(ACCOUNT_OWNER_KEY)을 남겨두고, 로그인한 user_id와 다를 때만 비운다.

import { DIAGNOSTIC_STORAGE_KEYS } from './diagnosticsStore.js';

/**
 * 지금 이 기기의 로컬 데이터가 어느 계정의 것인지 적어두는 표식.
 *
 * 값은 로그인한 user_id다. 로그아웃해도 **남긴다** — 지워버리면 다음 로그인이 전부 "표식이 없는
 * 첫 로그인"으로 보여서(=아무것도 안 지운다) 계정 전환 가드가 통째로 무력해진다.
 */
export const ACCOUNT_OWNER_KEY = 'accountUserId';

/**
 * 로그아웃할 때 지우는 키.
 *
 * 진단 기록은 지운 계정과의 통신 기록이라 함께 비운다 — 남겨두면 이미 로그아웃한 계정의 실패
 * 목록이 계속 보이고, "마지막 성공" 시각이 다음 계정의 24시간 판정(staleSyncWarning)에 그대로
 * 끼어든다. 안드로이드도 로그아웃/삭제 양쪽에서 같이 지운다.
 *
 * **여기에 emergencyUsesBucket* 를 넣지 말 것** — 위 "비대칭" 주석 참고. 사용/한도 기록과
 * 동기화 마커도 마찬가지다. 로그아웃은 같은 계정으로 다시 들어올 수 있는 상태라, 지워봐야
 * 다음 로그인 때 서버에서 다시 받아오거나(설정) 영영 잃는다(로컬 기록).
 */
export const SIGN_OUT_REMOVED_KEYS = Object.freeze([...DIAGNOSTIC_STORAGE_KEYS]);

/**
 * 계정 삭제 때 지우는 키. **chrome.storage.local.clear()를 쓰면 안 된다** — 이 확장의 저장소에는
 * 계정에서 온 값과 "지금 이 기기를 막고 있는 상태"가 섞여 있어서, 통째로 비우면 삭제가 잠금 해제
 * 수단이 된다(특히 emergency_uses_today가 날아가 재가입 시 허용 횟수가 full로 복구된다).
 *
 * 여기 있는 건 전부 지운 계정이 만들어낸 값이다: 대시보드가 그리는 기록과 그 짝인 한도 스냅샷,
 * daily_usage 동기화 마커(남겨두면 다음 계정이 이 계정의 보고 합계를 물려받는다), 다른 기기 몫
 * 캐시, 그리고 진단 기록.
 *
 * settingsCache도 지운다. 서버 settings 행의 사본이라 계정과 함께 사라지는 게 맞고, 남겨두면
 * hardcore_mode가 켜진 채로 굳는다 — 옵션 화면은 로그인한 계정이 있어야 설정을 바꿀 수 있으므로
 * 계정 없는 하드코어 잠금은 영영 못 푸는 상태가 된다.
 *
 * Supabase 세션 키(`sb-<ref>-auth-token`)는 프로젝트 ref가 섞인 동적 이름이라 여기 적을 수 없다.
 * 대신 호출부가 이 목록을 지우기 전에 supabase.auth.signOut({ scope: 'local' })을 부르고, 그
 * 어댑터가 자기 키를 지운다(lib/supabaseClient.js).
 */
export const ACCOUNT_DELETE_REMOVED_KEYS = Object.freeze([
  // 계정 설정의 로컬 사본
  'settingsCache',

  // 대시보드가 그리는 기록 + 그날 판정에 쓰인 한도 스냅샷.
  // 한도만 남기면 다음 계정의 새 기록이 지운 계정의 한도로 판정된다(안드로이드도 같이 지운다).
  'usage_history',
  'usage_history_shorts',
  'usage_history_hourly',
  'emergency_history',
  'limit_history',
  // 롤오버 기준일. 남겨두면 다음 계정의 첫 롤오버가 이 계정의 마지막 날부터 정산을 시작한다.
  'local_current_date',

  // daily_usage 동기화 마커 — "이 기기가 이미 보고한 몫"이라 계정이 바뀌면 무의미하다.
  'dailyUsageSyncDate',
  'dailyUsageSyncedMillis',
  'dailyUsageShortsSyncedMillis',
  'dailyUsageEmergencySyncedMillis',
  'dailyUsageEmergencyUsesSyncedCount',
  'dailyUsageCombinedMillis',
  'dailyUsageCombinedShortsMillis',
  // 예전 버전이 쓰기만 하고 아무도 읽지 않던 키. 지금은 쓰지도 않지만(service-worker.js 참고)
  // 이미 저장된 기기가 있으므로 정리 목록에는 남겨둔다.
  'dailyUsageCombinedEmergencyUses',

  // 다른 기기가 이 버킷에서 쓴 긴급 시청 횟수 캐시. 로그아웃 때는 남기고 여기서만 지운다.
  'emergencyUsesBucketDate',
  'emergencyUsesBucketRemote',
  'emergencyUsesBucketReported',

  // 로컬 데이터의 주인 표식. 값 자체가 지운 계정의 user_id라 남겨둘 이유가 없다.
  ACCOUNT_OWNER_KEY,

  ...DIAGNOSTIC_STORAGE_KEYS
]);

/**
 * 계정 삭제에서 **일부러 남기는** 키. 실제로 지울 때 쓰이지는 않고, 분류를 문서로 못 박아
 * 테스트가 "지우는 목록과 겹치지 않는다"를 검증하는 데 쓴다.
 *
 * 전부 계정이 아니라 이 기기에서 온 값이다: 지금 걸려 있는 차단, 돌고 있거나 예약된 집중 세션,
 * 긴급 시청 허용량과 쿨다운, 알람 장부, 예약 차단 마커, 대시보드 보기 설정. 이걸 지우면
 * "계정 삭제"가 지금 나를 막고 있는 집중 세션에서 빠져나가는 길이 된다.
 */
export const ACCOUNT_DELETE_PRESERVED_KEYS = Object.freeze([
  // 지금 걸려 있는 차단 상태
  'isYoutubeBlocked',
  'trackingBlocked',
  'isManuallyBlocked',
  'shortsLimitBlocked',

  // 집중 모드(진행 중 · 지연 시작 대기 · 종료 요청)와 그 입력 폼 기억값
  'focusModeActive',
  'focusModeEndTime',
  'focusModeDelayEndTime',
  'focusModeDelayDuration',
  'focusStopRequestedAt',
  'focusModeFormDuration',
  'focusModeFormDelay',

  // 긴급 시청: 진행 중인 창, 마지막 부여 시각(쿨다운), 그리고 이 기기의 남은 횟수 카운트다운
  'emergencyModeActive',
  'emergencyEndTime',
  'last_emergency_granted_at',
  'emergency_uses_today',
  'last_emergency_date',

  // 오늘 어떤 알림을 이미 띄웠는지 · 예약 차단 창 진입 여부
  'alarm_state',
  'scheduleBlockWasActive',

  // 화면 보기 설정(계정과 무관)
  'dashboardChartRangeDays'
]);

/**
 * 계정이 바뀌었을 때 지우는 키. **계정 삭제와 같은 목록이다**(주인 표식만 빼고 — 그건 곧바로
 * 새 주인으로 덮어쓰므로 지우는 게 무의미하다). 두 경우 모두 "이 기기에 남은 값이 더 이상 이
 * 계정의 것이 아니다"라는 같은 사실을 다루기 때문이다.
 *
 * 긴급 시청 버킷 캐시(emergencyUsesBucket*)는 로그아웃에서는 일부러 남기지만 **여기서는
 * 지운다.** 그 캐시는 "이 계정의 다른 기기가 이번 버킷에서 쓴 횟수"라 주인이 바뀌면 남의
 * 숫자다 — 남겨두면 B의 남은 횟수가 A의 폰이 쓴 만큼 깎인다. 로그아웃 때 남기는 이유였던
 * "로그아웃 = 횟수 리필" 우회는 여기서도 막혀 있다. 리필을 실제로 막고 있는 건 이 기기의
 * 카운트다운(emergency_uses_today)인데 그건 아래 PRESERVED에 그대로 남고, 애초에 이 경로는
 * **다른 계정으로 진짜 로그인해야** 도달한다(= 버튼 하나로 되는 우회가 아니다).
 */
export const ACCOUNT_SWITCH_REMOVED_KEYS = Object.freeze(
  ACCOUNT_DELETE_REMOVED_KEYS.filter((key) => key !== ACCOUNT_OWNER_KEY)
);

/**
 * 계정이 바뀌어도 남기는 키. 계정 삭제에서 남기는 것과 같다 — 전부 계정이 아니라 이 기기에서
 * 온 값이라, 지우면 "다른 계정으로 로그인"이 지금 걸려 있는 집중 세션·차단·남은 긴급 횟수를
 * 빠져나가는 길이 된다.
 */
export const ACCOUNT_SWITCH_PRESERVED_KEYS = Object.freeze([...ACCOUNT_DELETE_PRESERVED_KEYS]);

/**
 * 로그인한 user_id와 이 기기에 적힌 주인을 비교해 무엇을 할지 정한다.
 *
 * @param {string|null} storedUserId 이 기기의 로컬 데이터 주인(ACCOUNT_OWNER_KEY), 없으면 null
 * @param {string|null} currentUserId 지금 로그인한 user_id, 로그아웃 상태면 null
 * @returns {{switched: boolean, removedKeys: string[], ownerToStore: string|null}}
 *          ownerToStore가 null이면 표식을 다시 쓸 필요가 없다는 뜻.
 */
export function planAccountSwitch(storedUserId, currentUserId) {
  const stored = typeof storedUserId === 'string' && storedUserId ? storedUserId : null;
  const current = typeof currentUserId === 'string' && currentUserId ? currentUserId : null;

  // 로그아웃 상태에서는 아무 판단도 하지 않는다. "지금 주인이 없다"는 건 "주인이 바뀌었다"가
  // 아니고, 여기서 지우면 로그아웃이 곧 기록 삭제가 된다(SIGN_OUT_REMOVED_KEYS의 판단과 같다).
  if (!current) return { switched: false, removedKeys: [], ownerToStore: null };

  // 표식이 없는 기기 = 이 가드가 생기기 전부터 쓰던 기기이거나, 이 기기의 첫 로그인.
  // 여기서 지우면 멀쩡히 쓰던 사람이 확장 업데이트 한 번에 자기 기록을 잃는다. 표식만 남기고,
  // 지우는 판단은 "실제로 주인이 바뀌는" 다음번부터 한다.
  if (!stored) return { switched: false, removedKeys: [], ownerToStore: current };

  // 같은 계정으로 다시 로그인. 아무것도 지우면 안 된다 — 로컬 기록은 서버에서 되받아올 수
  // 없어서 한 번 지우면 영영 사라진다.
  if (stored === current) return { switched: false, removedKeys: [], ownerToStore: null };

  return { switched: true, removedKeys: [...ACCOUNT_SWITCH_REMOVED_KEYS], ownerToStore: current };
}

/**
 * 서버에서 받아온 settings 행으로 로컬 캐시를 어떻게 갱신할지.
 *
 * 행이 **없는** 경우(갓 가입해서 아직 아무 설정도 저장한 적 없는 계정)를 예전에는 그냥 넘겼는데,
 * 그러면 직전 계정의 settingsCache가 그대로 남아 새 계정이 남의 하드코어 잠금을 물려받는다
 * (§1.3). 서버 행이 없다는 건 "설정이 기본값"이라는 뜻이지 "직전 값을 유지하라"가 아니므로
 * 기본값으로 되돌린다.
 *
 * 여기서 서버에 기본 행을 만들지는 않는다. 쓰기는 옵션 화면이 upsert로 하고 있어서 백그라운드가
 * 같은 행을 동시에 만들면 경합만 늘고, 무엇보다 **읽기 실패와 "행 없음"을 구별하는 책임**이
 * 호출부에 있다 — 조회가 실패한 경우(error)는 이 함수까지 오지 않고 캐시를 그대로 둔다.
 * 오프라인이라고 설정이 기본값으로 풀려버리면 안 되기 때문이다.
 */
export function resolveSettingsCacheAfterPull(cached, row, defaults) {
  if (!row) return { ...defaults };
  return { ...cached, ...row };
}

// Supabase 세션은 sb-<project-ref>-auth-token 키로 chrome.storage.local에 저장된다(토큰이 크면
// .0/.1로 쪼개진다). 로그인·로그아웃이 확장 안에서 일어나는 사건 중 백그라운드가 관찰할 수 있는
// 건 이 키의 변화뿐이라, 계정 전환을 즉시 알아채는 신호로 쓴다.
const SUPABASE_SESSION_KEY_PATTERN = /^sb-.+-auth-token(\.\d+)?$/;

export function isSupabaseSessionKey(key) {
  return typeof key === 'string' && SUPABASE_SESSION_KEY_PATTERN.test(key);
}
