// 긴급 시청을 연달아 눌러 남용하지 못하도록, 한 번 부여되고 나면 최소 이 시간이 지나야
// 다음 요청을 다시 받아준다. 일/주/월 단위 사용 횟수 제한과는 별개의, 추가 관문이다.
export const EMERGENCY_GRANT_COOLDOWN_MS = 15 * 1000;

// 한 번 부여받으면 이만큼 차단이 풀린다.
export const EMERGENCY_DURATION_MS = 5 * 60 * 1000;

/**
 * 트래킹 구간 [fromMs, toMs]가 긴급 시청 창(부여 시각 ~ +EMERGENCY_DURATION_MS)과 겹친 시간.
 *
 * 긴급 시청 중 본 시간도 usage_history에는 그대로 쌓이지만(총 시청시간은 사실대로 기록),
 * 스트릭 판정에서 빼주려면 "그 구간 중 얼마가 긴급 시청분이었나"를 알아야 한다.
 * emergencyModeActive 플래그만 보고 구간 전체를 긴급분으로 치면 창의 시작/끝을 걸친 구간이
 * 통째로 긴급분이 되어버리므로, 실제 겹친 만큼만 계산한다. 부여 시각(last_emergency_granted_at)은
 * 창이 끝난 뒤에도 남아 있어서, 타이머가 플래그를 내린 뒤에 정산되는 구간도 제대로 잘린다.
 */
export function emergencyOverlapMs(fromMs, toMs, grantedAtMs) {
  if (!Number.isFinite(grantedAtMs) || !Number.isFinite(fromMs) || !Number.isFinite(toMs)) return 0;
  const start = Math.max(fromMs, grantedAtMs);
  const end = Math.min(toMs, grantedAtMs + EMERGENCY_DURATION_MS);
  return Math.max(0, end - start);
}
