package com.tubelimiter.app.diagnostics

import com.tubelimiter.app.permission.AppPermission

/**
 * "감시가 실제로 돌고 있나"를 판정하는 순수 규칙 (documents/QA_REVIEW.md §1.7).
 *
 * 사용현황 접근·오버레이·배터리 최적화 예외 중 **하나만 꺼져도** 차단은 무력화되는데, 예전에는
 * 그 상태에서 아무 신호도 나가지 않았다. 삼성·샤오미의 절전 정책이 포그라운드 서비스를 죽이는
 * 흔한 경우도 마찬가지로 조용했다 — 알림은 사라지고, 홈 화면은 여전히 "감시 중"이었다.
 *
 * 판정을 [com.tubelimiter.app.service.UsageMonitorService]에서 떼어 여기 두는 이유는
 * [staleSyncWarning]과 같다: 임계값이 걸린 분기는 유닛 테스트가 직접 검증해야 하고, 서비스는
 * 안드로이드 프레임워크 없이는 세울 수 없다.
 */

/**
 * 마지막 심박 이후 이만큼 지나면 "감시가 멈췄다"로 본다.
 *
 * 틱 자체는 5~30초 간격이라 이 값은 터무니없이 넉넉해 보이지만, 화면이 꺼진 채 기기가 깊이
 * 잠들면 `delay`가 예정보다 한참 늦게 깨는 경우가 있다. 임계값을 분 단위로 잡으면 밤새 폰을
 * 안 쓴 사람에게 아침마다 거짓 경고가 뜨고, 그러면 사용자는 이 경고를 읽지 않게 된다.
 * 두 시간은 "정상적인 지연"으로 설명할 수 없는 길이다.
 */
const val MONITOR_STALE_THRESHOLD_MILLIS = 2L * 60 * 60 * 1000

/** 홈 화면 경고의 내용. 문구가 아니라 판정 결과만 담는 건 [SyncWarning]과 같은 이유다. */
sealed interface MonitorWarning {
    /** 감시에 필요한 권한이 빠져 있다. 지금 이 순간 차단이 동작하지 않는다는 뜻이라 가장 급하다. */
    data class PermissionsRevoked(val permissions: List<AppPermission>) : MonitorWarning

    /** 권한은 멀쩡한데 서비스가 [hours]시간째 심박을 남기지 않았다(= 절전 정책이 죽였다). */
    data class StaleFor(val hours: Long) : MonitorWarning
}

/**
 * 홈 화면에 띄울 감시 경고, 띄울 게 없으면 null.
 *
 * 감시를 **사용자가 직접 꺼둔** 경우는 조용히 넘어간다 — 홈 화면 토글이 이미 "꺼짐 — 차단되지
 * 않습니다"라고 말하고 있고, 자기가 끈 걸 경고로 또 알리면 경고 자체가 값싸진다.
 *
 * 심박이 아예 없으면(null) 경고하지 않는다. 설치 직후 서비스가 첫 틱을 돌기 전 몇 초와, 정말로
 * 한 번도 안 뜬 상태를 이 값만으로는 구별할 수 없기 때문이다. 전자를 경고했다가는 앱을 처음
 * 켠 사람이 곧바로 빨간 줄을 보게 된다.
 */
fun monitorWarning(
    monitoringEnabled: Boolean,
    missingPermissions: List<AppPermission>,
    lastHeartbeatAtMillis: Long?,
    nowMillis: Long,
    thresholdMillis: Long = MONITOR_STALE_THRESHOLD_MILLIS,
): MonitorWarning? {
    if (!monitoringEnabled) return null
    if (missingPermissions.isNotEmpty()) return MonitorWarning.PermissionsRevoked(missingPermissions)
    if (lastHeartbeatAtMillis == null) return null
    val elapsed = nowMillis - lastHeartbeatAtMillis
    if (elapsed < thresholdMillis) return null
    return MonitorWarning.StaleFor(hours = elapsed / (60L * 60 * 1000))
}

/**
 * 서비스가 다시 살아났을 때 "얼마나 죽어 있었나". 임계값을 못 넘으면 null —
 * 재부팅이나 설정 변경으로 서비스가 잠깐 다시 뜨는 건 사고가 아니다.
 *
 * 홈 화면 경고와 달리 이건 **진단 기록에 남는 값**이다. 사용자가 앱을 열면
 * [com.tubelimiter.app.MainActivity]가 서비스를 곧바로 되살리므로 경고 줄은 몇 초 만에
 * 사라진다 — 그때 아무 흔적도 안 남으면 "어제 세 시간 동안 안 막혔다"를 나중에 확인할 방법이 없다.
 */
fun monitorGapMillis(
    previousHeartbeatAtMillis: Long?,
    nowMillis: Long,
    thresholdMillis: Long = MONITOR_STALE_THRESHOLD_MILLIS,
): Long? {
    if (previousHeartbeatAtMillis == null) return null
    val gap = nowMillis - previousHeartbeatAtMillis
    return if (gap >= thresholdMillis) gap else null
}

/**
 * 진단 코드는 **확장과 같은 종류(`monitor`) 안의 문자열**이다. BACKEND.md의 종류 표에 새 항목을
 * 만들지 않는 건, 확장에 대응하는 경로가 없는 안드로이드 전용 실패이기 때문 — 종류를 늘리면
 * "두 기기 기록을 나란히 놓고 읽는다"는 계약에서 한쪽에만 있는 열이 생긴다.
 */
fun monitorGapCode(gapMillis: Long): String = "gap/${(gapMillis / (60L * 60 * 1000)).coerceAtLeast(0)}h"

/** 권한 상실 코드. 짧은 토큰을 쓰는 건 네 개가 한꺼번에 빠져도 코드 길이 제한에 안 걸리게 하려는 것. */
fun permissionLossCode(permissions: List<AppPermission>): String {
    if (permissions.isEmpty()) return "permission_lost/none"
    val names = permissions.map { permission ->
        when (permission) {
            AppPermission.USAGE_ACCESS -> "usage"
            AppPermission.OVERLAY -> "overlay"
            AppPermission.BATTERY_UNRESTRICTED -> "battery"
            AppPermission.NOTIFICATIONS -> "notify"
        }
    }
    return "permission_lost/${names.joinToString(",")}"
}
