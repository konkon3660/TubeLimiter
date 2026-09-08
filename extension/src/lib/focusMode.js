// 집중 모드를 "종료 요청"해도 하드코어 모드처럼 즉시 끄지 않고, 이 시간이 지난 뒤에야 실제로
// 꺼지게 해서 충동적으로 껐다 켰다 하지 못하게 막는다. 요청 후 쿨다운이 지나기 전에 취소되면
// 이 값은 쓰이지 않는다 (focusStopRequestedAt이 null로 되돌아가므로).
//
// 단, "지연(분) 대기 중"인 아직 시작 전의 예약을 취소하는 것은 이 쿨다운 대상이 아니다 —
// 그건 애초에 차단을 시작한 적이 없으므로 즉시 취소되어도 문제없다 (service-worker.js의
// stopFocusMode 핸들러에서 별도로 분기됨).
export const FOCUS_STOP_COOLDOWN_MS = 10 * 60 * 1000;

/**
 * 실제로 집중 모드가 꺼져야 하는 시각을 계산한다.
 * "종료 요청 후 쿨다운이 다 지난 시각"과 "원래부터 예정되어 있던 자연 종료 시각" 중
 * 더 빠른 쪽이다 — 자연 종료가 얼마 안 남았는데 쿨다운 때문에 오히려 늘어나면 안 되므로.
 *
 * @param {number|null} naturalEndMillis 집중 모드가 원래 끝나기로 되어 있던 시각 (없으면 null)
 * @param {number|null} stopRequestedAtMillis 종료 요청이 들어온 시각 (요청 없으면 null)
 * @param {number} cooldownMs 종료 요청 후 실제로 꺼지기까지 걸리는 시간
 * @returns {number|null} 실제로 꺼져야 하는 시각. 요청도 자연 종료 시각도 없으면 null.
 */
export function resolveFocusStopTime(
  naturalEndMillis,
  stopRequestedAtMillis,
  cooldownMs = FOCUS_STOP_COOLDOWN_MS
) {
  if (stopRequestedAtMillis == null) return naturalEndMillis ?? null;

  const cooldownEndMillis = stopRequestedAtMillis + cooldownMs;
  if (naturalEndMillis == null) return cooldownEndMillis;

  return Math.min(naturalEndMillis, cooldownEndMillis);
}
