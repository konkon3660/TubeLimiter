package com.tubelimiter.app.limit

import com.tubelimiter.app.data.Settings

/**
 * 하드코어 모드가 "무엇을 못 바꾸게 하는가"의 규칙. 확장 `extension/src/lib/hardcoreLock.js`의
 * `findHardcoreViolations`를 그대로 옮긴 것이고, **두 쪽이 갈라지면 안 되는 계약**이다
 * (documents/BACKEND.md "하드코어 잠금 범위"). 한쪽만 고치면 같은 계정에서 폰으로는 되고
 * PC로는 안 되는 우회가 생긴다.
 *
 * ## 왜 입력 disabled만으로는 부족한가
 *
 * 예전 잠금은 [com.tubelimiter.app.ui.SettingsScreen]에서 한도 입력을 `enabled = false`로
 * 만드는 게 전부였다. 그래서 하드코어를 켜둔 채로도 긴급 시청 허용 횟수 상향, 리셋 주기 단축,
 * 예약 차단 삭제가 그대로 됐고, 그 값이 서버를 거쳐 PC의 캐시로도 들어갔다. 화면에서 가리는
 * 것과 규칙으로 막는 것은 다르고, 이 파일은 후자다 — 저장 직전에 이전 값과 저장하려는 값을
 * 비교하므로 어느 화면에서 왔든, 오프라인 저장이든 같은 판정을 거친다
 * ([com.tubelimiter.app.sync.SyncRepository.saveSettings]가 유일한 관문이다).
 *
 * ## 잠그는 기준: "약화만 금지"
 *
 * 전부 잠그면 하드코어 중에 규칙을 **더 세게** 만들 수도 없다 — 한도를 줄이거나 예약 차단을
 * 추가하는 것까지 막히면, 스스로를 더 옥죄려는 사용자가 하드코어를 끄고(1시간 대기 + 스트릭
 * 리셋) 다시 켜야 한다. 커밋먼트 장치가 커밋먼트를 방해하는 셈이라, 기준을 "차단이 약해지는
 * 방향"으로 잡는다.
 *
 * 판단이 애매한 값은 **약화로 본다**(막는다). 잘못 막으면 사용자가 1시간 기다렸다 바꾸면
 * 그만이지만, 잘못 열어주면 그게 곧 우회로다.
 *
 * ## 확장에 있고 여기 없는 것
 *
 * `whitelist` · `always_block_shorts` · `shorts_limit_ms`는 **브라우저 전용 컬럼**이라 판정
 * 대상이 아니다. 안드로이드는 이 세 컬럼을 select에도 upsert payload에도 넣지 않고
 * ([com.tubelimiter.app.sync.SETTINGS_COLUMNS]), [Settings]에 필드조차 없다 — 소유하지 않는
 * 값을 판정하면 "이 기기가 그 값을 안다"는 뜻이 되어 그 계약이 깨진다.
 *
 * ## 여기 있고 확장에 없는 것
 *
 * [HardcoreViolation.LIMIT_MODE]는 안드로이드에만 있다. 확장 옵션 화면에는 한도 모드가 별도
 * 컨트롤로 없지만 안드로이드 설정 화면에는 "매일 / 요일별" 칩이 있어서, 일일 한도 15분 +
 * 요일별 120분인 상태에서 모드만 요일별로 넘기면 **값은 하나도 안 건드리고** 실제 한도가
 * 여덟 배가 된다. 확장 규칙만 그대로 옮기면 이 칩이 그 자리에서 우회로가 되므로 한 항목을
 * 더 둔다. 판정은 "모드가 바뀌었고, 그 결과 어느 요일에서든 실제 한도가 커진다"일 때만이라
 * 강화 방향(요일별로 넘기면서 전부 줄이기)은 그대로 통과한다.
 */
enum class HardcoreViolation(
    /** 어느 `settings` 컬럼 때문에 막혔는가. 화면 문구는 UI가 붙인다(확장의 messageKey와 같은 역할). */
    val column: String,
) {
    DAILY_LIMIT("daily_limit_ms"),
    BY_DAY_LIMIT("daily_limit_by_day"),
    LIMIT_MODE("daily_limit_reset_frequency"),
    EMERGENCY("emergency_config"),
    SCHEDULE("scheduled_blocks"),
}

private const val MINUTES_PER_DAY = 24 * 60

/**
 * 무제한(0 이하 — 안드로이드의 [UNLIMITED_MINUTES]와 "0분 = 한도 없음" 컨벤션이 둘 다 여기
 * 걸린다)은 가장 느슨한 값이므로 비교에서 무한대로 접는다. 확장 `limitValue(ms)`와 같은 규칙.
 */
private fun limitValue(minutes: Int): Long = if (minutes <= 0) Long.MAX_VALUE else minutes.toLong()

/** [dayIndex]에 실제로 적용되는 한도. 모드 변경이 한도를 얼마나 풀어주는지는 이걸로만 잰다. */
private fun effectiveLimitValue(config: LimitConfig, dayIndex: Int): Long = when (config.frequency) {
    LimitFrequency.BY_DAY -> limitValue(config.byDayMinutes.getOrNull(dayIndex) ?: config.dailyLimitMinutes)
    LimitFrequency.DAILY -> limitValue(config.dailyLimitMinutes)
}

/**
 * 예약 차단 하나가 실제로 막는 분량(요일 수 × 구간 길이). 꺼져 있으면 0이고, 자정을 넘는
 * 구간은 wrap을 펴서 잰다. 확장 `scheduleWeight`와 같은 계산이다 — 한쪽만 바꾸면 같은 편집이
 * 한 기기에서만 거부된다.
 */
private fun scheduleWeight(window: ScheduleWindow?): Long {
    if (window == null || !window.enabled) return 0L
    val span = if (window.endMinute > window.startMinute) {
        window.endMinute - window.startMinute
    } else {
        MINUTES_PER_DAY - window.startMinute + window.endMinute
    }
    val days = window.days.count { it }
    return span.toLong() * days
}

/** 확장 `scheduleById`. id가 겹치면 뒤엣것이 이긴다(양쪽 다 마지막 값이 남는다). */
private fun scheduleById(windows: List<ScheduleWindow>): Map<String, ScheduleWindow> =
    windows.associateBy { it.id }

/**
 * 하드코어 모드에서 금지되는 변경(= 차단이 약해지는 방향)을 찾아낸다. 비어 있으면 저장해도 된다.
 *
 * @param previous 지금 적용 중인 설정
 * @param next 저장하려는 설정
 */
fun findHardcoreViolations(previous: Settings, next: Settings): List<HardcoreViolation> {
    val violations = mutableListOf<HardcoreViolation>()

    // 한도: 커지면(=더 오래 볼 수 있으면) 약화. 무제한으로 바꾸는 것도 여기 걸린다.
    if (limitValue(next.limit.dailyLimitMinutes) > limitValue(previous.limit.dailyLimitMinutes)) {
        violations += HardcoreViolation.DAILY_LIMIT
    }

    // 요일별 한도: 요일 하나라도 커지면 약화. 목록이 7개보다 짧은 옛 값은 그 요일이 기본
    // 한도로 풀린 것으로 보고 기본 한도와 비교한다(확장이 빠진 키를 기본 한도로 채우는 것과 같다).
    val weakerDay = (0 until 7).any { day ->
        val before = limitValue(previous.limit.byDayMinutes.getOrNull(day) ?: previous.limit.dailyLimitMinutes)
        val after = limitValue(next.limit.byDayMinutes.getOrNull(day) ?: next.limit.dailyLimitMinutes)
        after > before
    }
    if (weakerDay) violations += HardcoreViolation.BY_DAY_LIMIT

    // 한도 모드(안드로이드 전용 항목 — 파일 맨 위 주석 참고). 값이 아니라 "실제로 적용되는
    // 한도"로 재므로, 모드를 바꾸면서 동시에 더 조이는 편집은 통과한다.
    if (next.limit.frequency != previous.limit.frequency) {
        val loosensSomeDay = (0 until 7).any { day ->
            effectiveLimitValue(next.limit, day) > effectiveLimitValue(previous.limit, day)
        }
        if (loosensSomeDay) violations += HardcoreViolation.LIMIT_MODE
    }

    // 긴급 시청: 허용 횟수를 늘리는 것과 리셋 주기를 짧게 만드는 것 둘 다 약화다
    // (monthly < weekly < daily 순으로 느슨하다 — 자주 리셋될수록 총 횟수가 많아진다).
    val emergencyWeaker = next.emergencyAllowance > previous.emergencyAllowance ||
        emergencyResetStrictness(next.emergencyResetFrequency) >
        emergencyResetStrictness(previous.emergencyResetFrequency)
    if (emergencyWeaker) violations += HardcoreViolation.EMERGENCY

    // 예약 차단: 삭제·비활성화·구간 축소가 전부 약화다. 추가와 확대는 허용한다.
    val previousWindows = scheduleById(previous.scheduleWindows)
    val nextWindows = scheduleById(next.scheduleWindows)
    val scheduleWeakened = previousWindows.any { (id, before) ->
        val after = nextWindows[id]
        after == null || scheduleWeight(after) < scheduleWeight(before)
    }
    if (scheduleWeakened) violations += HardcoreViolation.SCHEDULE

    return violations
}

/**
 * 확장 `RESET_STRICTNESS`와 같은 순서. 확장은 모르는 값이 오면 비교를 건너뛰지만 여기서는
 * 열거형이라 그런 값 자체가 존재할 수 없다 — 원격 문자열은 이미
 * [com.tubelimiter.app.sync.RemoteSettings] 디코드에서 걸러진다.
 */
private fun emergencyResetStrictness(frequency: EmergencyResetFrequency): Int = when (frequency) {
    EmergencyResetFrequency.MONTHLY -> 0
    EmergencyResetFrequency.WEEKLY -> 1
    EmergencyResetFrequency.DAILY -> 2
}

/** [isHardcoreChangeAllowed]의 결론. 확장 `isHardcoreChangeAllowed`가 돌려주는 모양과 같다. */
data class HardcoreCheck(
    val allowed: Boolean,
    val violations: List<HardcoreViolation>,
)

/**
 * 하드코어가 켜져 있을 때만 위 규칙을 적용한다.
 *
 * 하드코어 **자체를 끄는 것**은 여기서 막지 않는다 — 그건 1시간 쿨다운
 * ([shouldDisableHardcore])이 담당하는 별개의 관문이고, 여기서 또 막으면 해제 요청조차 저장할
 * 수 없게 된다. 확장 `isHardcoreChangeAllowed`와 같은 판단이다.
 */
fun isHardcoreChangeAllowed(previous: Settings, next: Settings): HardcoreCheck {
    if (!previous.hardcoreMode) return HardcoreCheck(allowed = true, violations = emptyList())
    val violations = findHardcoreViolations(previous, next)
    return HardcoreCheck(allowed = violations.isEmpty(), violations = violations)
}
