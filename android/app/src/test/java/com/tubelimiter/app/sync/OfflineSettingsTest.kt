package com.tubelimiter.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 규칙의 원본은 확장 `extension/src/lib/offlineSettings.js`이고, 계약은 documents/BACKEND.md
// "오프라인 편집 대기분"이다. 상태 코드 분류는 확장 `isOfflineFailure`와 같은 목록이어야 한다.

class OfflineSettingsTest {

    // --- "로그아웃"과 "서버 불통" 가르기 ---

    @Test
    fun `a status the server answered with is not offline`() {
        // 답이 왔다 = 서버가 살아 있다. 여기를 뭉뚱그리면 토큰이 취소된 계정의 편집이 영원히
        // 대기분으로 쌓인다.
        listOf(400, 401, 403, 404, 409, 422).forEach { status ->
            assertFalse(status.toString(), isOfflineFailureFields(status, null))
        }
    }

    @Test
    fun `server errors and retryable statuses are offline`() {
        listOf(500, 502, 503, 504, 408, 425, 429).forEach { status ->
            assertTrue(status.toString(), isOfflineFailureFields(status, null))
        }
    }

    @Test
    fun `a PostgREST code means the server answered, so not offline`() {
        // RLS 거부·스키마 캐시 문제 등. 오프라인이라고 판단하면 진짜 버그가 대기분으로 숨는다.
        assertFalse(isOfflineFailureFields(null, "PGRST116"))
        assertFalse(isOfflineFailureFields(null, "PGRST301"))
    }

    @Test
    fun `no status and no server code means the response never arrived`() {
        // DNS 실패·타임아웃·pause된 Supabase 프로젝트가 전부 이 모양으로 온다.
        assertTrue(isOfflineFailureFields(null, null))
        assertTrue(isOfflineFailureFields(0, null))
        assertTrue(isOfflineFailureFields(null, "SocketTimeout"))
    }

    // --- 대기분 누적 ---

    @Test
    fun `pending columns accumulate across several offline saves`() {
        // 매번 통째로 덮어쓰면 앞선 저장에서만 건드린 항목이 사라진다.
        val first = accumulatePendingSettings(null, "user-a", setOf(SettingsColumn.DAILY_LIMIT_MS))
        val second = accumulatePendingSettings(first, "user-a", setOf(SettingsColumn.EMERGENCY_CONFIG))
        assertEquals(
            setOf(SettingsColumn.DAILY_LIMIT_MS, SettingsColumn.EMERGENCY_CONFIG),
            second?.columns,
        )
        assertEquals("user-a", second?.userId)
    }

    @Test
    fun `a pending patch from another account is dropped, not merged`() {
        // A의 오프라인 편집이 B의 계정으로 올라가면 안 된다.
        val fromA = PendingSettings("user-a", setOf(SettingsColumn.DAILY_LIMIT_MS))
        val forB = accumulatePendingSettings(fromA, "user-b", setOf(SettingsColumn.SCHEDULED_BLOCKS))
        assertEquals(PendingSettings("user-b", setOf(SettingsColumn.SCHEDULED_BLOCKS)), forB)
    }

    @Test
    fun `columns this build does not own are dropped`() {
        // 모르는 이름을 payload에 넣으면 upsert가 통째로 실패한다. 브라우저 전용 컬럼도 여기서 걸린다.
        val pending = accumulatePendingSettings(
            null,
            "user-a",
            setOf(SettingsColumn.HARDCORE_MODE, "whitelist", "always_block_shorts"),
        )
        assertEquals(setOf(SettingsColumn.HARDCORE_MODE), pending?.columns)
    }

    @Test
    fun `an empty patch leaves nothing pending`() {
        assertNull(accumulatePendingSettings(null, "user-a", emptySet()))
    }

    @Test
    fun `readPendingFor only hands back the signed-in account's patch`() {
        val stored = PendingSettings("user-a", setOf(SettingsColumn.DAILY_LIMIT_MS))
        assertEquals(stored, readPendingFor(stored, "user-a"))
        assertNull(readPendingFor(stored, "user-b"))
        assertNull(readPendingFor(stored, null))
        assertNull(readPendingFor(null, "user-a"))
        assertNull(readPendingFor(PendingSettings("user-a", emptySet()), "user-a"))
    }

    // --- 서버가 살아난 뒤 누가 이기나 ---

    @Test
    fun `a pending patch beats the server pull`() {
        // 기본 계약("settings는 서버가 진실의 원천")을 이 한 줄만 뒤집는다 — 대기분은 사용자가
        // 방금 명시적으로 한 변경이라, 서버 값으로 덮으면 "저장했는데 되돌아왔다"가 된다.
        val stored = PendingSettings("user-a", setOf(SettingsColumn.DAILY_LIMIT_MS))
        val plan = resolveSettingsSyncPlan(stored, "user-a")
        assertEquals(SettingsSyncAction.PUSH_PENDING, plan.action)
        assertEquals(stored, plan.pending)
    }

    @Test
    fun `no pending patch means the usual pull`() {
        val plan = resolveSettingsSyncPlan(null, "user-a")
        assertEquals(SettingsSyncAction.PULL, plan.action)
        assertNull(plan.pending)
    }

    @Test
    fun `another account's pending patch does not win - it is not even pushed`() {
        val plan = resolveSettingsSyncPlan(PendingSettings("user-a", setOf(SettingsColumn.HARDCORE_MODE)), "user-b")
        assertEquals(SettingsSyncAction.PULL, plan.action)
        assertNull(plan.pending)
    }

    // --- 저장 포맷 ---

    @Test
    fun `a pending patch survives a round trip through storage`() {
        val pending = PendingSettings(
            "1f1b0f8a-0000-4000-8000-000000000001",
            setOf(SettingsColumn.SCHEDULED_BLOCKS, SettingsColumn.DAILY_LIMIT_MS),
        )
        assertEquals(pending, decodePendingSettings(encodePendingSettings(pending)))
    }

    @Test
    fun `nothing to push encodes to null so the caller removes the key`() {
        assertNull(encodePendingSettings(null))
        assertNull(encodePendingSettings(PendingSettings("user-a", emptySet())))
        assertNull(encodePendingSettings(PendingSettings("", setOf(SettingsColumn.HARDCORE_MODE))))
    }

    @Test
    fun `a damaged stored value reads as no pending patch`() {
        // 저장된 문자열 하나 때문에 동기화가 멈추면 안 된다.
        assertNull(decodePendingSettings(null))
        assertNull(decodePendingSettings(""))
        // 구분자가 아예 없다
        assertNull(decodePendingSettings("user-a"))
        // 구분자는 있는데 컬럼이 비었다
        assertNull(decodePendingSettings("user-a\u001F"))
        // 이 빌드가 모르는 컬럼만 들어 있다
        assertNull(decodePendingSettings("user-a\u001Fnot_a_column"))
        // 올릴 계정을 모른다
        assertNull(decodePendingSettings("\u001Fhardcore_mode"))
    }
}
