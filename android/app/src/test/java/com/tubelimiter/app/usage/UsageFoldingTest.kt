package com.tubelimiter.app.usage

import com.tubelimiter.app.usage.UsageTransition.Type.BACKGROUND
import com.tubelimiter.app.usage.UsageTransition.Type.FOREGROUND
import org.junit.Assert.assertEquals
import org.junit.Test

private const val MINUTE = 60_000L

class UsageFoldingTest {

    private val windowStart = 0L
    private val windowEnd = 60 * MINUTE

    private fun fold(vararg transitions: UsageTransition) =
        foldForegroundMillis(transitions.toList(), windowStart, windowEnd)

    @Test
    fun `no transitions means no usage`() {
        assertEquals(0L, fold())
    }

    @Test
    fun `closed segment counts its span`() {
        val actual = fold(
            UsageTransition(10 * MINUTE, FOREGROUND),
            UsageTransition(25 * MINUTE, BACKGROUND),
        )
        assertEquals(15 * MINUTE, actual)
    }

    @Test
    fun `segment still open at window end counts up to the end`() {
        assertEquals(50 * MINUTE, fold(UsageTransition(10 * MINUTE, FOREGROUND)))
    }

    @Test
    fun `already foreground when window opened counts from window start`() {
        val actual = foldForegroundMillis(
            listOf(UsageTransition(10 * MINUTE, BACKGROUND)),
            windowStart,
            windowEnd,
            startsInForeground = true,
        )
        assertEquals(10 * MINUTE, actual)
    }

    @Test
    fun `session spanning the whole window counts fully`() {
        val actual = foldForegroundMillis(emptyList(), windowStart, windowEnd, startsInForeground = true)
        assertEquals(60 * MINUTE, actual)
    }

    @Test
    fun `a stray background with no open session is ignored`() {
        val actual = fold(
            UsageTransition(5 * MINUTE, FOREGROUND),
            UsageTransition(15 * MINUTE, BACKGROUND),
            UsageTransition(20 * MINUTE, BACKGROUND),
        )
        assertEquals(10 * MINUTE, actual)
    }

    @Test
    fun `reopening after the window opened in foreground sums both segments`() {
        val actual = foldForegroundMillis(
            listOf(
                UsageTransition(10 * MINUTE, BACKGROUND),
                UsageTransition(40 * MINUTE, FOREGROUND),
                UsageTransition(50 * MINUTE, BACKGROUND),
            ),
            windowStart,
            windowEnd,
            startsInForeground = true,
        )
        assertEquals(20 * MINUTE, actual)
    }

    @Test
    fun `repeated background events do not double count`() {
        val actual = fold(
            UsageTransition(5 * MINUTE, FOREGROUND),
            UsageTransition(15 * MINUTE, BACKGROUND),
            UsageTransition(15 * MINUTE, BACKGROUND),
            UsageTransition(16 * MINUTE, BACKGROUND),
        )
        assertEquals(10 * MINUTE, actual)
    }

    @Test
    fun `repeated foreground events keep the earliest entry`() {
        val actual = fold(
            UsageTransition(5 * MINUTE, FOREGROUND),
            UsageTransition(8 * MINUTE, FOREGROUND),
            UsageTransition(15 * MINUTE, BACKGROUND),
        )
        assertEquals(10 * MINUTE, actual)
    }

    @Test
    fun `multiple sessions sum`() {
        val actual = fold(
            UsageTransition(5 * MINUTE, FOREGROUND),
            UsageTransition(15 * MINUTE, BACKGROUND),
            UsageTransition(30 * MINUTE, FOREGROUND),
            UsageTransition(35 * MINUTE, BACKGROUND),
        )
        assertEquals(15 * MINUTE, actual)
    }

    @Test
    fun `unsorted input is normalised`() {
        val actual = fold(
            UsageTransition(25 * MINUTE, BACKGROUND),
            UsageTransition(10 * MINUTE, FOREGROUND),
        )
        assertEquals(15 * MINUTE, actual)
    }

    @Test
    fun `transitions outside the window are ignored`() {
        val actual = foldForegroundMillis(
            listOf(
                UsageTransition(-5 * MINUTE, FOREGROUND),
                UsageTransition(90 * MINUTE, BACKGROUND),
            ),
            windowStart,
            windowEnd,
        )
        assertEquals(0L, actual)
    }

    @Test
    fun `empty window yields zero`() {
        assertEquals(0L, foldForegroundMillis(listOf(UsageTransition(0, FOREGROUND)), 100, 100))
    }
}
