package com.tubelimiter.app.block

import com.tubelimiter.app.limit.BlockReason
import com.tubelimiter.app.limit.UNLIMITED_MILLIS
import com.tubelimiter.app.usage.DurationParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The overlay is removed from and re-added to the WindowManager whenever the render changes,
 * so anything that compares unequal between two polls is a visible flicker.
 */
class BlockRenderTest {

    private fun usageLimit(usedMillis: Long, emergencyRemaining: Int = 2) = BlockContent(
        reason = BlockReason.USAGE_LIMIT,
        usedMillis = usedMillis,
        limitMillis = 45 * 60_000L,
        emergencyRemaining = emergencyRemaining,
    )

    @Test
    fun `a poll tick that only advances usedMillis renders identically`() {
        // 45m and 45m05s both display as a flat 45 minutes; comparing the raw BlockContent
        // would redraw here.
        assertEquals(usageLimit(2_700_000L).render(), usageLimit(2_705_000L).render())
    }

    @Test
    fun `usedMillis still redraws once the displayed minute changes`() {
        assertNotEquals(usageLimit(2_700_000L).render(), usageLimit(2_760_000L).render())
    }

    @Test
    fun `a different reason redraws`() {
        val focus = BlockContent(BlockReason.FOCUS_MODE, 2_700_000L, 45 * 60_000L, 2)
        assertNotEquals(usageLimit(2_700_000L).render(), focus.render())
    }

    @Test
    fun `spending an emergency pass redraws the button label`() {
        assertNotEquals(
            usageLimit(2_700_000L, emergencyRemaining = 2).render(),
            usageLimit(2_700_000L, emergencyRemaining = 1).render(),
        )
    }

    @Test
    fun `the emergency button is offered only on a usage-limit block with passes left`() {
        assertEquals(2, usageLimit(2_700_000L).render().emergencyRemaining)
        assertNull(usageLimit(2_700_000L, emergencyRemaining = 0).render().emergencyRemaining)
        assertNull(BlockContent(BlockReason.SCHEDULED, 0L, 45 * 60_000L, 3).render().emergencyRemaining)
    }

    @Test
    fun `an unlimited day drops the limit half of the subtitle`() {
        val render = BlockContent(BlockReason.USAGE_LIMIT, 2_700_000L, UNLIMITED_MILLIS, 0).render()
        assertEquals(BlockSubtitle.USED_ONLY, render.subtitle)
        assertEquals(DurationParts(hours = 0, minutes = 45, seconds = 0), render.used)
        assertNull(render.limit)
    }

    @Test
    fun `each reason keeps its own subtitle`() {
        assertEquals(
            BlockSubtitle.USED_OF_LIMIT,
            usageLimit(2_700_000L).render().subtitle,
        )
        assertEquals(
            BlockSubtitle.FOCUS_MODE,
            BlockContent(BlockReason.FOCUS_MODE, 0L, 45 * 60_000L, 0).render().subtitle,
        )
        assertEquals(
            BlockSubtitle.MANUAL,
            BlockContent(BlockReason.MANUAL, 0L, 45 * 60_000L, 0).render().subtitle,
        )
    }
}
