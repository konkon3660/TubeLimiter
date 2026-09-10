package com.tubelimiter.app.sync

import com.tubelimiter.app.data.Settings
import com.tubelimiter.app.limit.HardcoreViolation
import com.tubelimiter.app.limit.isHardcoreChangeAllowed
import io.github.jan.supabase.exceptions.RestException

/**
 * 서버에 못 닿을 때 설정 편집이 어떻게 되는가의 규칙. 확장
 * `extension/src/lib/offlineSettings.js` + `lib/pendingSettingsStore.js`의 안드로이드 짝이고,
 * **두 쪽이 갈라지면 안 되는 계약**이다(documents/BACKEND.md "오프라인 편집 대기분").
 *
 * ## 안드로이드는 출발점이 다르다 — 무엇이 이미 되고 있었나
 *
 * 확장은 옵션 화면이 `getCurrentUser()` 하나에 막혀서 서버가 죽으면 **아무것도 편집할 수
 * 없었다**(차단은 걸려 있는데 조정도 해제도 못 하는 상태). 안드로이드는 DataStore가 1차
 * 저장소라 그 문제가 애초에 없다: 저장은 항상 로컬에 먼저 쓰이고 차단 판정도 로컬 값을
 * 보므로, 로그아웃이든 오프라인이든 편집과 즉시 반영은 예전부터 됐다.
 *
 * ## 그래서 여기서 새로 채우는 것: 대기분
 *
 * 빠져 있던 건 "서버에 못 올린 변경이 **되돌아가는** 것"이다. 오프라인에서 저장하면 push가
 * 실패하고, 30초마다 도는 [SyncRepository.pullSettings]가 서버의 옛 행을 다시 내려받아
 * `replaceAll`로 덮는다 — 사용자 입장에서는 "저장했는데 되돌아왔다". 그래서:
 *
 * 1. **로그아웃과 서버 불통을 구분한다**([isOfflineFailure]). 서버가 4xx나 PostgREST 코드로
 *    **답을 하면** 세션 문제이므로 대기분에 넣지 않는다. 5xx·타임아웃·응답 없음만 오프라인이다.
 *    여기를 뭉뚱그리면 토큰이 취소된 계정의 편집이 영원히 대기분에 쌓인다.
 * 2. **대기분은 누적된다**([accumulatePendingSettings]). 여러 번 저장하면 컬럼이 합쳐진다 —
 *    매번 통째로 덮어쓰면 앞선 저장에서만 건드린 항목이 사라진다.
 * 3. **계정이 다르면 앞의 대기분은 버린다.** A의 오프라인 편집이 B의 계정으로 올라가면 안 된다.
 * 4. **서버가 살아나면 대기분 push가 pull보다 우선한다**([resolveSettingsSyncPlan]). 기본
 *    계약("settings는 서버가 진실의 원천")을 이 한 줄만 뒤집는다.
 * 5. **push는 대기분에 든 컬럼만 올린다**([Settings.toRemoteJson]의 `columns` 인자). 로컬
 *    설정을 통째로 올리면 오프라인이던 사이 다른 기기가 바꾼 항목까지 옛 값으로 되돌린다.
 *    그래도 **마지막 쓰기가 이긴다** — 행에 버전도 벡터 시계도 없어 같은 컬럼을 다른 기기가
 *    바꿨는지 알 방법이 없다. 해결하려면 컬럼 단위 타임스탬프가 필요하고, 지금은 하지 않는다.
 * 6. **하드코어 관문은 온라인/오프라인 분기 전에 탄다**([SyncRepository.saveSettings]).
 *    안 그러면 "비행기 모드로 바꾸고 한도를 올린다"는 새 우회로를 우리 손으로 만드는 셈이다.
 *
 * ## 확장과 일부러 다른 것: 값이 아니라 **컬럼 이름**만 담는다
 *
 * 확장의 대기분은 `{필드: 값}` 패치다. 확장에는 `settingsCache`와 대기분이 **따로** 있어서
 * 값을 같이 들고 있어야 했기 때문이다. 안드로이드는 DataStore가 이미 최신 값을 들고 있으므로
 * 대기분은 "어느 컬럼을 아직 못 올렸는가"만 기억하고, 올릴 때 값을 그 자리에서 읽는다.
 * 저장할 값이 두 군데 있으면 서로 어긋날 수 있는데 그 여지가 아예 없어지고, 누적(2번)은
 * 집합 합집합이 되며, 5번(든 컬럼만 올리기)도 그대로 성립한다.
 */

/** 레코드 사이 구분자. [com.tubelimiter.app.data.encodeScheduleWindows]와 같은 이유로 제어문자를 쓴다. */
private const val FIELD_SEPARATOR = "\u001F"

/** 컬럼 이름 사이 구분자. 컬럼 이름은 우리가 정한 snake_case 상수뿐이라 쉼표로 충분하다. */
private const val COLUMN_SEPARATOR = ","

/**
 * 서버가 "이 요청은 잘못됐다 / 이 세션은 무효다"라고 **답한** 상태 코드. 답이 왔다는 건 서버가
 * 살아 있다는 뜻이라 오프라인이 아니다. 확장 `SERVER_REJECT_STATUSES`와 같은 목록.
 */
private val SERVER_REJECT_STATUSES = setOf(400, 401, 403, 404, 409, 422)

/** 서버가 살아는 있지만 지금은 못 받는 상태. 잠시 뒤 다시 시도할 값이라 오프라인으로 친다. */
private val RETRYABLE_STATUSES = setOf(408, 425, 429)

/**
 * 아직 서버에 못 올린 설정 편집분.
 *
 * @property userId 이 편집을 한 계정. 다른 계정으로 로그인하면 통째로 버린다.
 * @property columns 못 올린 `settings` 컬럼 이름들([SETTINGS_COLUMNS]의 부분집합).
 */
data class PendingSettings(
    val userId: String,
    val columns: Set<String>,
)

/**
 * 이 실패가 "서버에 못 닿았다"인가?
 *
 * @param statusField 응답 상태 코드(supabase-kt `RestException.statusCode`), 응답이 없었으면 null
 * @param codeField 서버가 준 오류 식별자(`RestException.error`), 없으면 null
 */
fun isOfflineFailureFields(statusField: Int?, codeField: String?): Boolean {
    if (statusField != null && statusField > 0) {
        if (statusField in SERVER_REJECT_STATUSES) return false
        return statusField >= 500 || statusField in RETRYABLE_STATUSES
    }

    // PostgREST 오류 코드가 붙어 있다 = PostgREST가 답을 만들어 보냈다 = 서버는 살아 있다.
    // (RLS 거부, 스키마 캐시 문제 등. 여기서 오프라인이라고 판단하면 진짜 버그가 대기분으로 숨는다.)
    if (codeField != null && codeField.startsWith("PGRST")) return false

    // 상태 코드도 서버 코드도 없다 = HTTP 응답 자체를 못 받았다(DNS 실패·타임아웃·pause된 프로젝트).
    return true
}

/**
 * [isOfflineFailureFields]의 얇은 껍데기. supabase-kt의 [RestException]은 `HttpResponse` 없이
 * 만들 수 없어 유닛 테스트에서 흉내낼 수 없으므로, 필드를 뽑는 일과 판정하는 일을 갈라둔다 —
 * [com.tubelimiter.app.diagnostics.summarizeFailure]와 같은 이유·같은 자리를 읽는다.
 */
fun isOfflineFailure(error: Throwable?): Boolean {
    val rest = error as? RestException
    return isOfflineFailureFields(statusField = rest?.statusCode, codeField = rest?.error)
}

/**
 * 이번에 못 올린 컬럼을 대기분에 **누적**한다. 결과가 빈 대기분이면 null(= 올릴 게 없다).
 *
 * 계정이 다르면 앞의 대기분은 버린다 — A의 오프라인 편집이 B의 계정으로 올라가면 안 된다.
 */
fun accumulatePendingSettings(
    previous: PendingSettings?,
    userId: String,
    columns: Set<String>,
): PendingSettings? {
    val carried = if (previous != null && previous.userId == userId) previous.columns else emptySet()
    val merged = normalizeColumns(carried + columns)
    return if (merged.isEmpty()) null else PendingSettings(userId, merged)
}

/**
 * 저장돼 있던 대기분 중 **지금 로그인한 계정의 것**만 돌려준다. 계정이 다르거나 비어 있으면
 * 없는 셈 친다(남의 설정을 내 계정에 올리는 사고 방지 — 확장 `readPendingFor`와 같은 보험).
 */
fun readPendingFor(stored: PendingSettings?, userId: String?): PendingSettings? {
    if (stored == null || userId == null) return null
    if (stored.userId != userId || stored.columns.isEmpty()) return null
    return stored
}

/** 서버와 다시 통했을 때 어느 쪽이 이기는가. */
enum class SettingsSyncAction {
    /** 대기분을 서버로 올린다. 이 한 줄만 "서버가 진실의 원천"을 뒤집는다. */
    PUSH_PENDING,

    /** 대기분이 없다. 기존대로 서버 행을 내려받는다. */
    PULL,
}

/** [resolveSettingsSyncPlan]의 결론. [pending]은 [SettingsSyncAction.PUSH_PENDING]일 때만 채워진다. */
data class SettingsSyncPlan(
    val action: SettingsSyncAction,
    val pending: PendingSettings?,
)

/**
 * 서버가 살아난 뒤 무엇을 먼저 할지.
 *
 * 대기분은 사용자가 **방금 명시적으로 한 변경**이라, 서버 값으로 덮어버리면 "저장했는데
 * 되돌아왔다"가 된다. 그래서 대기분이 있으면 push가 pull을 이긴다. 확장
 * `resolveSettingsSyncPlan`과 같은 판단이고, 계정 필터([readPendingFor])도 여기서 함께 건다.
 */
fun resolveSettingsSyncPlan(stored: PendingSettings?, userId: String?): SettingsSyncPlan {
    val pending = readPendingFor(stored, userId)
    return if (pending == null) {
        SettingsSyncPlan(SettingsSyncAction.PULL, null)
    } else {
        SettingsSyncPlan(SettingsSyncAction.PUSH_PENDING, pending)
    }
}

/** [planPendingSettingsPush]의 결론. */
data class PendingPushPlan(
    /** 실제로 올릴 컬럼. 비어 있으면 올릴 게 없다(대기분은 비운다). */
    val columns: Set<String>,
    /** 서버 기준 재검증에서 막힌 사유. 비어 있지 않으면 화면에 남겨야 한다. */
    val rejected: List<HardcoreViolation>,
    /** push 후 로컬에 남아야 할 설정. 거부된 컬럼은 서버 값으로 되돌아온다. */
    val settings: Settings,
)

/**
 * 대기분을 **올리기 직전에** 다시 판정한다. 확장 `planPendingSettingsPush`
 * (extension/src/lib/offlineSettings.js)와 같은 규칙이고, **두 쪽이 갈라지면 안 되는 계약**이다
 * — 한쪽만 고치면 이번에는 그 기기가 게이트 밖이 된다(QA_REVIEW §10.4).
 *
 * ## 왜 한 번 더 보는가
 *
 * 대기분은 오프라인 편집 시점의 **로컬 값**을 기준으로 [isHardcoreChangeAllowed]를 통과했다.
 * 그 사이 다른 기기가 규칙을 더 조였다면(폰 오프라인에서 한도 60분 저장 → 그동안 PC가 10분으로
 * 조임) 그대로 올리는 것은 하드코어 잠금 바깥에서 잠금을 되돌리는 일이 된다. 그래서 push 직전에
 * **서버의 현재 값**을 기준으로 다시 태운다 — 왕복이 한 번 는다.
 *
 * ## 거부되면 그 컬럼은 버린다
 *
 * 남겨두고 다시 시도해봐야 소용이 없다. 하드코어가 켜진 동안 서버 값은 조여지기만 하므로 다음
 * 시도도 같은 이유로 막히고, 대기분이 영영 안 비면 주기마다 같은 요청이 되풀이된다. 대신
 * **조용히 버리지는 않는다** — [rejected]를 [com.tubelimiter.app.data.AppState]에 남겨 설정
 * 화면이 "무엇이 왜 반영되지 않았는지"를 띄운다. 알림 대신 화면에 남기는 쪽을 고른 이유는, 이
 * 사건이 급하지 않고(더 강한 규칙이 이미 적용돼 있다) 알림은 한 번 지나가면 다시 볼 수 없기
 * 때문이다.
 *
 * 거부되지 않은 컬럼은 그대로 올린다. 한 번의 오프라인 편집에 여러 항목이 섞여 있을 때 하나
 * 때문에 전부 버리면, 특히 하드코어 해제 요청(게이트가 막지 않는 값)까지 같이 날아간다.
 *
 * @param server 서버의 현재 값(원격 행을 로컬로 디코드한 것). 하드코어 판정의 기준이다.
 * @param local 지금 이 기기의 값. 대기분 컬럼의 "올리려는 값"이 여기 들어 있다.
 * @param columns 아직 못 올린 컬럼
 */
fun planPendingSettingsPush(server: Settings, local: Settings, columns: Set<String>): PendingPushPlan {
    var survivors = columns
    val rejected = mutableListOf<HardcoreViolation>()
    val dropped = mutableSetOf<String>()

    // 컬럼 하나를 빼면 다른 규칙의 비교 기준이 바뀔 수 있어(요일별 한도는 기본 한도를 참조한다)
    // 통과할 때까지 되풀이한다. 한 바퀴마다 최소 한 컬럼이 빠지므로 반드시 끝난다.
    while (true) {
        val check = isHardcoreChangeAllowed(server, server.withColumnsFrom(local, survivors))
        if (check.allowed) break
        rejected += check.violations

        val removable = check.violations.map { it.column }.filter { it in survivors }.toSet()
        // 규칙이 지목한 컬럼이 대기분에 하나도 없다 = 무엇을 빼야 할지 모른다. 통째로 버린다
        // (판단이 애매하면 약화로 본다는 HardcoreLock.kt의 기준과 같은 방향).
        val drop = removable.ifEmpty { survivors }
        dropped += drop
        survivors = survivors - drop
    }

    // 거부된 컬럼은 서버 값이 이긴다. 로컬에 남겨두면 화면에는 반영된 것처럼 보인다.
    return PendingPushPlan(survivors, rejected.distinct(), local.withColumnsFrom(server, dropped))
}

/**
 * 이 빌드가 모르는 컬럼 이름은 버린다. 저장된 값이 손상됐거나 더 새 버전이 남긴 이름일 수
 * 있는데, 모르는 이름을 그대로 payload에 넣으면 upsert가 통째로 실패한다. 정렬해 두는 건
 * 인코딩 결과를 안정시키려는 것(같은 대기분이 매번 같은 문자열이 된다).
 */
private fun normalizeColumns(columns: Set<String>): Set<String> =
    columns.filter { it in SETTINGS_COLUMNS }.sorted().toSet()

/** 저장할 문자열. 올릴 게 없으면 null이고, 호출부는 키를 지운다. */
fun encodePendingSettings(pending: PendingSettings?): String? {
    if (pending == null || pending.userId.isBlank()) return null
    val columns = normalizeColumns(pending.columns)
    if (columns.isEmpty()) return null
    return pending.userId + FIELD_SEPARATOR + columns.joinToString(COLUMN_SEPARATOR)
}

/** 손상된 값은 "대기분 없음"으로 본다 — 저장된 문자열 하나 때문에 동기화가 멈추면 안 된다. */
fun decodePendingSettings(raw: String?): PendingSettings? {
    if (raw.isNullOrBlank()) return null
    val userId = raw.substringBefore(FIELD_SEPARATOR, missingDelimiterValue = "")
    if (userId.isBlank()) return null
    val columns = normalizeColumns(
        raw.substringAfter(FIELD_SEPARATOR, missingDelimiterValue = "")
            .split(COLUMN_SEPARATOR)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet(),
    )
    return if (columns.isEmpty()) null else PendingSettings(userId, columns)
}
