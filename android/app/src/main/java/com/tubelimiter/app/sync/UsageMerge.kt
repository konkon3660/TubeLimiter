package com.tubelimiter.app.sync

/**
 * How much of today's local usage hasn't been reported to the server yet. Zero once a new
 * day starts (`syncedDate` no longer matches `today`) — nothing to carry over.
 */
fun usageDeltaSinceSync(localUsedMillis: Long, syncedDate: String?, syncedMillis: Long, today: String): Long {
    val baseline = if (syncedDate == today) syncedMillis else 0L
    return (localUsedMillis - baseline).coerceAtLeast(0L)
}

/**
 * This device's own usage plus whatever the other devices have already pushed for today.
 * `remoteTotalMillis` already includes this device's last synced contribution, so that part
 * is subtracted back out before adding the (possibly newer) local figure.
 */
fun combinedUsedMillis(localUsedMillis: Long, syncedMillis: Long, remoteTotalMillis: Long): Long {
    val otherDevices = (remoteTotalMillis - syncedMillis).coerceAtLeast(0L)
    return localUsedMillis + otherDevices
}

// --- 긴급 시청 "횟수" ---
// 시간(ms)과 규칙은 같고 단위만 횟수다. 횟수가 로컬에만 남으면 기기를 바꿔 허용 횟수를
// 다시 채울 수 있어서(커밋먼트 장치 우회), 시간과 똑같이 델타로 올리고 합계로 되돌려 받는다.

/** [usageDeltaSinceSync]의 횟수 버전. */
fun emergencyUsesDeltaSinceSync(localUses: Int, syncedDate: String?, syncedUses: Int, today: String): Int =
    usageDeltaSinceSync(localUses.toLong(), syncedDate, syncedUses.toLong(), today).toInt()

/** 버킷 구간([bucketDateKeys])에 속하는 날짜의 횟수만 더한다. */
private fun sumInBucket(bucketDateKeys: List<String>, usesByDate: Map<String, Int>): Int =
    bucketDateKeys.sumOf { usesByDate[it] ?: 0 }

/**
 * 이 기기가 서버 버킷 합계에 기여한 횟수. 지난 날들은 그날그날 동기화됐다고 보고 로컬 기록
 * 그대로 세고(DataStore에는 오늘치 베이스라인만 남는다), 오늘치만 실제로 밀어넣은
 * [syncedTodayUses]를 쓴다. 아직 안 보낸 오늘분까지 내 몫으로 치면 그만큼이 "다른 기기 몫"에서
 * 빠져 남은 횟수가 실제보다 넉넉해진다 — 우회 구멍이 그대로 남는다.
 *
 * 확장 `lib/usageMerge.js`의 `reportedEmergencyUsesInBucket`과 같은 규칙.
 */
fun reportedEmergencyUsesInBucket(localBucketUses: Int, localTodayUses: Int, syncedTodayUses: Int): Int =
    (localBucketUses - localTodayUses).coerceAtLeast(0) + syncedTodayUses.coerceAtLeast(0)

/**
 * 다른 기기가 이번 버킷에서 쓴 긴급 시청 횟수. [combinedUsedMillis]와 같은 수학이고, 단위가
 * 횟수이며 구간이 하루가 아니라 버킷 전체라는 점만 다르다. 서버 합계가 이 기기 보고분보다
 * 작으면(오프라인이라 못 얻었거나 아직 덜 반영됐으면) 0이라, 그때는 로컬 값만으로 판정한다.
 *
 * 확장 `remainingEmergencyUses`가 로컬 잔여에서 빼는 값과 같은 값이다 — 클램프를 날짜별이 아니라
 * 버킷 합계에 한 번만 거는 것도 확장과 맞춘 것. 규칙이 갈라지면 두 클라이언트가 서로 다른
 * 잔여 횟수를 보여준다.
 */
fun otherDeviceEmergencyUses(
    bucketDateKeys: List<String>,
    localUsesByDate: Map<String, Int>,
    remoteUsesByDate: Map<String, Int>,
    todayKey: String,
    syncedTodayUses: Int,
): Int {
    val reported = reportedEmergencyUsesInBucket(
        localBucketUses = sumInBucket(bucketDateKeys, localUsesByDate),
        localTodayUses = if (todayKey in bucketDateKeys) localUsesByDate[todayKey] ?: 0 else 0,
        syncedTodayUses = syncedTodayUses,
    )
    return (sumInBucket(bucketDateKeys, remoteUsesByDate) - reported).coerceAtLeast(0)
}

/** 이 기기 로컬 기록 위에 [otherDeviceEmergencyUses]만 얹은 버킷 전체 횟수. */
fun combinedEmergencyUses(
    bucketDateKeys: List<String>,
    localUsesByDate: Map<String, Int>,
    remoteUsesByDate: Map<String, Int>,
    todayKey: String,
    syncedTodayUses: Int,
): Int = sumInBucket(bucketDateKeys, localUsesByDate) +
    otherDeviceEmergencyUses(bucketDateKeys, localUsesByDate, remoteUsesByDate, todayKey, syncedTodayUses)
