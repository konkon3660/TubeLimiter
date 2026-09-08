package com.tubelimiter.app.diagnostics

import com.tubelimiter.app.R
import io.github.jan.supabase.exceptions.RestException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 동기화·인증 실패를 **기기 안에만** 쌓아두는 링버퍼.
 *
 * 외부 크래시 리포팅 서비스를 붙이지 않기로 했으므로(계정도 비용도 필요 없다), 실패가
 * `Log`로만 남고 조용히 사라지는 걸 막는 유일한 수단이 이것이다. 최근 [DIAGNOSTIC_CAPACITY]건을
 * DataStore에 남겨 설정 화면에서 그대로 보여준다.
 *
 * 저장 포맷은 [com.tubelimiter.app.data.encodeScheduleWindows]와 **같은 이유로** 손으로 만든
 * 문자열이다: `org.json`은 유닛 테스트에서 스텁이라 못 쓰고, 필드 네 개 때문에 serialization
 * 플러그인을 새로 넣을 이유도 없다(documents/MOBILE_PLAN.md의 구현 메모 참고).
 *
 * ## 민감정보 금지 — 이 파일의 존재 이유의 절반
 *
 * access token, 이메일, `user_id`, service key 같은 값은 **절대** 이벤트에 들어가면 안 된다.
 * 이 버퍼는 사용자가 화면에서 읽고 클립보드로 복사해 남에게 붙여넣는 것을 전제로 하므로,
 * 한 번 새면 그대로 유출이다. 그래서:
 *
 * 1. 서버 에러 메시지를 **그대로 넣지 말 것.** [summarizeFailure]가 허용 목록 방식으로
 *    예외 클래스 이름 + HTTP 상태 코드 + PostgREST 오류 코드만 뽑아낸다. PostgREST/GoTrue의
 *    오류 문구에는 조건에 걸린 값(이메일, uuid 등)이 그대로 실려 오는 경우가 있다.
 * 2. 그래도 새는 경우를 대비해 [sanitizeDiagnosticCode]가 이메일·UUID·JWT 모양을 한 번 더
 *    지운다. 마지막 방어선이지 1번의 대체재가 아니다.
 */

/** 링버퍼 크기. 넘치면 오래된 것부터 버린다. */
const val DIAGNOSTIC_CAPACITY = 50

/** 마지막 동기화 성공이 이만큼 지나면 홈 화면에 조용한 한 줄 경고를 띄운다. */
const val SYNC_STALE_THRESHOLD_MILLIS = 24L * 60 * 60 * 1000

/** 코드 한 줄의 최대 길이. 길수록 원문(=민감정보)이 섞여 들어올 여지가 커진다. */
private const val MAX_CODE_LENGTH = 48

/**
 * 레코드 사이 / 필드 사이 구분자. [com.tubelimiter.app.data.encodeScheduleWindows]와 같은
 * 이유로 눈에 보이는 문자 대신 ASCII 제어문자를 쓴다 — 코드 문자열은 예외 메시지에서 나온
 * 값이라 콜론·쉼표·세미콜론이 얼마든지 들어올 수 있다.
 */
private const val EVENT_SEPARATOR = "\u001E"
private const val FIELD_SEPARATOR = "\u001F"

/** 저장된 코드에서 지워야 할 것들. 순서대로 훑고 전부 `[redacted]`로 바꾼다. */
private val REDACTION_PATTERNS = listOf(
    // 이메일
    Regex("""[\w.+-]+@[\w.-]+\.\w+"""),
    // user_id 같은 UUID
    Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"""),
    // JWT (access token / anon key / service key 전부 이 모양이다)
    Regex("""eyJ[A-Za-z0-9_.-]{8,}"""),
)

/**
 * 예외 메시지에서 뽑아낼 **유일한** 숫자. 앞뒤가 영숫자면 잡지 않아 uuid 조각에서 세 자리를
 * 잘라오는 일이 없다.
 */
private val HTTP_STATUS_PATTERN = Regex("""(?<![0-9A-Za-z])[45][0-9]{2}(?![0-9A-Za-z])""")

/** PostgREST가 돌려주는 오류 코드(`PGRST116` 등). 값이 아니라 분류라 안전하다. */
private val POSTGREST_CODE_PATTERN = Regex("""PGRST[0-9]{3}""")

private val TIME_FORMATTER = DateTimeFormatter.ofPattern("MM-dd HH:mm")

/** 어느 경로가 실패했는지. 화면에 뜨는 이름은 [diagnosticKindLabelRes]에 있다. */
object DiagnosticKind {
    const val SYNC_SETTINGS = "sync_settings"
    const val SYNC_STREAK = "sync_streak"
    const val SYNC_USAGE = "sync_usage"
    const val EMERGENCY_FETCH = "emergency_fetch"
    const val AUTH = "auth"
    const val MONITOR = "monitor"
}

/**
 * String resource for a kind, or null for one this build does not know — the caller then shows
 * the raw stored key, which is what an event written by a newer version would carry.
 */
fun diagnosticKindLabelRes(kind: String): Int? = when (kind) {
    DiagnosticKind.SYNC_SETTINGS -> R.string.diagnostic_kind_sync_settings
    DiagnosticKind.SYNC_STREAK -> R.string.diagnostic_kind_sync_streak
    DiagnosticKind.SYNC_USAGE -> R.string.diagnostic_kind_sync_usage
    DiagnosticKind.EMERGENCY_FETCH -> R.string.diagnostic_kind_emergency_fetch
    DiagnosticKind.AUTH -> R.string.diagnostic_kind_auth
    DiagnosticKind.MONITOR -> R.string.diagnostic_kind_monitor
    else -> null
}

/**
 * 실패 한 건. [code]는 사람이 읽을 짧은 분류지 서버 메시지 원문이 아니다 — 위 주석 참고.
 *
 * [count]는 같은 (종류, 코드)가 반복된 횟수다. 30초마다 도는 동기화가 실패하면 같은 실패가
 * 하루에 2880번 쌓이는데, 그대로 넣으면 버퍼가 노이즈로 차서 정작 다른 실패가 밀려난다.
 */
data class DiagnosticEvent(
    val atMillis: Long,
    val kind: String,
    val code: String,
    val count: Int = 1,
)

/**
 * 코드/종류 문자열에서 구분자와 제어문자를 없애고, 민감해 보이는 값을 지우고, 길이를 자른다.
 * 이벤트를 넣는 유일한 통로인 [appendDiagnosticEvent]가 항상 이걸 거치므로 저장된 문자열은
 * 구분자를 품을 수 없다(= 인코딩이 깨지지 않는다).
 */
fun sanitizeDiagnosticCode(raw: String?): String {
    if (raw.isNullOrBlank()) return "unknown"
    var cleaned: String = raw
    REDACTION_PATTERNS.forEach { pattern -> cleaned = pattern.replace(cleaned, "[redacted]") }
    val collapsed = cleaned
        .map { if (it.isISOControl()) ' ' else it }
        .joinToString("")
        .trim()
        .replace(Regex("""\s+"""), " ")
    return if (collapsed.isEmpty()) "unknown" else collapsed.take(MAX_CODE_LENGTH)
}

/**
 * 예외를 **허용 목록 방식**으로 짧은 코드로 줄인다. 남기는 건 예외 클래스 이름과, 필드/메시지에서
 * 뽑아낸 HTTP 상태 / PostgREST 코드뿐 — 메시지 본문은 한 글자도 옮기지 않는다. 원문에 무엇이
 * 들어 있을지 우리가 통제할 수 없기 때문이다(파일 맨 위 "민감정보 금지" 참고).
 *
 * 상태 코드를 메시지 정규식에만 맡기지 않는 건 **확장과 코드를 맞추기 위해서다.** 확장
 * `syncDiagnostics.js`는 `statusFromField(error.status)`와 `error.code`를 먼저 본다. 안드로이드도
 * supabase-kt의 [RestException]이 `statusCode`(응답 상태)와 `error`(서버가 준 오류 식별자)를
 * 필드로 들고 있으므로 같은 자리를 읽는다 — 같은 실패가 두 기기에서 다른 코드로 남으면
 * "나란히 놓고 읽는다"는 documents/BACKEND.md의 계약이 반만 성립한다.
 *
 * supabase-kt에서 **못 꺼내는 것**: PostgREST의 `details`/`hint`와 Postgres SQLSTATE는
 * [RestException]에 따로 담기지 않고 `error`/`description` 문자열 안에만 들어온다. 그래서
 * `error` 필드도 그대로 쓰지 않고 [POSTGREST_CODE_PATTERN] 허용 목록을 통과한 `PGRSTxxx`만
 * 남긴다(확장이 `error.code`에 같은 정규식을 거는 것과 같은 이유 — 그 자리에 값이 실려 올
 * 가능성을 우리가 통제할 수 없다). 네트워크 실패(`HttpRequestException`)에는 상태 자체가
 * 없으므로 예외 이름만 남는다.
 */
fun summarizeFailure(error: Throwable?): String {
    if (error == null) return "unknown"
    val rest = error as? RestException
    return summarizeFailureFields(
        name = error::class.simpleName ?: "Throwable",
        message = error.message,
        statusField = rest?.statusCode,
        codeField = rest?.error,
    )
}

/**
 * [summarizeFailure]의 순수 알맹이. supabase-kt의 [RestException]은 `HttpResponse` 없이는 만들
 * 수 없어 유닛 테스트에서 흉내낼 수 없으므로, 필드를 뽑는 일과 조립하는 일을 갈라둔다 —
 * 테스트는 이 쪽으로 status/code 필드 경로를 그대로 검증한다.
 */
fun summarizeFailureFields(
    name: String?,
    message: String?,
    statusField: Int?,
    codeField: String?,
): String {
    val text = message.orEmpty()
    // 필드가 우선이고 메시지 정규식은 대체재다(확장 `statusFromField(...) || extractStatus(...)`).
    val status = statusFromField(statusField) ?: HTTP_STATUS_PATTERN.find(text)?.value?.let { "http_$it" }
    val postgrest = POSTGREST_CODE_PATTERN.find(codeField.orEmpty())?.value
        ?: POSTGREST_CODE_PATTERN.find(text)?.value
    val label = name?.takeIf { it.isNotBlank() } ?: "Throwable"
    return sanitizeDiagnosticCode(listOfNotNull(label, status, postgrest).joinToString("/"))
}

/** 4xx/5xx만 통과시킨다 — 그 밖의 값은 상태 코드가 아니거나 진단에 쓸모가 없다. */
private fun statusFromField(value: Int?): String? =
    if (value != null && value in 400..599) "http_$value" else null

/**
 * 새 실패를 버퍼 맨 앞(=최신)에 넣는다. 목록은 항상 최신순이고 [capacity]를 넘으면 뒤쪽
 * (=오래된 것)부터 버린다.
 *
 * 같은 (종류, 코드)가 이미 있으면 새로 쌓지 않고 그 항목의 시각을 갱신하며 [DiagnosticEvent.count]만
 * 올린다. "설정 동기화 http_500 ×143, 마지막 3분 전" 한 줄이 같은 줄 143개보다 진단에 쓸모 있고,
 * 무엇보다 버퍼가 한 종류의 실패로 가득 차 다른 실패를 밀어내는 일이 없다.
 */
fun appendDiagnosticEvent(
    events: List<DiagnosticEvent>,
    event: DiagnosticEvent,
    capacity: Int = DIAGNOSTIC_CAPACITY,
): List<DiagnosticEvent> {
    if (capacity <= 0) return emptyList()
    val kind = sanitizeDiagnosticCode(event.kind)
    val code = sanitizeDiagnosticCode(event.code)
    val previous = events.firstOrNull { it.kind == kind && it.code == code }
    val merged = DiagnosticEvent(
        atMillis = event.atMillis,
        kind = kind,
        code = code,
        count = (previous?.count ?: 0) + event.count.coerceAtLeast(1),
    )
    val rest = events.filterNot { it.kind == kind && it.code == code }
    return (listOf(merged) + rest).take(capacity)
}

fun encodeDiagnosticEvents(events: List<DiagnosticEvent>): String =
    events.joinToString(EVENT_SEPARATOR) { event ->
        listOf(
            event.atMillis.toString(),
            sanitizeDiagnosticCode(event.kind),
            sanitizeDiagnosticCode(event.code),
            event.count.toString(),
        ).joinToString(FIELD_SEPARATOR)
    }

/**
 * 저장소에서 읽어온 이벤트 목록을 믿을 수 있는 상태로 만든다: 종류·코드를
 * [sanitizeDiagnosticCode]에 다시 통과시키고 [capacity]로 자른다.
 *
 * 왜 읽는 쪽에서도 거르나: 쓰는 쪽([appendDiagnosticEvent])만 sanitize하면, DataStore 값이
 * 손상되거나 손으로 편집됐을 때 그 원문이 화면과 **클립보드로 그대로 나간다**. 진단 기록은
 * 사용자가 복사해 남에게 붙여넣는 것을 전제로 하므로(파일 맨 위 "민감정보 금지"), 새 코드가
 * 실수로 sanitize를 건너뛰더라도 나가는 길목에서 한 번 더 막혀야 한다. 용량 제한도 같은
 * 이유다 — 부풀려진 값이 그대로 화면을 채우면 안 된다.
 *
 * 확장 `syncDiagnostics.js`의 `normalizeDiagnosticEvents`와 같은 규칙이고, 그쪽도 표시와
 * 리포트 양쪽에서 이걸 거친다.
 */
fun normalizeDiagnosticEvents(
    events: List<DiagnosticEvent>,
    capacity: Int = DIAGNOSTIC_CAPACITY,
): List<DiagnosticEvent> {
    if (capacity <= 0) return emptyList()
    return events.map { event ->
        DiagnosticEvent(
            atMillis = event.atMillis,
            kind = sanitizeDiagnosticCode(event.kind),
            code = sanitizeDiagnosticCode(event.code),
            count = event.count.coerceAtLeast(1),
        )
    }.take(capacity)
}

/**
 * 손상된 항목(필드 수 부족, 숫자가 아닌 시각)은 버린다. 통째로 깨진 문자열이면 자연히 빈
 * 목록이 되므로, 진단 기록 하나 때문에 앱이 못 뜨는 일은 없다.
 *
 * 살아남은 항목도 [normalizeDiagnosticEvents]를 거친다 — 저장된 값이 sanitize를 통과했다는
 * 보장이 디코드 시점에는 없다.
 */
fun decodeDiagnosticEvents(raw: String?, capacity: Int = DIAGNOSTIC_CAPACITY): List<DiagnosticEvent> {
    if (raw.isNullOrBlank()) return emptyList()
    val parsed = raw.split(EVENT_SEPARATOR).mapNotNull { entry ->
        val fields = entry.split(FIELD_SEPARATOR)
        if (fields.size < 4) return@mapNotNull null
        val atMillis = fields[0].toLongOrNull() ?: return@mapNotNull null
        val kind = fields[1]
        val code = fields[2]
        if (kind.isBlank() || code.isBlank()) return@mapNotNull null
        DiagnosticEvent(
            atMillis = atMillis,
            kind = kind,
            code = code,
            count = fields[3].toIntOrNull()?.coerceAtLeast(1) ?: 1,
        )
    }
    return normalizeDiagnosticEvents(parsed, capacity)
}

/** 화면과 클립보드가 같이 쓰는 시각 표기. 초 단위까지는 진단에 필요 없다. */
fun formatDiagnosticTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))

/**
 * 클립보드로 나가는 텍스트. 여기 들어가는 값은 시각·종류·코드·횟수뿐이라, 어디에 붙여넣어도
 * 계정을 특정할 수 있는 정보가 따라나가지 않는다.
 *
 * 화면 문구와 달리 **번역하지 않는다.** 이건 사용자가 읽는 글이 아니라 버그 리포트에 붙여넣는
 * 로그이고, 받는 쪽이 한국어를 읽는다는 보장이 없다. 리소스를 쓰지 않는 덕에 함수도 순수하게
 * 남아 유닛 테스트가 그대로 검증한다 — 종류(`kind`)는 원래부터 저장된 키 그대로 나간다.
 */
fun buildDiagnosticsReport(
    lastSuccessAtMillis: Long?,
    events: List<DiagnosticEvent>,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val lastSuccess = lastSuccessAtMillis?.let { formatDiagnosticTime(it, zone) } ?: "none"
    val header = "TubeLimiter sync diagnostics\nLast success: $lastSuccess"
    // 클립보드로 나가기 직전에 한 번 더 거른다. 호출자가 어디서 목록을 가져왔든(디코드를 거치지
    // 않은 인메모리 값일 수도 있다) 나가는 텍스트는 sanitize를 통과한 값이어야 한다.
    // 확장 `buildDiagnosticsReport`도 normalizeDiagnosticEvents를 통과시킨 뒤 줄을 만든다.
    val safe = normalizeDiagnosticEvents(events)
    if (safe.isEmpty()) return "$header\nRecent failures: none"
    val lines = safe.joinToString("\n") { event ->
        val repeat = if (event.count > 1) " x${event.count}" else ""
        "${formatDiagnosticTime(event.atMillis, zone)} ${event.kind} ${event.code}$repeat"
    }
    return "$header\nRecent failures: ${safe.size}\n$lines"
}

/**
 * 로그아웃 뒤 진단 기록(실패 목록 + 마지막 성공 시각)을 지워야 하는가.
 *
 * **왜 지워야 하나**: [staleSyncWarning]은 마지막 성공 시각으로 24시간을 잰다. 이전 계정의
 * 시각이 남으면 다른 계정으로 로그인했을 때 한 번도 동기화에 성공한 적이 없는데도 그 값이
 * 새 계정의 판정에 끼어들어 정당한 경고가 죽는다. 쌓여 있던 실패 목록도 이미 없는 계정과의
 * 통신 기록이라 새 계정 화면에 남아 있을 이유가 없다. 확장 `options/options.js`의 로그아웃
 * 처리가 `DIAGNOSTIC_STORAGE_KEYS`를 지우는 것과 같은 규칙이고,
 * documents/BACKEND.md가 "로그아웃/계정 삭제 시 양쪽 다 지운다"로 적어둔 계약이다.
 *
 * **기준이 "버튼을 눌렀는가"가 아닌 이유**: supabase-kt의 `signOut()`은 서버가 4xx를 주면
 * 로컬 세션을 먼저 지우고 예외를 다시 던지지만, 네트워크 자체가 끊겼을 땐 세션을 남긴 채
 * 실패한다. 반환값만 보면 두 경우를 구별할 수 없다. 세션이 그대로인데 지워버리면 지금 겪고
 * 있는 실패 기록까지 같이 날아가므로, 세션이 실제로 사라졌을 때만 지운다.
 *
 * 긴급 시청 버킷 캐시는 **여기 해당하지 않는다.** 그건 로그아웃을 한도 우회로로 쓰지 못하게
 * 일부러 남기는 값이다(AppState.clearAccountData 주석 참고).
 */
fun shouldClearDiagnosticsAfterSignOut(stillSignedIn: Boolean): Boolean = !stillSignedIn

/**
 * 홈 화면 경고의 내용. 문구가 아니라 판정 결과만 담는 건 [staleSyncWarning]을 순수하게 두어
 * 유닛 테스트가 임계값을 그대로 검증할 수 있게 하려는 것 — 문구는 화면에서 붙인다.
 */
sealed interface SyncWarning {
    /** 로그인한 뒤 한 번도 성공하지 못했고, 실패는 실제로 기록된 상태. */
    data object NeverSucceeded : SyncWarning

    /** 마지막 성공 이후 [hours]시간이 지났다. */
    data class StaleFor(val hours: Long) : SyncWarning
}

/**
 * 홈 화면에 띄울 한 줄 경고, 띄울 게 없으면 null.
 *
 * 로그아웃 상태에서는 애초에 동기화할 게 없으니 조용히 넘어간다. 성공 기록이 아예 없는데
 * 실패는 쌓인 경우는 "며칠째 실패"를 셀 기준점이 없으므로 시간 대신 사실만 말한다. 문구는
 * 겁주지 않는 선에서 사실만 — 사용자가 지금 당장 뭘 잘못한 게 아니고, 실제로 그냥 오프라인일
 * 수도 있다.
 */
fun staleSyncWarning(
    signedIn: Boolean,
    lastSuccessAtMillis: Long?,
    hasRecordedFailure: Boolean,
    nowMillis: Long,
    thresholdMillis: Long = SYNC_STALE_THRESHOLD_MILLIS,
): SyncWarning? {
    if (!signedIn) return null
    if (lastSuccessAtMillis == null) {
        return if (hasRecordedFailure) SyncWarning.NeverSucceeded else null
    }
    val elapsed = nowMillis - lastSuccessAtMillis
    if (elapsed < thresholdMillis) return null
    return SyncWarning.StaleFor(hours = elapsed / (60L * 60 * 1000))
}
