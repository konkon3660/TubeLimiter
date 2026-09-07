package com.tubelimiter.app.diagnostics

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

/** 어느 경로가 실패했는지. 화면 표시용 한글 이름은 [diagnosticKindLabel]에 있다. */
object DiagnosticKind {
    const val SYNC_SETTINGS = "sync_settings"
    const val SYNC_STREAK = "sync_streak"
    const val SYNC_USAGE = "sync_usage"
    const val EMERGENCY_FETCH = "emergency_fetch"
    const val AUTH = "auth"
    const val MONITOR = "monitor"
}

fun diagnosticKindLabel(kind: String): String = when (kind) {
    DiagnosticKind.SYNC_SETTINGS -> "설정 동기화"
    DiagnosticKind.SYNC_STREAK -> "연속 기록 / 뱃지"
    DiagnosticKind.SYNC_USAGE -> "사용시간 보고"
    DiagnosticKind.EMERGENCY_FETCH -> "긴급 횟수 조회"
    DiagnosticKind.AUTH -> "로그인 세션"
    DiagnosticKind.MONITOR -> "감시 루프"
    else -> kind
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
 * 예외를 **허용 목록 방식**으로 짧은 코드로 줄인다. 남기는 건 예외 클래스 이름과, 메시지에서
 * 뽑아낸 HTTP 상태 / PostgREST 코드뿐 — 메시지 본문은 한 글자도 옮기지 않는다. 원문에 무엇이
 * 들어 있을지 우리가 통제할 수 없기 때문이다(파일 맨 위 "민감정보 금지" 참고).
 */
fun summarizeFailure(error: Throwable?): String {
    if (error == null) return "unknown"
    val name = error::class.simpleName ?: "Throwable"
    val message = error.message.orEmpty()
    val status = HTTP_STATUS_PATTERN.find(message)?.value?.let { "http_$it" }
    val postgrest = POSTGREST_CODE_PATTERN.find(message)?.value
    return sanitizeDiagnosticCode(listOfNotNull(name, status, postgrest).joinToString("/"))
}

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
 * 손상된 항목(필드 수 부족, 숫자가 아닌 시각)은 버린다. 통째로 깨진 문자열이면 자연히 빈
 * 목록이 되므로, 진단 기록 하나 때문에 앱이 못 뜨는 일은 없다.
 */
fun decodeDiagnosticEvents(raw: String?): List<DiagnosticEvent> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(EVENT_SEPARATOR).mapNotNull { entry ->
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
}

/** 화면과 클립보드가 같이 쓰는 시각 표기. 초 단위까지는 진단에 필요 없다. */
fun formatDiagnosticTime(millis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    TIME_FORMATTER.format(Instant.ofEpochMilli(millis).atZone(zone))

/**
 * 클립보드로 나가는 텍스트. 여기 들어가는 값은 시각·종류·코드·횟수뿐이라, 어디에 붙여넣어도
 * 계정을 특정할 수 있는 정보가 따라나가지 않는다.
 */
fun buildDiagnosticsReport(
    lastSuccessAtMillis: Long?,
    events: List<DiagnosticEvent>,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val lastSuccess = lastSuccessAtMillis?.let { formatDiagnosticTime(it, zone) } ?: "없음"
    val header = "TubeLimiter 동기화 진단\n최근 성공: $lastSuccess"
    if (events.isEmpty()) return "$header\n최근 실패: 없음"
    val lines = events.joinToString("\n") { event ->
        val repeat = if (event.count > 1) " x${event.count}" else ""
        "${formatDiagnosticTime(event.atMillis, zone)} ${event.kind} ${event.code}$repeat"
    }
    return "$header\n최근 실패 ${events.size}건\n$lines"
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
): String? {
    if (!signedIn) return null
    if (lastSuccessAtMillis == null) {
        return if (hasRecordedFailure) {
            "아직 한 번도 동기화에 성공하지 못했어요. 설정 > 동기화 상태에서 확인할 수 있어요."
        } else {
            null
        }
    }
    val elapsed = nowMillis - lastSuccessAtMillis
    if (elapsed < thresholdMillis) return null
    val hours = elapsed / (60L * 60 * 1000)
    return "동기화가 ${hours}시간째 되지 않고 있어요. 설정 > 동기화 상태에서 확인할 수 있어요."
}
