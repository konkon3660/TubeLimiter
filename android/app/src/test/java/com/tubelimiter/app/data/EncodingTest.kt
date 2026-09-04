package com.tubelimiter.app.data

import com.tubelimiter.app.limit.ScheduleWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EncodingTest {

    @Test
    fun `a usage map survives a round trip`() {
        val history = mapOf("2026-09-01" to 1_000L, "2026-09-02" to 0L)
        assertEquals(history, decodeLongMap(encodeLongMap(history)))
    }

    @Test
    fun `blank and malformed usage entries decode to empty`() {
        assertTrue(decodeLongMap(null).isEmpty())
        assertTrue(decodeLongMap("").isEmpty())
        assertEquals(mapOf("2026-09-01" to 5L), decodeLongMap("2026-09-01=5;garbage;=7;x="))
    }

    @Test
    fun `per-day limits pad missing slots with the default`() {
        assertEquals(listOf(1, 2, 3, 9, 9, 9, 9), decodeIntList("1,2,3", size = 7, default = 9))
        assertEquals(List(7) { 30 }, decodeIntList(null, size = 7, default = 30))
    }

    @Test
    fun `a malformed slot falls back without shifting the rest`() {
        assertEquals(listOf(1, 9, 3), decodeIntList("1,x,3", size = 3, default = 9))
    }

    @Test
    fun `per-day limits survive a round trip including the unlimited sentinel`() {
        val values = listOf(15, -1, 30, 60, -1, 90, 120)
        assertEquals(values, decodeIntList(encodeIntList(values), size = 7, default = 0))
    }

    @Test
    fun `achievement sets survive a round trip`() {
        val keys = setOf("streak_3", "streak_7")
        assertEquals(keys, decodeStringSet(encodeStringSet(keys)))
        assertTrue(decodeStringSet(null).isEmpty())
    }

    @Test
    fun `milestone sets drop non-numeric entries`() {
        assertEquals(setOf(30, 10), decodeIntSet("30,10,oops"))
    }

    @Test
    fun `pruning keeps only the retained days`() {
        val history = mapOf("2026-09-01" to 1L, "2026-08-01" to 2L)
        assertEquals(mapOf("2026-09-01" to 1L), pruneHistory(history, setOf("2026-09-01")))
    }

    @Test
    fun `an hourly usage map survives a round trip`() {
        val history = mapOf(
            "2026-09-01" to mapOf(9 to 60_000L, 23 to 5_000L),
            "2026-09-02" to mapOf(0 to 1_000L),
        )
        assertEquals(history, decodeHourlyMap(encodeHourlyMap(history)))
    }

    @Test
    fun `blank and malformed hourly entries decode to empty`() {
        assertTrue(decodeHourlyMap(null).isEmpty())
        assertTrue(decodeHourlyMap("").isEmpty())
        assertEquals(
            mapOf("2026-09-01" to mapOf(9 to 5L)),
            decodeHourlyMap("2026-09-01=9:5,garbage,x:1;=1:1"),
        )
    }

    @Test
    fun `a day with no hour entries decodes to an empty inner map`() {
        assertEquals(mapOf("2026-09-01" to emptyMap<Int, Long>()), decodeHourlyMap("2026-09-01="))
    }

    @Test
    fun `schedule windows survive a round trip`() {
        val windows = listOf(
            ScheduleWindow("1", "밤 시간", List(7) { true }, 22 * 60, 7 * 60, enabled = true),
            ScheduleWindow("2", "점심", listOf(false, true, true, true, true, true, false), 12 * 60, 13 * 60, enabled = false),
        )
        assertEquals(windows, decodeScheduleWindows(encodeScheduleWindows(windows)))
    }

    @Test
    fun `empty or missing schedule windows decode to an empty list`() {
        assertTrue(decodeScheduleWindows(null).isEmpty())
        assertTrue(decodeScheduleWindows("").isEmpty())
        assertTrue(decodeScheduleWindows(encodeScheduleWindows(emptyList())).isEmpty())
    }

    @Test
    fun `hourly pruning keeps only the retained days`() {
        val history = mapOf(
            "2026-09-01" to mapOf(9 to 1L),
            "2026-08-01" to mapOf(10 to 2L),
        )
        assertEquals(
            mapOf("2026-09-01" to mapOf(9 to 1L)),
            pruneHourlyHistory(history, setOf("2026-09-01")),
        )
    }
}
