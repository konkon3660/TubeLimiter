package com.tubelimiter.app.data

/**
 * 로그아웃도 계정 삭제도 아닌 **세 번째 경우** — 기기의 주인이 바뀌는 것(A가 로그아웃하고 B가
 * 로그인하는 공용 폰) — 을 다루는 규칙. 확장 `extension/src/lib/accountReset.js`의
 * `planAccountSwitch`를 그대로 옮긴 것이고, **두 쪽이 갈라지면 안 되는 계약**이다
 * (documents/BACKEND.md "계정 전환 — 무엇을 지우고 무엇을 남기나").
 *
 * ## 왜 로그아웃 때 지우면 안 되나
 *
 * 같은 계정으로 다시 들어오는 게 로그아웃의 정상 경로이고, 로컬 전용 기록(시간대별 패턴,
 * 한도 스냅샷, 긴급 시청 기록)은 서버에서 되받아올 수 없어 한 번 지우면 영영 사라진다.
 * 그래서 "언제 지우는가"의 기준을 로그아웃이 아니라 **주인이 바뀌었는가**로 잡는다.
 *
 * ## 가드가 없으면 무엇이 새는가
 *
 * 1. B의 대시보드에 A의 기록이 그대로 뜬다(프라이버시).
 * 2. A가 이미 서버에 보고한 몫(`daily_usage` 동기화 마커)이 B의 첫 델타 기준선이 되어
 *    **B의 그날 사용량이 조용히 서버에 안 올라간다**(음수 델타는 0으로 잘린다).
 * 3. A의 설정 캐시가 남아 B가 **A의 하드코어 잠금을 물려받는다**.
 *
 * ## 반대로 지우면 안 되는 것
 *
 * 지금 걸려 있는 차단·집중 세션과 **이 기기의 남은 긴급 시청 횟수**는 계정이 아니라 이
 * 기기에서 온 값이다. 지우면 "다른 계정으로 로그인"이 지금 나를 막고 있는 차단에서 빠져나가는
 * 길이 된다. 목록은 [ACCOUNT_SWITCH_PRESERVED_KEYS].
 */

/** 이 기기의 로컬 데이터 주인을 적어두는 키 이름. 확장 `ACCOUNT_OWNER_KEY`와 같은 역할. */
const val ACCOUNT_OWNER_KEY = "account_user_id"

/**
 * 계정이 바뀌었을 때 지우는 키 이름 전부. 주인 표식은 빠져 있다 — 실제 실행은
 * [AppState.clearAccountData]가 그것까지 지운 뒤 곧바로 새 주인을 쓰므로 결과가 같고,
 * 확장 `ACCOUNT_SWITCH_REMOVED_KEYS`가 표식만 빼는 것과 같은 뜻이다.
 *
 * 목록의 원본은 [ACCOUNT_DATA_KEYS](AppState)와 [SYNCED_SETTINGS_KEYS](AppSettings)이고,
 * 여기서는 이름만 모아 테스트가 계약을 고정할 수 있게 한다.
 */
val ACCOUNT_SWITCH_REMOVED_KEYS: List<String> =
    (ACCOUNT_DATA_KEYS.map { it.name } + SYNCED_SETTINGS_KEYS.map { it.name })
        .filterNot { it == ACCOUNT_OWNER_KEY }

/**
 * 계정이 바뀌어도 남기는 키 이름 전부. 전부 계정이 아니라 이 기기에서 온 값이다 —
 * 자세한 이유는 [ACCOUNT_PRESERVED_STATE_KEYS] / [DEVICE_LOCAL_SETTINGS_KEYS] 주석 참고.
 */
val ACCOUNT_SWITCH_PRESERVED_KEYS: List<String> =
    ACCOUNT_PRESERVED_STATE_KEYS.map { it.name } + DEVICE_LOCAL_SETTINGS_KEYS.map { it.name }

/**
 * [planAccountSwitch]의 결론.
 *
 * @property switched 계정에서 온 로컬 값을 비워야 하는가.
 * @property ownerToStore 새로 적어둘 주인. null이면 표식을 다시 쓸 필요가 없다는 뜻이다.
 */
data class AccountSwitchPlan(
    val switched: Boolean,
    val ownerToStore: String?,
)

/**
 * 로그인한 user_id와 이 기기에 적힌 주인을 비교해 무엇을 할지 정한다.
 *
 * @param storedUserId 이 기기의 로컬 데이터 주인([ACCOUNT_OWNER_KEY]), 없으면 null
 * @param currentUserId 지금 로그인한 user_id, 로그아웃 상태면 null
 */
fun planAccountSwitch(storedUserId: String?, currentUserId: String?): AccountSwitchPlan {
    val stored = storedUserId?.takeIf { it.isNotBlank() }
    val current = currentUserId?.takeIf { it.isNotBlank() }

    // 로그아웃 상태에서는 아무 판단도 하지 않는다. "지금 주인이 없다"는 건 "주인이 바뀌었다"가
    // 아니고, 여기서 지우면 로그아웃이 곧 기록 삭제가 된다.
    if (current == null) return AccountSwitchPlan(switched = false, ownerToStore = null)

    // 표식이 없는 기기 = 이 가드가 생기기 전부터 쓰던 기기이거나, 이 기기의 첫 로그인.
    // 여기서 지우면 멀쩡히 쓰던 사람이 앱 업데이트 한 번에 자기 기록을 잃는다. 표식만 남기고,
    // 지우는 판단은 "실제로 주인이 바뀌는" 다음번부터 한다.
    if (stored == null) return AccountSwitchPlan(switched = false, ownerToStore = current)

    // 같은 계정으로 다시 로그인. 아무것도 지우면 안 된다 — 로컬 전용 기록은 서버에서 되받아올
    // 수 없어서 한 번 지우면 영영 사라진다.
    if (stored == current) return AccountSwitchPlan(switched = false, ownerToStore = null)

    return AccountSwitchPlan(switched = true, ownerToStore = current)
}
