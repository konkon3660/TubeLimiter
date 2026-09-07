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

// --- 긴급 시청 "횟수" ---
// 횟수도 "내가 보고한 몫을 서버 합계에서 빼면 다른 기기 몫"이라는 수학이 시간과 똑같다.
// 델타 계산은 usageDeltaSinceSync를 단위만 ms→회로 바꿔 그대로 재사용한다. 다만 남은 횟수는
// 다른 기기 몫을 "더하는" 게 아니라 "빼는" 방향이고, 리셋 주기가 주/월일 수 있어서 합산 구간이
// 하루가 아니라 버킷 전체다 — 그 두 가지만 아래 함수들로 따로 둔다.

/**
 * emergency_history({ 'YYYY-MM-DD': { uses, ms } })에서 버킷 구간의 횟수만 합산한다.
 * 서버 daily_usage는 날짜별 행이라 weekly/monthly 설정에서는 오늘 행 하나만 봐선 안 되고
 * 버킷 시작일부터 오늘까지를 다 더해야 잔여 횟수가 맞는다.
 * 날짜 키가 'YYYY-MM-DD' 고정폭이라 문자열 비교만으로 구간 판정이 된다.
 *
 * @param {Record<string, {uses?: number}>|null|undefined} history
 * @param {string} bucketStartDate 버킷 시작일(= dateRollover.js의 emergencyResetDate)
 * @param {string} endDate 보통 오늘. 시계가 앞선 기기가 남긴 미래 날짜는 세지 않는다.
 */
export function sumEmergencyUsesInBucket(history, bucketStartDate, endDate) {
  let total = 0;
  for (const [date, entry] of Object.entries(history || {})) {
    if (date < bucketStartDate || date > endDate) continue;
    total += entry?.uses || 0;
  }
  return total;
}

/**
 * 이 기기가 서버 버킷 합계에 기여한 횟수.
 * 지난 날들은 그날그날 동기화됐다고 보고 로컬 기록 그대로 세고, 오늘치만 실제로 서버에 밀어넣은
 * 만큼(syncedTodayUses)을 쓴다. 아직 안 보낸 오늘분까지 내 몫으로 치면 그만큼이 "다른 기기 몫"에서
 * 빠져 남은 횟수가 실제보다 넉넉해진다 — 우회 구멍이 그대로 남는다.
 */
export function reportedEmergencyUsesInBucket(localBucketUses, localTodayUses, syncedTodayUses) {
  const pastDays = Math.max(0, (localBucketUses || 0) - (localTodayUses || 0));
  return pastDays + Math.max(0, syncedTodayUses || 0);
}

/**
 * 다른 기기가 이 버킷에서 이미 쓴 만큼을 로컬 잔여 횟수에서 뺀 값.
 * remoteBucketUses가 0이거나(오프라인·로그아웃이라 서버 합계를 못 얻음) 내 보고분보다 작으면
 * 빼는 값이 0이라 로컬 값 그대로다 — 서버 합계는 "얻어지면 반영되는 보너스"이지 전제가 아니다
 * (documents/BACKEND.md). 네트워크가 죽었다고 긴급 시청이 막히면 안 된다.
 */
export function remainingEmergencyUses(localRemaining, reportedBucketUses, remoteBucketUses) {
  const otherDevices = Math.max(0, (remoteBucketUses || 0) - (reportedBucketUses || 0));
  return Math.max(0, (localRemaining || 0) - otherDevices);
}
