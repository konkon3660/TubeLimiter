package com.tubelimiter.app.block

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.tubelimiter.app.R
import com.tubelimiter.app.limit.BlockReason
import com.tubelimiter.app.limit.isUnlimited
import com.tubelimiter.app.limit.messageRes
import com.tubelimiter.app.usage.DurationParts
import com.tubelimiter.app.usage.durationParts
import com.tubelimiter.app.usage.format

private const val TAG = "BlockOverlay"

data class BlockContent(
    val reason: BlockReason,
    val usedMillis: Long,
    val limitMillis: Long,
    val emergencyRemaining: Int,
)

/** Which of the overlay's second lines applies. */
internal enum class BlockSubtitle { USED_ONLY, USED_OF_LIMIT, FOCUS_MODE, SCHEDULED, MANUAL }

/**
 * Everything the overlay actually draws, as identities rather than as text: string resources
 * cannot be resolved without a Context, and this has to stay comparable from a unit test.
 *
 * `usedMillis` climbs on every poll but only reaches the screen through a coarse duration
 * ([DurationParts] drops the seconds above a minute), so two contents a tick apart almost
 * always render identically - which is why the redraw guard compares this and not
 * [BlockContent].
 */
internal data class BlockRender(
    val reason: BlockReason,
    val subtitle: BlockSubtitle,
    val used: DurationParts?,
    val limit: DurationParts?,
    /** Remaining emergency passes, or null when no emergency button is offered at all. */
    val emergencyRemaining: Int?,
)

internal fun BlockContent.render(): BlockRender = when (reason) {
    BlockReason.FOCUS_MODE -> reasonOnly(BlockSubtitle.FOCUS_MODE)
    BlockReason.SCHEDULED -> reasonOnly(BlockSubtitle.SCHEDULED)
    BlockReason.MANUAL -> reasonOnly(BlockSubtitle.MANUAL)
    BlockReason.USAGE_LIMIT -> {
        val unlimited = isUnlimited(limitMillis)
        BlockRender(
            reason = reason,
            subtitle = if (unlimited) BlockSubtitle.USED_ONLY else BlockSubtitle.USED_OF_LIMIT,
            used = durationParts(usedMillis),
            limit = if (unlimited) null else durationParts(limitMillis),
            emergencyRemaining = emergencyRemaining.takeIf { it > 0 },
        )
    }
}

private fun BlockContent.reasonOnly(subtitle: BlockSubtitle) = BlockRender(
    reason = reason,
    subtitle = subtitle,
    used = null,
    limit = null,
    emergencyRemaining = null,
)

private fun BlockRender.subtitleText(context: Context): String = when (subtitle) {
    BlockSubtitle.USED_ONLY ->
        context.getString(R.string.block_overlay_used_only, used?.format(context).orEmpty())

    BlockSubtitle.USED_OF_LIMIT -> context.getString(
        R.string.block_overlay_used_of_limit,
        used?.format(context).orEmpty(),
        limit?.format(context).orEmpty(),
    )

    BlockSubtitle.FOCUS_MODE -> context.getString(R.string.block_overlay_focus_mode)
    BlockSubtitle.SCHEDULED -> context.getString(R.string.block_overlay_scheduled)
    BlockSubtitle.MANUAL -> context.getString(R.string.block_overlay_manual)
}

private fun BlockRender.emergencyLabel(context: Context): String? = emergencyRemaining?.let { left ->
    context.resources.getQuantityString(R.plurals.block_overlay_emergency, left, left)
}

/** Full-screen blocker drawn over the YouTube app. */
class BlockOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var shownRender: BlockRender? = null

    fun show(content: BlockContent, onEmergency: () -> Unit) {
        // Redraw only when the message actually changes, otherwise the overlay would
        // flicker on every poll.
        val render = content.render()
        if (view != null && shownRender == render) return
        if (!Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Overlay permission revoked; cannot block")
            return
        }
        hide()

        val root = buildView(render, onEmergency)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // No NOT_TOUCHABLE / NOT_FOCUSABLE: the point is to swallow input meant
            // for the app underneath.
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE,
        )

        runCatching { windowManager.addView(root, params) }
            .onSuccess {
                view = root
                shownRender = render
            }
            .onFailure { Log.e(TAG, "Failed to add overlay", it) }
    }

    fun hide() {
        val current = view ?: return
        runCatching { windowManager.removeView(current) }
            .onFailure { Log.e(TAG, "Failed to remove overlay", it) }
        view = null
        shownRender = null
    }

    private fun buildView(render: BlockRender, onEmergency: () -> Unit): View {
        val pad = dp(32)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#0F172A"))
            setPadding(pad, pad, pad, pad)

            addView(
                text(
                    context.getString(render.reason.messageRes()),
                    sizeSp = 26f,
                    color = Color.WHITE,
                    bold = true,
                ),
            )
            addView(
                text(
                    render.subtitleText(context),
                    sizeSp = 16f,
                    color = Color.parseColor("#94A3B8"),
                    topMarginDp = 12,
                ),
            )

            render.emergencyLabel(context)?.let { label ->
                addView(button(label, topMarginDp = 28) { onEmergency() })
            }
            addView(button(context.getString(R.string.block_overlay_go_home), topMarginDp = 12) { goHome() })
        }
    }

    private fun text(
        value: String,
        sizeSp: Float,
        color: Int,
        bold: Boolean = false,
        topMarginDp: Int = 0,
    ) = TextView(context).apply {
        text = value
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        gravity = Gravity.CENTER
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(topMarginDp) }
    }

    private fun button(label: String, topMarginDp: Int, onClick: () -> Unit) =
        Button(context).apply {
            text = label
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(topMarginDp)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

    private fun goHome() {
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        runCatching { context.startActivity(home) }
            .onFailure { Log.e(TAG, "Failed to go home", it) }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
