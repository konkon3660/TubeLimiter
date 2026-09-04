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
import com.tubelimiter.app.limit.BlockReason
import com.tubelimiter.app.limit.isUnlimited
import com.tubelimiter.app.limit.message
import com.tubelimiter.app.usage.formatDuration

private const val TAG = "BlockOverlay"

data class BlockContent(
    val reason: BlockReason,
    val usedMillis: Long,
    val limitMillis: Long,
    val emergencyRemaining: Int,
)

/**
 * Everything the overlay actually draws. `usedMillis` climbs on every poll but only reaches
 * the screen through a coarse duration string, so two contents a tick apart almost always
 * render identically - which is why the redraw guard compares this and not [BlockContent].
 */
internal data class BlockRender(
    val title: String,
    val subtitle: String,
    val emergencyLabel: String?,
)

internal fun BlockContent.render(): BlockRender = BlockRender(
    title = reason.message(),
    subtitle = subtitle(),
    emergencyLabel = if (reason == BlockReason.USAGE_LIMIT && emergencyRemaining > 0) {
        "긴급 시청 5분 (${emergencyRemaining}회 남음)"
    } else {
        null
    },
)

private fun BlockContent.subtitle(): String = when (reason) {
    BlockReason.USAGE_LIMIT ->
        if (isUnlimited(limitMillis)) {
            "사용 ${formatDuration(usedMillis)}"
        } else {
            "사용 ${formatDuration(usedMillis)} / 한도 ${formatDuration(limitMillis)}"
        }

    BlockReason.FOCUS_MODE -> "집중 모드가 끝나면 다시 열립니다."
    BlockReason.SCHEDULED -> "예약된 시간이 끝나면 다시 열립니다."
    BlockReason.MANUAL -> "앱에서 차단을 풀 수 있어요."
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

            addView(text(render.title, sizeSp = 26f, color = Color.WHITE, bold = true))
            addView(
                text(
                    render.subtitle,
                    sizeSp = 16f,
                    color = Color.parseColor("#94A3B8"),
                    topMarginDp = 12,
                ),
            )

            render.emergencyLabel?.let { label ->
                addView(button(label, topMarginDp = 28) { onEmergency() })
            }
            addView(button("홈으로", topMarginDp = 12) { goHome() })
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
