package com.tubelimiter.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId

private val SEOUL = ZoneId.of("Asia/Seoul")
private const val HOUR = 60L * 60 * 1000

class SyncDiagnosticsTest {

    // --- 인코딩 ---

    @Test
    fun `an event list survives a round trip`() {
        val events = listOf(
            DiagnosticEvent(atMillis = 1_700_000_000_000L, kind = DiagnosticKind.SYNC_USAGE, code = "rpc/IOException"),
            DiagnosticEvent(atMillis = 1_699_000_000_000L, kind = DiagnosticKind.AUTH, code = "session_refresh_failed", count = 7),
        )
        assertEquals(events, decodeDiagnosticEvents(encodeDiagnosticEvents(events)))
    }

    @Test
    fun `blank storage decodes to an empty list`() {
        assertTrue(decodeDiagnosticEvents(null).isEmpty())
        assertTrue(decodeDiagnosticEvents("").isEmpty())
        assertTrue(decodeDiagnosticEvents("   ").isEmpty())
    }

    @Test
    fun `a corrupted string decodes to an empty list rather than throwing`() {
        assertTrue(decodeDiagnosticEvents("완전히 깨진 값").isEmpty())
        assertTrue(decodeDiagnosticEvents("not;a;record").isEmpty())
    }

    @Test
    fun `malformed entries are dropped without taking the healthy ones with them`() {
        val healthy = DiagnosticEvent(atMillis = 42L, kind = "auth", code = "http_401")
        val raw = encodeDiagnosticEvents(listOf(healthy)) + "\u001E" + "oops\u001Fauth\u001Fhttp_401\u001F1"
        assertEquals(listOf(healthy), decodeDiagnosticEvents(raw))
    }

    @Test
    fun `a missing count field decodes as one occurrence`() {
        assertEquals(
            listOf(DiagnosticEvent(atMillis = 5L, kind = "auth", code = "x", count = 1)),
            decodeDiagnosticEvents("5\u001Fauth\u001Fx\u001Fnope"),
        )
    }

    // --- 링버퍼 ---

    @Test
    fun `the newest event goes first and the oldest is dropped past capacity`() {
        var events = emptyList<DiagnosticEvent>()
        repeat(5) { index ->
            events = appendDiagnosticEvent(
                events,
                DiagnosticEvent(atMillis = index.toLong(), kind = "sync_usage", code = "code_$index"),
                capacity = 3,
            )
        }
        assertEquals(3, events.size)
        assertEquals(listOf("code_4", "code_3", "code_2"), events.map { it.code })
    }

    @Test
    fun `a repeat of the same kind and code bumps the count instead of stacking up`() {
        val first = appendDiagnosticEvent(
            emptyList(),
            DiagnosticEvent(atMillis = 100L, kind = "sync_settings", code = "pull/http_500"),
        )
        val second = appendDiagnosticEvent(
            first,
            DiagnosticEvent(atMillis = 200L, kind = "sync_settings", code = "pull/http_500"),
        )
        assertEquals(1, second.size)
        assertEquals(2, second[0].count)
        assertEquals(200L, second[0].atMillis)
    }

    @Test
    fun `a repeated failure is promoted back to the front`() {
        var events = appendDiagnosticEvent(
            emptyList(),
            DiagnosticEvent(atMillis = 1L, kind = "sync_settings", code = "pull/http_500"),
        )
        events = appendDiagnosticEvent(events, DiagnosticEvent(atMillis = 2L, kind = "auth", code = "session_refresh_failed"))
        events = appendDiagnosticEvent(events, DiagnosticEvent(atMillis = 3L, kind = "sync_settings", code = "pull/http_500"))

        assertEquals(2, events.size)
        assertEquals("sync_settings", events[0].kind)
        assertEquals(2, events[0].count)
    }

    @Test
    fun `different kinds with the same code stay separate rows`() {
        var events = appendDiagnosticEvent(emptyList(), DiagnosticEvent(1L, "sync_usage", "http_500"))
        events = appendDiagnosticEvent(events, DiagnosticEvent(2L, "sync_streak", "http_500"))
        assertEquals(2, events.size)
    }

    @Test
    fun `a zero capacity keeps nothing`() {
        assertTrue(appendDiagnosticEvent(emptyList(), DiagnosticEvent(1L, "auth", "x"), capacity = 0).isEmpty())
    }

    // --- 민감정보 차단 ---

    @Test
    fun `an email in a code is redacted`() {
        val cleaned = sanitizeDiagnosticCode("row not found for jookpower1022@gmail.com")
        assertFalse(cleaned.contains("@"))
        assertTrue(cleaned.contains("[redacted]"))
    }

    @Test
    fun `a user id shaped uuid is redacted`() {
        val cleaned = sanitizeDiagnosticCode("user_id=3f9a1c22-9b1e-4f0a-8c7d-1a2b3c4d5e6f denied")
        assertFalse(cleaned.contains("3f9a1c22"))
        assertTrue(cleaned.contains("[redacted]"))
    }

    @Test
    fun `a jwt access token is redacted`() {
        val token = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.abcdef"
        val cleaned = sanitizeDiagnosticCode("Authorization Bearer $token")
        assertFalse(cleaned.contains("eyJhbGci"))
    }

    @Test
    fun `separators and newlines cannot be smuggled into a code`() {
        val cleaned = sanitizeDiagnosticCode("a\u001Eb\u001Fc\nd")
        assertFalse(cleaned.contains("\u001E"))
        assertFalse(cleaned.contains("\u001F"))
        assertEquals("a b c d", cleaned)
    }

    @Test
    fun `a code is capped so a long message body cannot ride along`() {
        assertTrue(sanitizeDiagnosticCode("x".repeat(500)).length <= 48)
    }

    @Test
    fun `a blank code becomes a placeholder rather than an empty row`() {
        assertEquals("unknown", sanitizeDiagnosticCode(null))
        assertEquals("unknown", sanitizeDiagnosticCode("   "))
    }

    @Test
    fun `appending sanitizes so nothing unsafe reaches storage`() {
        val events = appendDiagnosticEvent(
            emptyList(),
            DiagnosticEvent(atMillis = 1L, kind = "auth", code = "denied for a@b.com"),
        )
        assertFalse(events[0].code.contains("@"))
        assertEquals(events, decodeDiagnosticEvents(encodeDiagnosticEvents(events)))
    }

    // --- 예외 요약 ---

    @Test
    fun `a failure summary keeps the class name and the http status only`() {
        val error = IOException("Server returned 503 for https://x.supabase.co/rest/v1/settings")
        val summary = summarizeFailure(error)
        assertEquals("IOException/http_503", summary)
    }

    @Test
    fun `a failure summary never carries the raw server message`() {
        val error = IllegalStateException("duplicate key value violates unique constraint for jook@example.com")
        val summary = summarizeFailure(error)
        assertFalse(summary.contains("jook"))
        assertFalse(summary.contains("duplicate"))
        assertEquals("IllegalStateException", summary)
    }

    @Test
    fun `a postgrest error code is kept because it is a classification not a value`() {
        val summary = summarizeFailure(IllegalArgumentException("PGRST116: no rows"))
        assertTrue(summary.contains("PGRST116"))
    }

    @Test
    fun `three digits inside an id are not mistaken for a status code`() {
        val summary = summarizeFailure(IOException("id ab401cd failed"))
        assertEquals("IOException", summary)
    }

    @Test
    fun `a null failure summarizes to a placeholder`() {
        assertEquals("unknown", summarizeFailure(null))
    }

    // supabase-kt의 RestException은 HttpResponse 없이는 만들 수 없어 유닛 테스트에서 흉내낼 수
    // 없다. 그래서 필드 경로는 summarizeFailureFields로 검증한다 — 확장 syncDiagnostics.js가
    // statusFromField(error.status)와 error.code를 읽는 것과 같은 규칙이어야, 같은 실패가 두
    // 기기에서 같은 코드로 남는다(documents/BACKEND.md "나란히 놓고 읽는다").

    @Test
    fun `상태 코드가 필드로 오면 메시지에 없어도 잡는다`() {
        val summary = summarizeFailureFields(
            name = "UnauthorizedRestException",
            message = "JWT expired",
            statusField = 401,
            codeField = null,
        )
        assertEquals("UnauthorizedRestException/http_401", summary)
    }

    @Test
    fun `필드 상태가 메시지에서 주운 숫자보다 우선한다`() {
        val summary = summarizeFailureFields(
            name = "UnknownRestException",
            message = "upstream said 503",
            statusField = 409,
            codeField = null,
        )
        assertEquals("UnknownRestException/http_409", summary)
    }

    @Test
    fun `상태 코드가 아닌 필드 값은 버린다`() {
        assertEquals("RestException", summarizeFailureFields("RestException", null, 200, null))
        assertEquals("RestException", summarizeFailureFields("RestException", null, 0, null))
        assertEquals("RestException", summarizeFailureFields("RestException", null, 600, null))
    }

    @Test
    fun `code 필드도 PostgREST 허용 목록만 통과한다`() {
        assertEquals(
            "NotFoundRestException/http_406/PGRST116",
            summarizeFailureFields("NotFoundRestException", null, 406, "PGRST116"),
        )
        // SQLSTATE나 서버 문구가 그 자리에 실려 와도 그대로 나가지 않는다.
        assertEquals(
            "BadRequestRestException/http_400",
            summarizeFailureFields("BadRequestRestException", null, 400, "23505 for a@b.com"),
        )
    }

    @Test
    fun `필드가 비면 예전처럼 메시지 정규식으로 떨어진다`() {
        assertEquals(
            "HttpRequestException/http_502/PGRST301",
            summarizeFailureFields("HttpRequestException", "502 PGRST301", null, null),
        )
    }

    // --- 화면 노출 ---

    @Test
    fun `the clipboard report carries times kinds and codes only`() {
        val report = buildDiagnosticsReport(
            lastSuccessAtMillis = 1_700_000_000_000L,
            events = listOf(DiagnosticEvent(1_700_000_000_000L, "sync_usage", "rpc/IOException", count = 3)),
            zone = SEOUL,
        )
        assertTrue(report.contains("rpc/IOException"))
        assertTrue(report.contains("x3"))
        assertFalse(report.contains("@"))
    }

    @Test
    fun `an empty report still says when the last success was`() {
        val report = buildDiagnosticsReport(lastSuccessAtMillis = null, events = emptyList(), zone = SEOUL)
        assertTrue(report.contains("none"))
    }

    // 손으로 편집되거나 손상된 DataStore 값이 화면·클립보드로 그대로 나가면 안 된다.
    // 확장 normalizeDiagnosticEvents가 sanitize와 용량 제한을 둘 다 거는 것과 같은 규칙.

    @Test
    fun `디코드가 저장된 값을 다시 sanitize한다`() {
        // 손으로 써넣은 것처럼 sanitize를 거치지 않은 저장 문자열.
        val raw = "1\u001Fauth\u001Fdenied for jook@example.com\u001F1"
        val decoded = decodeDiagnosticEvents(raw)
        assertEquals(1, decoded.size)
        assertFalse(decoded[0].code.contains("@"))
        assertTrue(decoded[0].code.contains("[redacted]"))
    }

    @Test
    fun `디코드가 용량을 넘긴 저장 값을 잘라낸다`() {
        val raw = (1..5).joinToString("\u001E") { "$it\u001Fauth\u001Fcode_$it\u001F1" }
        assertEquals(2, decodeDiagnosticEvents(raw, capacity = 2).size)
        assertTrue(decodeDiagnosticEvents(raw, capacity = 0).isEmpty())
    }

    @Test
    fun `디코드가 길이 제한도 다시 건다`() {
        val raw = "1\u001Fauth\u001F${"x".repeat(500)}\u001F1"
        assertTrue(decodeDiagnosticEvents(raw)[0].code.length <= 48)
    }

    @Test
    fun `클립보드 리포트는 sanitize를 거치지 않은 목록도 걸러낸다`() {
        val report = buildDiagnosticsReport(
            lastSuccessAtMillis = 1_700_000_000_000L,
            // 디코드를 거치지 않고 직접 만들어진 목록(인메모리 경로)을 흉내낸다.
            events = listOf(DiagnosticEvent(1_700_000_000_000L, "auth", "denied for jook@example.com")),
            zone = SEOUL,
        )
        assertFalse(report.contains("@"))
        assertFalse(report.contains("jook"))
        assertTrue(report.contains("[redacted]"))
    }

    @Test
    fun `클립보드 리포트는 용량을 넘긴 목록도 잘라낸다`() {
        val many = (1..DIAGNOSTIC_CAPACITY + 10).map {
            DiagnosticEvent(it.toLong(), "auth", "code_$it")
        }
        val report = buildDiagnosticsReport(lastSuccessAtMillis = null, events = many, zone = SEOUL)
        assertTrue(report.contains("Recent failures: $DIAGNOSTIC_CAPACITY"))
        assertFalse(report.contains("code_${DIAGNOSTIC_CAPACITY + 1}"))
    }

    @Test
    fun `an event time renders in the given zone`() {
        // 1_700_000_000_000 = 2023-11-14T22:13:20Z, KST 기준 07:13.
        assertEquals("11-15 07:13", formatDiagnosticTime(1_700_000_000_000L, SEOUL))
    }

    // --- 경고 임계값 ---

    @Test
    fun `a signed out user is never warned`() {
        val warning = staleSyncWarning(
            signedIn = false,
            lastSuccessAtMillis = 0L,
            hasRecordedFailure = true,
            nowMillis = 100 * HOUR,
        )
        assertNull(warning)
    }

    @Test
    fun `a sync that succeeded within the threshold is not warned about`() {
        val warning = staleSyncWarning(
            signedIn = true,
            lastSuccessAtMillis = 100 * HOUR,
            hasRecordedFailure = true,
            nowMillis = 100 * HOUR + 23 * HOUR,
        )
        assertNull(warning)
    }

    @Test
    fun `past the threshold the warning states the elapsed hours`() {
        val warning = staleSyncWarning(
            signedIn = true,
            lastSuccessAtMillis = 0L,
            hasRecordedFailure = true,
            nowMillis = 30 * HOUR,
        )
        assertEquals(SyncWarning.StaleFor(hours = 30), warning)
    }

    @Test
    fun `the boundary itself counts as stale`() {
        val warning = staleSyncWarning(
            signedIn = true,
            lastSuccessAtMillis = 0L,
            hasRecordedFailure = false,
            nowMillis = SYNC_STALE_THRESHOLD_MILLIS,
        )
        assertNotNull(warning)
    }

    // --- 로그아웃 시 정리 ---

    @Test
    fun `세션이 사라졌으면 진단 기록을 지운다`() {
        assertTrue(shouldClearDiagnosticsAfterSignOut(stillSignedIn = false))
    }

    @Test
    fun `로그아웃이 실패해 세션이 남았으면 지우지 않는다`() {
        assertFalse(shouldClearDiagnosticsAfterSignOut(stillSignedIn = true))
    }

    @Test
    fun `지우지 않으면 이전 계정의 성공 시각이 새 계정의 경고를 죽인다`() {
        // 로그아웃이 진단 기록을 지워야 하는 이유 자체다: 이전 계정이 한 시간 전에 성공했던
        // 시각이 남아 있으면, 다른 계정으로 로그인해 처음부터 계속 실패해도 24시간 판정이
        // 그 시각을 기준으로 돌아 경고가 조용히 죽는다.
        val previousAccountSuccess = 100 * HOUR
        val now = previousAccountSuccess + 1 * HOUR

        val carriedOver = staleSyncWarning(
            signedIn = true,
            lastSuccessAtMillis = previousAccountSuccess,
            hasRecordedFailure = true,
            nowMillis = now,
        )
        assertNull(carriedOver)

        // 지운 뒤(= 성공 기록 없음)에는 실패가 쌓이는 즉시 사실대로 알린다.
        val cleared = staleSyncWarning(
            signedIn = true,
            lastSuccessAtMillis = null,
            hasRecordedFailure = true,
            nowMillis = now,
        )
        assertEquals(SyncWarning.NeverSucceeded, cleared)
    }

    @Test
    fun `never having succeeded warns only once a failure has actually been recorded`() {
        val justSignedIn = staleSyncWarning(
            signedIn = true,
            lastSuccessAtMillis = null,
            hasRecordedFailure = false,
            nowMillis = 5 * HOUR,
        )
        assertNull(justSignedIn)

        val failingSinceSignIn = staleSyncWarning(
            signedIn = true,
            lastSuccessAtMillis = null,
            hasRecordedFailure = true,
            nowMillis = 5 * HOUR,
        )
        assertNotNull(failingSinceSignIn)
    }
}
