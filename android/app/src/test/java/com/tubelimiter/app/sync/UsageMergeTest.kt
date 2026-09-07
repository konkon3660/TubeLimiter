package com.tubelimiter.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class UsageMergeTest {

    @Test
    fun `nothing synced yet reports the full local figure as the delta`() {
        assertEquals(20_000L, usageDeltaSinceSync(20_000L, syncedDate = null, syncedMillis = 0L, today = "2026-09-03"))
    }

    @Test
    fun `only the usage since the last sync is a delta`() {
        val delta = usageDeltaSinceSync(35_000L, syncedDate = "2026-09-03", syncedMillis = 20_000L, today = "2026-09-03")
        assertEquals(15_000L, delta)
    }

    @Test
    fun `a stale baseline from yesterday does not carry over`() {
        val delta = usageDeltaSinceSync(5_000L, syncedDate = "2026-09-02", syncedMillis = 20_000L, today = "2026-09-03")
        assertEquals(5_000L, delta)
    }

    @Test
    fun `the delta never goes negative even if the local clock rewound`() {
        val delta = usageDeltaSinceSync(10_000L, syncedDate = "2026-09-03", syncedMillis = 20_000L, today = "2026-09-03")
        assertEquals(0L, delta)
    }

    @Test
    fun `combined usage adds only the other devices' share on top of the local figure`() {
        // This device already told the server about 20s; the server total is 50s, so the
        // other 30s came from elsewhere and should be added to whatever this device sees now.
        val combined = combinedUsedMillis(localUsedMillis = 25_000L, syncedMillis = 20_000L, remoteTotalMillis = 50_000L)
        assertEquals(55_000L, combined)
    }

    @Test
    fun `a single device sees its own usage unchanged`() {
        val combined = combinedUsedMillis(localUsedMillis = 25_000L, syncedMillis = 25_000L, remoteTotalMillis = 25_000L)
        assertEquals(25_000L, combined)
    }

    @Test
    fun `a remote total behind the synced baseline contributes nothing negative`() {
        val combined = combinedUsedMillis(localUsedMillis = 10_000L, syncedMillis = 20_000L, remoteTotalMillis = 15_000L)
        assertEquals(10_000L, combined)
    }

    // --- 긴급 시청 횟수 ---

    @Test
    fun `use-count deltas follow the same rules as time deltas`() {
        // 아직 아무것도 안 보냈으면 로컬 값 전부가 델타.
        assertEquals(2, emergencyUsesDeltaSinceSync(2, syncedDate = null, syncedUses = 0, today = TODAY))
        // 보고 이후 늘어난 만큼만.
        assertEquals(1, emergencyUsesDeltaSinceSync(3, syncedDate = TODAY, syncedUses = 2, today = TODAY))
        // 날짜가 바뀌면 베이스라인은 0으로 되돌아간다.
        assertEquals(1, emergencyUsesDeltaSinceSync(1, syncedDate = "2026-09-02", syncedUses = 3, today = TODAY))
        // 음수는 나오지 않는다.
        assertEquals(0, emergencyUsesDeltaSinceSync(1, syncedDate = TODAY, syncedUses = 3, today = TODAY))
    }

    @Test
    fun `a daily bucket folds in only the other devices' share`() {
        // 이 기기는 1회 써서 보고까지 마쳤고, 서버 합계는 3회 - 나머지 2회는 다른 기기 몫.
        val combined = combinedEmergencyUses(
            bucketDateKeys = listOf(TODAY),
            localUsesByDate = mapOf(TODAY to 1),
            remoteUsesByDate = mapOf(TODAY to 3),
            todayKey = TODAY,
            syncedTodayUses = 1,
        )
        assertEquals(3, combined)
        assertEquals(2, otherDevices(listOf(TODAY), mapOf(TODAY to 1), mapOf(TODAY to 3), syncedTodayUses = 1))
    }

    @Test
    fun `a server total behind the local count never lowers it`() {
        // 방금 쓴 3번째 횟수를 아직 못 올린 상태(서버 2회). 로컬 값이 이겨야 판정이 느슨해지지 않는다.
        val combined = combinedEmergencyUses(
            bucketDateKeys = listOf(TODAY),
            localUsesByDate = mapOf(TODAY to 3),
            remoteUsesByDate = mapOf(TODAY to 2),
            todayKey = TODAY,
            syncedTodayUses = 2,
        )
        assertEquals(3, combined)
        assertEquals(0, otherDevices(listOf(TODAY), mapOf(TODAY to 3), mapOf(TODAY to 2), syncedTodayUses = 2))
    }

    @Test
    fun `a fresh device with no local history inherits the whole server bucket`() {
        // 기기를 갈아탄 직후: 로컬은 0회지만 계정 전체로는 이미 3회를 썼다 - 이게 막으려는 구멍이다.
        val combined = combinedEmergencyUses(
            bucketDateKeys = listOf(TODAY),
            localUsesByDate = emptyMap(),
            remoteUsesByDate = mapOf(TODAY to 3),
            todayKey = TODAY,
            syncedTodayUses = 0,
        )
        assertEquals(3, combined)
        assertEquals(3, otherDevices(listOf(TODAY), emptyMap(), mapOf(TODAY to 3), syncedTodayUses = 0))
    }

    @Test
    fun `a weekly bucket sums every day since the bucket started`() {
        val week = listOf("2026-08-31", "2026-09-01", "2026-09-02", TODAY)
        val local = mapOf("2026-08-31" to 1, TODAY to 1)
        // 월요일치는 서버에도 1회(이 기기가 올린 그 1회), 화요일 1회는 다른 기기,
        // 오늘은 이 기기 1회 + 다른 기기 1회.
        val remote = mapOf("2026-08-31" to 1, "2026-09-01" to 1, TODAY to 2)

        assertEquals(4, combinedEmergencyUses(week, local, remote, TODAY, syncedTodayUses = 1))
        // 로컬 몫 2회를 빼면 다른 기기가 쓴 건 2회.
        assertEquals(2, otherDevices(week, local, remote, syncedTodayUses = 1))
    }

    @Test
    fun `looking only at today's row under-counts a weekly bucket`() {
        val week = listOf("2026-08-31", "2026-09-01", "2026-09-02", TODAY)
        val remote = mapOf("2026-08-31" to 2, "2026-09-01" to 1)

        // 오늘 행만 보면 0회로 보이지만, 이번 주 버킷으로는 이미 3회를 썼다.
        assertEquals(0, combinedEmergencyUses(listOf(TODAY), emptyMap(), remote, TODAY, syncedTodayUses = 0))
        assertEquals(3, combinedEmergencyUses(week, emptyMap(), remote, TODAY, syncedTodayUses = 0))
    }

    @Test
    fun `a past day the local record already covers is not double counted`() {
        // 지난 날은 "보고분 = 로컬 기록"으로 보므로 서버에 있는 그 1회가 다시 더해지지 않는다.
        val week = listOf("2026-08-31", TODAY)
        val local = mapOf("2026-08-31" to 2)
        val remote = mapOf("2026-08-31" to 2)
        assertEquals(2, combinedEmergencyUses(week, local, remote, TODAY, syncedTodayUses = 0))
        assertEquals(0, otherDevices(week, local, remote, syncedTodayUses = 0))
    }

    @Test
    fun `an unreported past day offsets other devices within the same bucket`() {
        // 클램프를 날짜별이 아니라 버킷 합계에 한 번만 거는 확장 규칙과 같은 결과여야 한다:
        // 8/31에 로컬 2회를 썼는데 서버엔 1회만 올라갔다면(그 날 오프라인), 그 미보고분이
        // 오늘 다른 기기가 쓴 1회를 상쇄해 다른 기기 몫은 0이 된다.
        val week = listOf("2026-08-31", TODAY)
        val local = mapOf("2026-08-31" to 2)
        val remote = mapOf("2026-08-31" to 1, TODAY to 1)
        assertEquals(0, otherDevices(week, local, remote, syncedTodayUses = 0))
        assertEquals(2, combinedEmergencyUses(week, local, remote, TODAY, syncedTodayUses = 0))
    }

    @Test
    fun `today's still-unreported uses do not count as this device's server share`() {
        // 오늘 2회를 썼지만 아직 1회만 보고된 상태에서 서버 합계가 3회라면, 남은 1회는
        // 다른 기기 몫이다. 미보고분까지 내 몫으로 치면 그만큼 판정이 느슨해진다.
        val today = listOf(TODAY)
        val local = mapOf(TODAY to 2)
        val remote = mapOf(TODAY to 3)
        assertEquals(2, reportedEmergencyUsesInBucket(localBucketUses = 2, localTodayUses = 2, syncedTodayUses = 2))
        assertEquals(1, reportedEmergencyUsesInBucket(localBucketUses = 2, localTodayUses = 2, syncedTodayUses = 1))
        assertEquals(2, otherDevices(today, local, remote, syncedTodayUses = 1))
    }

    @Test
    fun `a monthly bucket spans the whole month so far`() {
        val month = (1..5).map { "2026-09-0$it" }
        val local = mapOf("2026-09-01" to 1)
        val remote = mapOf("2026-09-01" to 1, "2026-09-04" to 2)
        // 9월 4일 2회는 이 기기 기록에 없으니 전부 다른 기기 몫.
        assertEquals(3, combinedEmergencyUses(month, local, remote, todayKey = "2026-09-05", syncedTodayUses = 0))
        assertEquals(
            2,
            otherDeviceEmergencyUses(month, local, remote, todayKey = "2026-09-05", syncedTodayUses = 0),
        )
    }

    private fun otherDevices(
        bucketDateKeys: List<String>,
        localUsesByDate: Map<String, Int>,
        remoteUsesByDate: Map<String, Int>,
        syncedTodayUses: Int,
    ): Int = otherDeviceEmergencyUses(bucketDateKeys, localUsesByDate, remoteUsesByDate, TODAY, syncedTodayUses)
}

private const val TODAY = "2026-09-03"
