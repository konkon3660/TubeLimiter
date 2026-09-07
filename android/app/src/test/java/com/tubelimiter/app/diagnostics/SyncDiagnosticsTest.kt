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
