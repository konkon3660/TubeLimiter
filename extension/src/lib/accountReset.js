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

import { DIAGNOSTIC_STORAGE_KEYS } from './diagnosticsStore.js';

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
