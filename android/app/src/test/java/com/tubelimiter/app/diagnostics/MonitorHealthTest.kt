package com.tubelimiter.app.diagnostics

import com.tubelimiter.app.permission.AppPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOUR = 60L * 60 * 1000
private const val NOW = 1_700_000_000_000L

/**
 * 권한 회수·서비스 사망 판정 (documents/QA_REVIEW.md §1.7). 임계값이 걸린 분기라 서비스를 띄우지
 * 않고 여기서 그대로 검증한다.
 */
class MonitorHealthTest {

    private val allGranted = emptyList<AppPermission>()

    @Test
    fun `healthy monitor warns about nothing`() {
        assertNull(
            monitorWarning(
                monitoringEnabled = true,
                missingPermissions = allGranted,
                lastHeartbeatAtMillis = NOW - 5_000,
                nowMillis = NOW,
            ),
        )
    }

    @Test
    fun `monitoring the user turned off is not a warning`() {
        assertNull(
            monitorWarning(
                monitoringEnabled = false,
                missingPermissions = listOf(AppPermission.USAGE_ACCESS),
                lastHeartbeatAtMillis = NOW - 10 * HOUR,
                nowMillis = NOW,
            ),
        )
    }

    @Test
    fun `a revoked permission wins over staleness`() {
        val warning = monitorWarning(
            monitoringEnabled = true,
            missingPermissions = listOf(AppPermission.OVERLAY),
            lastHeartbeatAtMillis = NOW - 10 * HOUR,
            nowMillis = NOW,
        )
        assertEquals(MonitorWarning.PermissionsRevoked(listOf(AppPermission.OVERLAY)), warning)
    }

    @Test
    fun `never having ticked is not a warning`() {
        assertNull(
            monitorWarning(
                monitoringEnabled = true,
                missingPermissions = allGranted,
                lastHeartbeatAtMillis = null,
                nowMillis = NOW,
            ),
        )
    }

    @Test
    fun `a heartbeat just under the threshold stays quiet`() {
        assertNull(
            monitorWarning(
                monitoringEnabled = true,
                missingPermissions = allGranted,
                lastHeartbeatAtMillis = NOW - (MONITOR_STALE_THRESHOLD_MILLIS - 1),
                nowMillis = NOW,
            ),
        )
    }

    @Test
    fun `crossing the threshold reports whole hours`() {
        val warning = monitorWarning(
            monitoringEnabled = true,
            missingPermissions = allGranted,
            lastHeartbeatAtMillis = NOW - (5 * HOUR + 30 * 60 * 1000),
            nowMillis = NOW,
        )
        assertEquals(MonitorWarning.StaleFor(5), warning)
    }

    @Test
    fun `threshold is overridable for callers with their own budget`() {
        val warning = monitorWarning(
            monitoringEnabled = true,
            missingPermissions = allGranted,
            lastHeartbeatAtMillis = NOW - HOUR,
            nowMillis = NOW,
            thresholdMillis = HOUR,
        )
        assertEquals(MonitorWarning.StaleFor(1), warning)
    }

    @Test
    fun `a clock that jumped backwards does not warn`() {
        assertNull(
            monitorWarning(
                monitoringEnabled = true,
                missingPermissions = allGranted,
                lastHeartbeatAtMillis = NOW + HOUR,
                nowMillis = NOW,
            ),
        )
    }

    @Test
    fun `no stored heartbeat means no reportable gap`() {
        assertNull(monitorGapMillis(null, NOW))
    }

    @Test
    fun `a short restart is not a gap`() {
        assertNull(monitorGapMillis(NOW - 30_000, NOW))
    }

    @Test
    fun `a long outage is reported at the threshold`() {
        assertEquals(
            MONITOR_STALE_THRESHOLD_MILLIS,
            monitorGapMillis(NOW - MONITOR_STALE_THRESHOLD_MILLIS, NOW),
        )
    }

    @Test
    fun `gap code carries whole hours`() {
        assertEquals("gap/3h", monitorGapCode(3 * HOUR + 59 * 60 * 1000))
        assertEquals("gap/0h", monitorGapCode(-1))
    }

    @Test
    fun `permission loss code lists short names`() {
        assertEquals(
            "permission_lost/usage,overlay",
            permissionLossCode(listOf(AppPermission.USAGE_ACCESS, AppPermission.OVERLAY)),
        )
        assertEquals("permission_lost/none", permissionLossCode(emptyList()))
    }

    /** 코드가 잘리면 어떤 권한이 빠졌는지 못 읽는다 — 네 개가 한꺼번에 빠져도 살아남아야 한다. */
    @Test
    fun `permission loss code survives the diagnostic sanitizer intact`() {
        val code = permissionLossCode(AppPermission.entries.toList())
        assertEquals(code, sanitizeDiagnosticCode(code))
        assertTrue(code.contains("notify"))
    }
}
