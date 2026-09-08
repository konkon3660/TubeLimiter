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

    // --- 여러 앱을 동시에 볼 때 (documents/QA_REVIEW.md §1.8) ---

    @Test
    fun `union of disjoint app sessions adds up`() {
        val youtube = foregroundIntervals(
            listOf(
                UsageTransition(0, FOREGROUND),
                UsageTransition(10 * MINUTE, BACKGROUND),
            ),
            windowStart,
            windowEnd,
        )
        val kids = foregroundIntervals(
            listOf(
                UsageTransition(20 * MINUTE, FOREGROUND),
                UsageTransition(25 * MINUTE, BACKGROUND),
            ),
            windowStart,
            windowEnd,
        )
        assertEquals(15 * MINUTE, unionMillis(youtube + kids))
    }

    /** 앱을 갈아탈 때 두 앱의 RESUMED/PAUSED가 겹쳐 들어와도 벽시계 시간을 넘지 않아야 한다. */
    @Test
    fun `overlapping app sessions are not double counted`() {
        val first = listOf(ForegroundInterval(0, 20 * MINUTE))
        val second = listOf(ForegroundInterval(15 * MINUTE, 30 * MINUTE))
        assertEquals(30 * MINUTE, unionMillis(first + second))
    }

    @Test
    fun `a session fully inside another adds nothing`() {
        val intervals = listOf(
            ForegroundInterval(0, 30 * MINUTE),
            ForegroundInterval(5 * MINUTE, 10 * MINUTE),
        )
        assertEquals(30 * MINUTE, unionMillis(intervals))
    }

    @Test
    fun `touching sessions merge without a gap`() {
        val intervals = listOf(
            ForegroundInterval(0, 10 * MINUTE),
            ForegroundInterval(10 * MINUTE, 20 * MINUTE),
        )
        assertEquals(20 * MINUTE, unionMillis(intervals))
    }

    @Test
    fun `union ignores empty and inverted intervals`() {
        val intervals = listOf(
            ForegroundInterval(5 * MINUTE, 5 * MINUTE),
            ForegroundInterval(20 * MINUTE, 10 * MINUTE),
            ForegroundInterval(0, MINUTE),
        )
        assertEquals(MINUTE, unionMillis(intervals))
    }

    @Test
    fun `union of nothing is zero`() {
        assertEquals(0L, unionMillis(emptyList()))
    }

    @Test
    fun `intervals still sum to the old fold result`() {
        val transitions = listOf(
            UsageTransition(10 * MINUTE, FOREGROUND),
            UsageTransition(25 * MINUTE, BACKGROUND),
            UsageTransition(40 * MINUTE, FOREGROUND),
        )
        val intervals = foregroundIntervals(transitions, windowStart, windowEnd)
        assertEquals(
            foldForegroundMillis(transitions, windowStart, windowEnd),
            intervals.sumOf { it.endMillis - it.startMillis },
        )
        assertEquals(35 * MINUTE, unionMillis(intervals))
    }
}
