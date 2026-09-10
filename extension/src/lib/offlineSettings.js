// 백엔드가 죽어 있을 때 옵션 화면이 무엇을 할 수 있는가의 규칙. 전부 순수 함수라 node:test에서 돈다.
//
// ## 왜 필요한가
//
// Supabase 무료 프로젝트는 일정 기간 비활성이면 자동 pause되고, 그러면 인증부터 실패한다.
// 그때 차단은 로컬 캐시로 계속 걸리는데 옵션 화면은 `getCurrentUser()`가 null이라는 이유로
// "로그인하세요" 화면만 띄웠다. 결과는 **"차단은 걸려 있는데 풀 수도 조정할 수도 없는" 상태**이고,
// 남는 선택지가 확장 삭제뿐인데 그건 로컬 전용 기록(시간대별 패턴·limit_history·긴급 기록)이
// 통째로 사라지는 길이다(documents/QA_REVIEW.md §3.3, §3.2).
//
// ## 로그아웃과 "서버에 못 닿음"은 다른 사건이다
//
// 지금까지 둘 다 `user === null`로 접혔다. 여기서는 **로컬 세션이 있는지**로 가른다:
//   - 세션이 아예 없다            → 로그아웃. 편집할 계정이 없으니 예전 화면 그대로.
//   - 세션은 있는데 조회가 실패했다 → 오프라인. 이 기기는 누구 것인지 알고, 로컬 캐시도 있다.
//   - 서버가 401/403으로 **명시적으로 거절**했다 → 세션이 죽은 것이므로 로그아웃 취급.
//
// 마지막 줄이 중요하다. "실패했으니 일단 오프라인"으로 뭉뚱그리면 토큰이 취소된 계정이 영원히
// 오프라인 편집 상태로 남는다.

import { isHardcoreChangeAllowed } from './hardcoreLock.js';

/** 옵션 화면이 어떤 모드로 뜨는가. */
export const ACCESS_STATE = Object.freeze({
  /** 서버와 통한다. 기존 동작(서버가 진실의 원천). */
  online: 'online',
  /** 세션은 있는데 서버에 못 닿는다. 로컬 캐시로 띄우고 편집분은 대기시킨다. */
  offline: 'offline',
  /** 세션 자체가 없다. */
  signedOut: 'signedOut'
});

/** 오프라인 편집분을 담아두는 chrome.storage.local 키. */
export const PENDING_SETTINGS_KEY = 'pendingSettingsSync';

/**
 * 대기분 중 서버 기준 재검증에서 거부돼 **버려진** 항목의 사유(messageKey 목록 + 시각).
 * 백그라운드가 쓰고 옵션 화면이 읽어 띄운 뒤 지운다 — 조용히 버리면 사용자는 저장된 줄 안다
 * ([planPendingSettingsPush] 주석 참고).
 */
export const PENDING_REJECTED_KEY = 'pendingSettingsRejected';

/**
 * 서버가 "이 요청은 잘못됐다/이 세션은 무효다"라고 **답한** 상태 코드.
 * 답이 왔다는 건 서버가 살아 있다는 뜻이라, 이건 오프라인이 아니다.
 */
const SERVER_REJECT_STATUSES = new Set([400, 401, 403, 404, 409, 422]);

/** 서버가 살아는 있지만 지금은 못 받는 상태. 잠시 뒤 다시 시도할 값이라 오프라인으로 친다. */
const RETRYABLE_STATUSES = new Set([408, 425, 429]);

/**
 * 이 오류가 "서버에 못 닿았다"인가?
 *
 * pause된 Supabase 프로젝트는 상태 코드 없이 fetch가 통째로 실패하거나(TypeError) 게이트웨이가
 * 5xx를 던지는 두 모양으로 온다. 둘 다 여기서 true다.
 *
 * @param {any} error supabase-js가 돌려준 error 객체(AuthError / PostgrestError / TypeError)
 */
export function isOfflineFailure(error) {
  if (!error) return false;

  const status = Number(error.status ?? error.statusCode);
  if (Number.isFinite(status) && status > 0) {
    if (SERVER_REJECT_STATUSES.has(status)) return false;
    return status >= 500 || RETRYABLE_STATUSES.has(status);
  }

  // PostgREST 오류 코드가 붙어 있다 = PostgREST가 답을 만들어 보냈다 = 서버는 살아 있다.
  // (RLS 거부, 스키마 캐시 문제 등. 여기서 오프라인이라고 판단하면 진짜 버그가 대기분으로 숨는다.)
  if (typeof error.code === 'string' && error.code.startsWith('PGRST')) return false;

  // supabase-js가 네트워크 실패를 감쌀 때 쓰는 이름. 상태 코드가 없어도 이건 확실히 오프라인이다.
  if (typeof error.name === 'string' && error.name.includes('Retryable')) return true;

  // 상태 코드도 서버 코드도 없다 = HTTP 응답 자체를 못 받았다(DNS 실패·타임아웃·pause).
  return true;
}

/**
 * 지금 이 기기가 서버와 어떤 관계인지 판정한다.
 *
 * @param {object} input
 * @param {object|null} input.user  auth.getUser()가 돌려준 사용자
 * @param {any} input.userError     auth.getUser()의 오류
 * @param {object|null} input.session auth.getSession()이 돌려준 **로컬** 세션
 * @param {any} input.sessionError  auth.getSession()의 오류
 * @returns {{state: string, userId: string|null}}
 */
export function resolveAccessState({ user, userError, session, sessionError } = {}) {
  if (user?.id) return { state: ACCESS_STATE.online, userId: user.id };

  // 세션 저장소 자체를 못 읽었으면 이 기기가 누구 것인지도 모른다 — 오프라인 편집을 열 수 없다.
  if (sessionError) return { state: ACCESS_STATE.signedOut, userId: null };

  const sessionUserId = session?.user?.id ?? null;
  if (!sessionUserId) return { state: ACCESS_STATE.signedOut, userId: null };

  // 세션은 있다. 조회가 왜 실패했는가로 갈린다.
  if (isOfflineFailure(userError)) return { state: ACCESS_STATE.offline, userId: sessionUserId };

  // 서버가 답을 했는데 사용자가 없다(401 등) = 이 세션은 더 이상 쓸 수 없다.
  // 오류가 아예 없는 경우도 여기로 온다 — 서버는 멀쩡한데 사용자가 없다는 뜻이라 로그아웃이 맞다.
  return { state: ACCESS_STATE.signedOut, userId: null };
}

/** 화면에 띄울 설정 = 로컬 캐시 위에 아직 서버로 못 올린 대기분을 얹은 것. */
export function applyPendingSettings(cached, pending) {
  return { ...(cached || {}), ...(pending?.settings || {}) };
}

/**
 * 오프라인 저장분을 쌓는다. 여러 번 저장하면 **누적 패치**가 된다 — 매번 통째로 덮어쓰면
 * 앞선 저장에서만 건드린 항목이 사라진다.
 *
 * 계정이 다르면 앞의 대기분은 버린다. A의 오프라인 편집이 B의 계정으로 올라가면 안 된다.
 *
 * @param {object|null} previous 저장돼 있던 대기분
 * @param {object} patch 이번에 바뀐 설정 필드들
 * @param {{userId: string, atMillis: number, resetStreak?: boolean}} meta
 */
export function createPendingSettings(previous, patch, meta = {}) {
  const userId = meta.userId || null;
  const base = previous && previous.userId === userId ? previous : null;
  const atMillis = Number(meta.atMillis);

  return {
    userId,
    settings: { ...(base?.settings || {}), ...(patch || {}) },
    updatedAtMillis: Number.isFinite(atMillis) ? atMillis : 0,
    // 하드코어 해제가 오프라인에서 성립하면 스트릭 리셋도 함께 밀린다. 플래그는 한 번 켜지면
    // 실제로 올릴 때까지 유지된다 — 여기서 꺼지면 "끄면 연속 기록이 초기화된다"는 경고가 허언이 된다.
    resetStreak: !!(base?.resetStreak || meta.resetStreak)
  };
}

/**
 * 저장돼 있던 대기분 중 **지금 로그인한 계정의 것**만 돌려준다.
 * 계정이 다르거나 모양이 깨졌으면 없는 셈 친다(남의 설정을 내 계정에 올리는 사고 방지).
 */
export function readPendingFor(stored, userId) {
  if (!stored || typeof stored !== 'object') return null;
  if (!stored.settings || typeof stored.settings !== 'object') return null;
  if (!userId || stored.userId !== userId) return null;
  return stored;
}

/**
 * 서버가 살아난 뒤 **어느 쪽이 이기는가**.
 *
 * 이 저장소의 기본 계약은 "settings는 서버가 진실의 원천"이다(documents/BACKEND.md). 그런데
 * 오프라인 편집분은 사용자가 **방금 명시적으로 한 변경**이라, 서버 값으로 덮어버리면 "저장했는데
 * 되돌아왔다"가 된다. 그래서 규칙을 하나만 뒤집는다:
 *
 *   대기분이 있으면 그것을 서버에 push하고, 없으면 기존대로 서버를 pull 한다.
 *
 * push할 때 올리는 건 **대기분에 들어 있는 필드뿐**이다. 오프라인이던 시절의 캐시를 통째로
 * 올리면 그 사이 다른 기기가 바꾼 항목까지 옛 값으로 되돌린다.
 *
 * ## 한계: 마지막 쓰기가 이긴다
 *
 * 오프라인인 동안 다른 기기가 같은 항목을 바꿨어도 여기서는 알 방법이 없다(행에 버전도 벡터 시계도
 * 없다). 그 경우 이 push가 그 변경을 덮어쓴다 — 기존 모델(upsert = 마지막 쓰기 승리)을 그대로
 * 유지하는 것이고, 해결하려면 필드 단위 타임스탬프가 필요해서 여기서는 하지 않는다.
 *
 * @param {object} input
 * @param {object|null} input.pending readPendingFor를 통과한 대기분
 * @param {object|null} input.cached 로컬 settingsCache
 * @returns {{action: 'push'|'pull', patch: object|null, resetStreak: boolean, settings: object|null}}
 *          action이 'pull'이면 settings는 null이다 — 서버 행과 캐시를 어떻게 합칠지는 호출부가
 *          이미 소유한 규칙(lib/accountReset.js의 resolveSettingsCacheAfterPull)에 맡긴다.
 */
export function resolveSettingsSyncPlan({ pending, cached } = {}) {
  if (pending) {
    return {
      action: 'push',
      patch: { ...pending.settings },
      resetStreak: !!pending.resetStreak,
      settings: applyPendingSettings(cached, pending)
    };
  }
  return { action: 'pull', patch: null, resetStreak: false, settings: null };
}

/**
 * 대기분을 **올리기 직전에** 다시 판정한다. (QA_REVIEW §10.4)
 *
 * ## 왜 한 번 더 보는가
 *
 * 대기분은 오프라인 편집 시점의 **로컬 캐시**를 기준으로 게이트를 통과한 값이다. 그 사이 다른
 * 기기가 규칙을 더 조였다면(PC 오프라인에서 한도 60분 저장 → 그동안 폰이 10분으로 조임)
 * 그대로 올리는 것은 하드코어 잠금 바깥에서 잠금을 되돌리는 일이 된다. 그래서 push 직전에
 * **서버의 현재 값**을 기준으로 [isHardcoreChangeAllowed]를 다시 태운다 — 왕복이 한 번 는다.
 *
 * ## 거부되면 그 필드는 버린다
 *
 * 남겨두고 다시 시도해봐야 소용이 없다. 하드코어가 켜진 동안 서버 값은 조여지기만 하므로
 * 다음 시도도 같은 이유로 막히고, 대기분이 영영 안 비면 30초마다 같은 요청이 되풀이된다.
 * 대신 **조용히 버리지는 않는다** — 호출부가 [dropped]를 저장소에 남겨 옵션 화면이 "무엇이
 * 왜 반영되지 않았는지"를 띄운다. 알림 대신 화면에 남기는 쪽을 고른 이유는, 이 사건이 급하지
 * 않고(더 강한 규칙이 이미 적용돼 있다) OS 알림은 한 번 지나가면 다시 볼 수 없기 때문이다.
 *
 * 거부되지 않은 필드는 그대로 올린다. 한 번의 오프라인 편집에 여러 항목이 섞여 있을 때
 * 하나 때문에 전부 버리면, 특히 하드코어 해제 요청(게이트가 막지 않는 값)까지 같이 날아간다.
 *
 * @param {object} input
 * @param {object|null} input.pending readPendingFor를 통과한 대기분
 * @param {object|null} input.remote 서버의 현재 settings 행. null이면(행이 아직 없다) 비교할
 *   기준이 없으므로 그대로 올린다.
 * @param {object|null} input.cached 로컬 settingsCache
 * @returns {{patch: object, dropped: Array<{field: string, messageKey: string}>, settings: object}}
 *          patch가 비어 있으면 올릴 게 없다(대기분은 비운다). settings는 push 후의 로컬 캐시 —
 *          버려진 필드는 서버 값으로 되돌려 담는다.
 */
export function planPendingSettingsPush({ pending, remote, cached } = {}) {
  const patch = { ...(pending?.settings || {}) };
  const settings = applyPendingSettings(cached, pending);
  if (!remote) return { patch, dropped: [], settings };

  /** 화면에 띄울 거부 사유(필드 -> messageKey). */
  const dropped = new Map();
  /** 실제로 patch에서 빠진 필드. 사유 목록과 달리 캐시를 되돌릴 대상이라 따로 센다. */
  const droppedFields = new Set();

  // 필드 하나를 빼면 다른 규칙의 비교 기준이 바뀔 수 있어(요일별 한도는 기본 한도를 참조한다)
  // 통과할 때까지 되풀이한다. 한 바퀴마다 최소 한 필드가 빠지므로 반드시 끝난다.
  for (;;) {
    const check = isHardcoreChangeAllowed(remote, { ...remote, ...patch });
    if (check.allowed) break;
    for (const violation of check.violations) dropped.set(violation.field, violation.messageKey);

    const removable = check.violations.filter((violation) => violation.field in patch);
    // 규칙이 지목한 필드가 대기분에 하나도 없다 = 무엇을 빼야 할지 모른다. 통째로 버린다
    // (판단이 애매하면 약화로 본다는 hardcoreLock.js의 기준과 같은 방향).
    const fields = removable.length > 0 ? removable.map((v) => v.field) : Object.keys(patch);
    for (const field of fields) {
      droppedFields.add(field);
      delete patch[field];
    }
  }

  // 버려진 필드는 서버 값이 이긴다. 캐시에 남겨두면 화면에는 반영된 것처럼 보인다.
  for (const field of droppedFields) {
    if (field in remote) settings[field] = remote[field];
  }
  return {
    patch,
    dropped: [...dropped].map(([field, messageKey]) => ({ field, messageKey })),
    settings
  };
}

/**
 * 설정 저장의 **공통 관문**. 온라인 저장과 오프라인 저장이 갈라지기 **전에** 하드코어 판정을 태운다.
 *
 * 여기서 갈라놓으면 "인터넷을 끊고 옵션을 열어 한도를 늘린다"는 새 우회로를 우리 손으로 만드는
 * 셈이다. 오프라인 편집을 열어주는 대가로 반드시 지켜야 하는 한 줄이라, 화면 코드가 아니라
 * 판정 함수 안에 둔다 — 그래야 "오프라인 경로도 관문을 탄다"를 테스트로 고정할 수 있다.
 *
 * @param {object} input
 * @param {string} input.accessState ACCESS_STATE 중 하나
 * @param {object|null} input.current 지금 적용 중인 설정(하드코어 판정의 기준값)
 * @param {object} input.patch 저장하려는 변경
 * @returns {{action: 'blocked'|'server'|'local', violations: Array, settings: object}}
 */
export function planSettingsSave({ accessState, current, patch }) {
  const next = { ...(current || {}), ...(patch || {}) };
  const check = isHardcoreChangeAllowed(current, next);
  if (!check.allowed) {
    return { action: 'blocked', violations: check.violations || [], settings: current || {} };
  }
  return {
    action: accessState === ACCESS_STATE.online ? 'server' : 'local',
    violations: [],
    settings: next
  };
}
