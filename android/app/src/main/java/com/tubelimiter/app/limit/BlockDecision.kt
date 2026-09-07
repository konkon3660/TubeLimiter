package com.tubelimiter.app.limit

import com.tubelimiter.app.usage.monthStartDate
import com.tubelimiter.app.usage.weekStartDate
import java.time.LocalDate

const val EMERGENCY_DURATION_MILLIS = 5 * 60 * 1000L

/** Turning hardcore mode off is deliberately slow so it cannot be flipped on impulse. */
const val HARDCORE_DISABLE_COOLDOWN_MILLIS = 60 * 60 * 1000L

/**
 * Stopping an already-active focus session is deliberately slow too, mirroring hardcore's
 * disable cooldown, so a moment of impulse cannot cut a commitment session short. This does
 * NOT apply to canceling a delayed session that hasn't started blocking yet - that stays instant.
 */
const val FOCUS_STOP_COOLDOWN_MILLIS = 10 * 60 * 1000L

/**
 * Emergency grants are additionally rate-limited on top of the daily/weekly/monthly use-count
 * allowance, so mashing the button cannot chain grants back to back.
 */
const val EMERGENCY_GRANT_COOLDOWN_MILLIS = 15 * 1000L

/**
 * How much of the window [fromMillis, toMillis] fell inside an emergency pass granted at
 * [grantedAtMillis] (which runs for [EMERGENCY_DURATION_MILLIS]).
 *
 * Emergency-watched time stays in the day's usage total, so settling a streak needs to know how
 * much of a tick's time was covered by the pass. Taking the whole tick whenever the pass was
 * active would over-count the ticks that straddle its start or end, so only the overlap counts.
 * Port of the extension's `emergencyOverlapMs`.
 */
fun emergencyOverlapMillis(fromMillis: Long, toMillis: Long, grantedAtMillis: Long?): Long {
    if (grantedAtMillis == null) return 0L
    val start = maxOf(fromMillis, grantedAtMillis)
    val end = minOf(toMillis, grantedAtMillis + EMERGENCY_DURATION_MILLIS)
    return (end - start).coerceAtLeast(0L)
}

enum class BlockReason { FOCUS_MODE, SCHEDULED, MANUAL, USAGE_LIMIT }

enum class EmergencyResetFrequency { DAILY, WEEKLY, MONTHLY }

data class BlockInputs(
    val usedMillis: Long,
    val limitMillis: Long,
    val emergencyActive: Boolean = false,
    val focusModeActive: Boolean = false,
    val scheduleBlockActive: Boolean = false,
    val manuallyBlocked: Boolean = false,
)

/**
 * Precedence ported from the extension's `checkUsageAndBlock`: focus mode and a scheduled
 * block window are both deliberate commitment devices that block "regardless of the limit",
 * so neither can be bypassed by an emergency pass - they outrank it. Below that, an active
 * emergency pass beats a manual block and the daily limit, matching the original behaviour.
 */
fun BlockInputs.blockReason(): BlockReason? = when {
    focusModeActive -> BlockReason.FOCUS_MODE
    scheduleBlockActive -> BlockReason.SCHEDULED
    emergencyActive -> null
    manuallyBlocked -> BlockReason.MANUAL
    !isUnlimited(limitMillis) && usedMillis >= limitMillis -> BlockReason.USAGE_LIMIT
    else -> null
}

fun BlockReason.message(): String = when (this) {
    BlockReason.FOCUS_MODE -> "집중 모드가 켜져 있어요"
    BlockReason.SCHEDULED -> "예약된 차단 시간이에요"
    BlockReason.MANUAL -> "직접 차단해 둔 상태예요"
    BlockReason.USAGE_LIMIT -> "오늘 한도를 다 썼어요"
}

/**
 * 긴급 시청 허용 횟수가 리셋되는 버킷의 첫 날. 서버 `daily_usage`는 날짜별 행이라
 * 주간/월간 리셋에서는 오늘 행 하나만 봐서는 버킷 합계를 알 수 없다 — 어느 날짜부터
 * 긁어와야 하는지를 정하는 게 이 함수다. 날짜는 4시 컷오프 기준
 * ([com.tubelimiter.app.usage.effectiveDate])으로 들어온다.
 */
fun emergencyBucketStartDate(frequency: EmergencyResetFrequency, date: LocalDate): LocalDate =
    when (frequency) {
        EmergencyResetFrequency.DAILY -> date
        EmergencyResetFrequency.WEEKLY -> weekStartDate(date)
        EmergencyResetFrequency.MONTHLY -> monthStartDate(date)
    }

/**
 * The key an emergency-use allowance is bucketed under. When it changes, the
 * remaining uses reset. 버킷 시작일과 같은 값이라 [emergencyBucketStartDate]에 위임한다 —
 * 리셋 시점과 합계 조회 범위가 갈라지면 주간/월간에서 조용히 어긋난다.
 */
fun emergencyResetKey(frequency: EmergencyResetFrequency, date: LocalDate): String =
    emergencyBucketStartDate(frequency, date).toString()

/** 버킷 시작일부터 [date]까지의 날짜 키. 서버에서 긁어온 행을 이 목록으로 합산한다. */
fun emergencyBucketDateKeys(frequency: EmergencyResetFrequency, date: LocalDate): List<String> {
    val start = emergencyBucketStartDate(frequency, date)
    // 시계가 뒤로 갔거나 저장된 날짜가 미래면 start > date가 될 수 있다. 빈 목록 대신
    // 최소한 오늘은 넣어야 오늘 쓴 횟수가 판정에서 통째로 빠지지 않는다.
    if (start.isAfter(date)) return listOf(date.toString())

    val keys = mutableListOf<String>()
    var cursor = start
    while (!cursor.isAfter(date)) {
        keys += cursor.toString()
        cursor = cursor.plusDays(1)
    }
    return keys
}

/**
 * 화면과 차단 오버레이가 보여줄, 그리고 승인 판정이 쓸 남은 긴급 시청 횟수.
 * 로컬 카운트다운([localRemaining], 없으면 [allowance])에서 **다른 기기가 이번 버킷에 이미 쓴
 * 몫**만 더 뺀다. 서버 합계를 못 받았거나 로그아웃이면 [otherDeviceUses]가 0이라 기존
 * 로컬 전용 동작 그대로다 — 네트워크가 죽었다고 긴급 시청이 막히면 안 되기 때문.
 */
fun effectiveEmergencyRemaining(localRemaining: Int?, allowance: Int, otherDeviceUses: Int): Int =
    ((localRemaining ?: allowance) - otherDeviceUses).coerceAtLeast(0)

fun shouldDisableHardcore(disableRequestedAtMillis: Long?, nowMillis: Long): Boolean =
    disableRequestedAtMillis != null &&
        nowMillis >= disableRequestedAtMillis + HARDCORE_DISABLE_COOLDOWN_MILLIS

fun hardcoreCooldownRemainingMillis(disableRequestedAtMillis: Long?, nowMillis: Long): Long {
    if (disableRequestedAtMillis == null) return 0L
    return (disableRequestedAtMillis + HARDCORE_DISABLE_COOLDOWN_MILLIS - nowMillis).coerceAtLeast(0L)
}

/**
 * The moment an active focus session actually turns off: whichever comes first between the
 * cooldown ending (counted from the stop request) and the session's own natural end. A pending
 * stop request must never extend a session past when it was going to end anyway. Mirrors the
 * extension's `resolveFocusStopTime` (extension/src/lib/focusMode.js).
 */
fun resolveFocusStopTime(
    naturalEndMillis: Long?,
    stopRequestedAtMillis: Long?,
    cooldownMillis: Long = FOCUS_STOP_COOLDOWN_MILLIS,
): Long? {
    if (stopRequestedAtMillis == null) return naturalEndMillis

    val cooldownEndMillis = stopRequestedAtMillis + cooldownMillis
    if (naturalEndMillis == null) return cooldownEndMillis

    return minOf(naturalEndMillis, cooldownEndMillis)
}

/** How much longer until the anti-mash cooldown after an emergency grant clears, if any. */
fun emergencyGrantCooldownRemainingMillis(
    lastGrantedAtMillis: Long?,
    nowMillis: Long,
    cooldownMillis: Long = EMERGENCY_GRANT_COOLDOWN_MILLIS,
): Long {
    if (lastGrantedAtMillis == null) return 0L
    return (lastGrantedAtMillis + cooldownMillis - nowMillis).coerceAtLeast(0L)
}
