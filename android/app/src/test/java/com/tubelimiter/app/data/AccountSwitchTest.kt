package com.tubelimiter.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 규칙의 원본은 확장 `extension/src/lib/accountReset.js`의 `planAccountSwitch`이고, 목록은
// documents/BACKEND.md "계정 전환 — 무엇을 지우고 무엇을 남기나"가 계약으로 적어둔 것이다.

class AccountSwitchTest {

    @Test
    fun `a device with no owner mark keeps everything and only records the owner`() {
        // 이 가드가 생기기 전부터 쓰던 기기이거나 이 기기의 첫 로그인. 여기서 지우면 멀쩡히
        // 쓰던 사람이 앱 업데이트 한 번에 자기 기록을 잃는다.
        val plan = planAccountSwitch(storedUserId = null, currentUserId = "user-a")
        assertFalse(plan.switched)
        assertEquals("user-a", plan.ownerToStore)
    }

    @Test
    fun `signing back in on the same account clears nothing`() {
        val plan = planAccountSwitch(storedUserId = "user-a", currentUserId = "user-a")
        assertFalse(plan.switched)
        // 표식이 이미 맞으므로 다시 쓸 필요가 없다.
        assertNull(plan.ownerToStore)
    }

    @Test
    fun `a different account clears and takes over the owner mark`() {
        val plan = planAccountSwitch(storedUserId = "user-a", currentUserId = "user-b")
        assertTrue(plan.switched)
        assertEquals("user-b", plan.ownerToStore)
    }

    @Test
    fun `signed out is not a switch - nothing is judged and nothing is written`() {
        // "지금 주인이 없다"는 "주인이 바뀌었다"가 아니다. 여기서 지우면 로그아웃이 곧 기록 삭제가 된다.
        listOf<String?>(null, "user-a").forEach { stored ->
            val plan = planAccountSwitch(storedUserId = stored, currentUserId = null)
            assertFalse(plan.switched)
            assertNull(plan.ownerToStore)
        }
    }

    @Test
    fun `a blank stored mark is treated as no mark`() {
        // 저장이 손상됐거나 옛 빌드가 빈 문자열을 남긴 경우. "표식 없음"과 같이 다뤄야 멀쩡한
        // 기록이 빈 문자열 하나 때문에 날아가지 않는다.
        val plan = planAccountSwitch(storedUserId = "", currentUserId = "user-a")
        assertFalse(plan.switched)
        assertEquals("user-a", plan.ownerToStore)
    }

    @Test
    fun `the removed and preserved lists never overlap`() {
        val overlap = ACCOUNT_SWITCH_REMOVED_KEYS.intersect(ACCOUNT_SWITCH_PRESERVED_KEYS.toSet())
        assertEquals(emptySet<String>(), overlap)
    }

    @Test
    fun `everything the account contributed is on the removed list`() {
        // 하나라도 빠지면 새 주인이 앞 계정의 값을 물려받는다 — 특히 hardcore_mode(설정 캐시),
        // daily_usage 동기화 마커, 다른 기기 몫 캐시가 그렇다.
        listOf(
            "usage_history",
            "usage_history_hourly",
            "emergency_history",
            "emergency_uses_history",
            "limit_history",
            "last_rollover_date",
            "streak_current",
            "achievements",
            "daily_usage_sync_date",
            "daily_usage_synced_millis",
            "daily_usage_combined_millis",
            "daily_usage_emergency_synced_millis",
            "daily_usage_emergency_uses_synced",
            "emergency_uses_other_devices",
            "emergency_uses_other_devices_key",
            "diagnostic_events",
            "last_sync_success_at",
            "pending_settings_sync",
            "hardcore_mode",
            "hardcore_disable_requested_at",
            "daily_limit_minutes",
            "scheduled_blocks",
        ).forEach { key ->
            assertTrue(key, key in ACCOUNT_SWITCH_REMOVED_KEYS)
        }
    }

    @Test
    fun `the live protection state is on the preserved list`() {
        // 이걸 지우면 "다른 계정으로 로그인"이 지금 나를 막고 있는 차단·집중 세션에서
        // 빠져나가는 길이 되고, 이 기기의 남은 긴급 횟수가 full로 되살아난다.
        listOf(
            "manual_block",
            "focus_end",
            "focus_delay_end",
            "focus_delay_duration",
            "focus_stop_requested_at",
            "emergency_end",
            "emergency_remaining",
            "emergency_reset_key",
            "last_emergency_granted_at",
            "alarm_date",
            "schedule_block_was_active",
            "monitor_heartbeat_at",
            // 기기별 설정: 감시 켜짐 여부·차트 범위·감시 대상은 계정에서 온 값이 아니다.
            "monitoring_enabled",
            "chart_range_days",
            "watch_youtube_music",
        ).forEach { key ->
            assertTrue(key, key in ACCOUNT_SWITCH_PRESERVED_KEYS)
        }
    }

    @Test
    fun `the owner mark itself is not on the switch removal list`() {
        // 확장 ACCOUNT_SWITCH_REMOVED_KEYS와 같다 — 곧바로 새 주인으로 덮어쓰므로 지우는
        // 목록에 넣을 이유가 없다(계정 삭제에서는 지운다).
        assertFalse(ACCOUNT_OWNER_KEY in ACCOUNT_SWITCH_REMOVED_KEYS)
        assertFalse(ACCOUNT_OWNER_KEY in ACCOUNT_SWITCH_PRESERVED_KEYS)
    }
}
