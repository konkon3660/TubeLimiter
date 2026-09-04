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
}
