// 차단 판정 순수 로직. service-worker.js의 checkUsageAndBlock에서 "무엇을 왜 차단할지"만
// 떼어낸 것으로, storage 읽기/쓰기·탭 조작·알림은 전부 호출자(서비스워커) 몫이다 —
// 안드로이드 limit/BlockDecision.kt와 같은 구조("판정은 값을 반환하고 저장은 호출자가 한다").
// 이 파일은 chrome.*를 참조하지 않으므로 node:test에서 그대로 돌릴 수 있다.
//
// 우선순위(위가 이길수록 강함): 집중 모드 > 예약 차단 > 긴급 시청(우회) > 수동 차단 > 사용 한도.
// 집중 모드와 예약 차단은 "한도와 무관하게" 무조건 차단하는 커밋먼트 장치라 긴급 시청으로
// 우회할 수 없다 — requestEmergency 핸들러도 이 두 상태에선 애초에 발급을 거부한다.
// 안드로이드 BlockInputs.blockReason()과 같은 순서다 — 한쪽을 고치면 다른 쪽도 맞춰야 한다.

// content/content.js가 이 문자열로 차단 사유 문구를 고른다 — 값을 바꾸면 그쪽도 같이 고쳐야 한다.
export const BLOCK_REASON = Object.freeze({
  focusMode: 'focusMode',
  scheduledBlock: 'scheduledBlock',
  manualBlock: 'manualBlock',
  usageLimit: 'usageLimit',
  alwaysBlockShorts: 'alwaysBlockShorts'
});

/**
 * 한도 초과 여부. computeLimitForDate는 "무제한"을 Infinity로 돌려주므로
 * 별도 무제한 분기 없이 비교만으로 걸러진다 (안드로이드는 UNLIMITED_MILLIS 센티널이라
 * isUnlimited() 검사가 따로 붙어 있는데, 결과는 같다).
 * 경계는 "정확히 도달하면 차단"(>=)이다 — 한도 30분을 딱 채운 순간부터 막힌다.
 */
export function isUsageLimitExceeded(usedMs, limitMs) {
  return usedMs >= limitMs;
}

/**
 * 전역(브라우저 전체) 차단 판정.
 *
 * @param {object} inputs
 * @param {number} inputs.usedMs 오늘 사용량(다른 기기 몫 합산 후)
 * @param {number} inputs.limitMs 오늘 한도 (무제한이면 Infinity)
 * @param {boolean} inputs.focusModeActive
 * @param {boolean} inputs.scheduleBlockActive
 * @param {boolean} inputs.emergencyModeActive
 * @param {boolean} inputs.manuallyBlocked
 * @returns {{shouldBlock: boolean, reason: string, displayBlocked: boolean, trackingBlocked: boolean}}
 */
export function resolveBlockDecision({
  usedMs = 0,
  limitMs = Infinity,
  focusModeActive = false,
  scheduleBlockActive = false,
  emergencyModeActive = false,
  manuallyBlocked = false
} = {}) {
  const usageLimitExceeded = isUsageLimitExceeded(usedMs, limitMs);

  let shouldBlock = false;
  let reason = '';

  if (focusModeActive) {
    shouldBlock = true;
    reason = BLOCK_REASON.focusMode;
  } else if (scheduleBlockActive) {
    shouldBlock = true;
    reason = BLOCK_REASON.scheduledBlock;
  } else if (emergencyModeActive) {
    shouldBlock = false;
  } else if (manuallyBlocked) {
    shouldBlock = true;
    reason = BLOCK_REASON.manualBlock;
  } else if (usageLimitExceeded) {
    shouldBlock = true;
    reason = BLOCK_REASON.usageLimit;
  }

  return {
    shouldBlock,
    reason,
    // storage의 isYoutubeBlocked(팝업 표시·긴급 시청 요청 가능 여부에 쓰임)는 shouldBlock과
    // 미묘하게 다르다: 긴급 시청 중에도 "차단해둔 상태이긴 한데 지금만 풀린 것"으로 봐야 하므로,
    // 긴급이 뚫어주는 사유(수동 차단)는 여기선 그대로 살아있다. 한도 초과만 긴급 시청 중
    // 제외되는데, 그래야 팝업이 "남은 시간 없음"과 "지금은 볼 수 있음"을 같이 보여준다.
    displayBlocked: focusModeActive || scheduleBlockActive || manuallyBlocked ||
      (usageLimitExceeded && !emergencyModeActive),
    // 사용시간 집계를 멈춰야 하는지는 "지금 실제로 볼 수 있는가"와 같은 질문이라 shouldBlock과
    // 같은 값이다. 예전엔 이 판정에도 displayBlocked를 썼는데, 그러면 같은 긴급 시청인데도
    // 한도 초과 위에서 쓴 시간은 집계되고 수동 차단 위에서 쓴 시간은 통째로 빠지는 비대칭이
    // 생겼다(안드로이드엔 이 게이트 자체가 없어 늘 집계된다). 긴급 시청으로 허용된 시간은
    // 차단 사유와 무관하게 총 시청시간에도, 긴급분(emergency_ms)에도 들어가야 한다.
    // 집중 모드/예약 차단은 애초에 긴급 시청으로 뚫리지 않으므로 여기서도 계속 집계가 멈춘다.
    trackingBlocked: shouldBlock
  };
}

/** 화이트리스트는 URL 부분 문자열 매칭이다 (옵션에서 "youtube.com/@channel" 같은 조각을 넣는다). */
export function isWhitelistedUrl(url, whitelist) {
  if (!url || !Array.isArray(whitelist)) return false;
  return whitelist.some((entry) => url.includes(entry));
}

export function isShortsUrl(url) {
  return !!url && url.includes('youtube.com/shorts');
}

/**
 * 탭 하나에 대한 최종 판정. 전역 판정을 깔고 시작해서 탭별 예외(화이트리스트/Shorts)를 얹는다.
 *
 * 집중 모드와 예약 차단은 화이트리스트보다 먼저 검사한다 — 진짜 커밋먼트 장치가 되려면
 * 예외 통로가 없어야 하므로 화이트리스트로도 뚫리면 안 된다.
 *
 * @param {object} inputs resolveBlockDecision과 같은 입력
 * @param {object} tab
 * @param {boolean} tab.isWhitelisted
 * @param {boolean} tab.isShortsTab
 * @param {boolean} tab.alwaysBlockShorts
 * @returns {{shouldBlock: boolean, reason: string}}
 */
export function resolveTabBlock(inputs, { isWhitelisted = false, isShortsTab = false, alwaysBlockShorts = false } = {}) {
  const { shouldBlock, reason } = resolveBlockDecision(inputs);

  if (inputs?.focusModeActive) return { shouldBlock: true, reason: BLOCK_REASON.focusMode };
  if (inputs?.scheduleBlockActive) return { shouldBlock: true, reason: BLOCK_REASON.scheduledBlock };
  // 사유(reason)는 차단할 때만 쓰이므로 아래 두 갈래에선 전역 사유를 그대로 흘려보낸다.
  if (inputs?.emergencyModeActive) return { shouldBlock: false, reason };
  if (isWhitelisted) return { shouldBlock: false, reason };
  if (alwaysBlockShorts && isShortsTab) return { shouldBlock: true, reason: BLOCK_REASON.alwaysBlockShorts };

  return { shouldBlock, reason };
}
