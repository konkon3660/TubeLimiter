package com.tubelimiter.app.limit

import com.tubelimiter.app.R
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

/**
 * String resource for the one-line reason, shared by the home screen's mode badge and the
 * block overlay's title. A resource id rather than the text itself so this stays a pure
 * function the unit tests can reach.
 */
fun BlockReason.messageRes(): Int = when (this) {
    BlockReason.FOCUS_MODE -> R.string.block_reason_focus_mode
    BlockReason.SCHEDULED -> R.string.block_reason_scheduled
    BlockReason.MANUAL -> R.string.block_reason_manual
    BlockReason.USAGE_LIMIT -> R.string.block_reason_usage_limit
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

/**
 * [planEmergencyReset]의 결론.
 *
 * - [RESET]: 시간이 흘러 버킷이 끝났다. 허용 횟수를 다시 채운다.
 * - [CARRY_OVER]: 아직 같은 버킷인데 **주기만 바뀌었다.** 이미 쓴 횟수를 새 버킷이 이어받도록
 *   버킷 표식만 옮기고 남은 횟수는 건드리지 않는다.
 * - [NONE]: 표식도 남은 횟수도 그대로. 저장소를 쓸 필요가 없다.
 */
enum class EmergencyResetAction { RESET, CARRY_OVER, NONE }

/** [planEmergencyReset]이 돌려주는 새 버킷 표식과 채울 횟수. */
data class EmergencyResetPlan(
    val action: EmergencyResetAction,
    /** 새 버킷 키(= 버킷 시작일). [EmergencyResetAction.NONE]이면 지금 값과 같다. */
    val resetKey: String,
    /** 그 키를 만든 주기. 키와 **한 쌍으로** 저장해야 다음 판정이 구별을 할 수 있다. */
    val frequency: EmergencyResetFrequency,
    /** [EmergencyResetAction.RESET]일 때 채울 허용 횟수. */
    val allowance: Int,
)

/**
 * 긴급 시청 허용 횟수를 지금 리셋해야 하는지 판정한다. 확장
 * `extension/src/lib/dateRollover.js`의 `planEmergencyReset`과 **같은 규칙**이고, 두 쪽이
 * 갈라지면 같은 계정의 두 기기가 서로 다른 잔여를 보여준다
 * (documents/BACKEND.md "긴급 시청 횟수 버킷 합산 규칙").
 *
 * ## 왜 "키가 달라졌다"만으로는 안 되는가 (QA_REVIEW §10.2)
 *
 * 버킷 키는 주기에 따라 오늘/주 시작일/월 시작일이라 **주기를 바꾸는 것만으로도** 키가
 * 달라진다. 예전 규칙(저장된 키 != 지금 키면 리셋)에서는 하드코어를 켠 채 daily→weekly→
 * monthly로 "조이기만" 해도 그 자리에서 횟수가 두 번 리필됐다 — 하드코어 게이트는 조이는
 * 방향을 통과시키므로([findHardcoreViolations]) 잠금 안에서 뚫리는 구멍이었다.
 *
 * 그래서 **"시간이 흘러 새 버킷이 시작된 것"과 "주기가 바뀐 것"을 구별한다.** 구별 수단은
 * 마지막으로 적용된 주기를 키와 함께 저장해두는 것이다([lastFrequency], DataStore의
 * `emergency_reset_bucket_frequency`):
 *
 *   - **그때 주기로 다시 계산한 오늘의 키**가 저장된 키와 다르다 → 날짜가 흘러 버킷이 끝났다
 *     → [EmergencyResetAction.RESET].
 *   - 같다 → 아직 같은 버킷 안이다. 키가 달라진 이유는 주기 변경뿐이므로
 *     [EmergencyResetAction.CARRY_OVER].
 *
 * 그 결과 주기 변경은 어느 방향이든 리필하지 않고, 주기를 바꾼 뒤 **실제로** 새 버킷이
 * 시작되면 그때 정상적으로 리셋된다.
 *
 * 서버 합산 구간은 여전히 [emergencyBucketStartDate] 하나로 정해진다 — carryOver로 키가 주
 * 시작일까지 넓어지면 합산 구간([emergencyBucketDateKeys])도 같이 넓어지고, 그 구간에서 이
 * 기기가 이미 보고한 몫은 같은 구간으로 빼지므로 이중 차감이 생기지 않는다.
 *
 * @param lastKey 저장돼 있던 버킷 키. 없으면(설치 직후) 리셋으로 깔아준다.
 * @param lastFrequency 그 키를 만들 때 적용됐던 주기. 이 값이 생기기 전 저장소에는 없다(null) —
 *   그때는 "지금 주기와 같았다"고 보고 예전과 똑같이 판정하되, 다음 판정부터 구별이 되도록
 *   표식만 남긴다([EmergencyResetAction.CARRY_OVER]).
 */
fun planEmergencyReset(
    lastKey: String?,
    lastFrequency: EmergencyResetFrequency?,
    frequency: EmergencyResetFrequency,
    allowance: Int,
    date: LocalDate,
): EmergencyResetPlan {
    val resetKey = emergencyResetKey(frequency, date)
    fun plan(action: EmergencyResetAction) = EmergencyResetPlan(action, resetKey, frequency, allowance)

    // 기록이 아예 없다(설치 직후·계정 삭제 후) = 깔아줄 첫 버킷이다.
    if (lastKey == null) return plan(EmergencyResetAction.RESET)

    // 저장된 키를 **그때 주기로** 다시 계산해 오늘과 맞춰본다. 다르면 날짜가 흘러간 것이다.
    if (lastKey != emergencyResetKey(lastFrequency ?: frequency, date)) {
        return plan(EmergencyResetAction.RESET)
    }

    // 여기부터는 "아직 같은 버킷 안". 키·주기가 달라졌으면 표식만 새 버킷으로 옮긴다.
    // 표식이 아예 없던 저장소(lastFrequency == null)도 이 길로 한 번 들어와 표식을 남긴다.
    if (lastKey != resetKey || lastFrequency != frequency) return plan(EmergencyResetAction.CARRY_OVER)
    return plan(EmergencyResetAction.NONE)
}

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
