// 여러 기기(이 확장 + 안드로이드 앱)가 같은 계정을 공유할 때 오늘 사용량을 합치는 순수 함수.
// 안드로이드의 sync/UsageMerge.kt와 같은 알고리즘 — 한쪽을 고치면 다른 쪽도 맞춰야 한다.

// 서버에 아직 알리지 않은 오늘치 사용량만 델타로 돌려준다.
// 날짜가 바뀌면(syncedDate !== today) 기준을 0으로 되돌린다.
export function usageDeltaSinceSync(localUsedMs, syncedDate, syncedMs, today) {
  const baseline = syncedDate === today ? (syncedMs || 0) : 0;
  return Math.max(0, localUsedMs - baseline);
}

// 이 기기가 이미 보고한 만큼(syncedMs)을 서버 합계(remoteTotalMs)에서 빼서
// "다른 기기 몫"만 이 기기의 최신 로컬 값 위에 더한다.
export function combinedUsedMillis(localUsedMs, syncedMs, remoteTotalMs) {
  const otherDevices = Math.max(0, remoteTotalMs - (syncedMs || 0));
  return localUsedMs + otherDevices;
}
