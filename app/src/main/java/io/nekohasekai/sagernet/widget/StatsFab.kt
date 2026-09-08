package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.progressindicator.CircularProgressIndicator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.getColour
import kotlin.math.max
import kotlin.math.min

/**
 * 启动/停止悬浮胶囊（FAB 重构）。
 *
 * 规范（需求文档三）：
 * - 颜色固定经典高亮蓝 #00A3FF，日/夜间不变；胶囊大圆角（高 56dp / 圆角 28dp）；
 * - 收起态 56dp 圆形，仅显示 ▶ 启动图标；
 * - 连接后背景向左平滑展开数据区（▼ 下行 / ▲ 上行 / 延迟 三行白色小字，垂直居中），
 *   右侧图标位置严格固定（▶ ↔ ⏸ 切换不位移）；
 * - 展开后最左端严禁侵入屏幕左半区，距垂直中线保留 16dp、距右边缘 16dp：
 *   Max_Width = Screen_Width * 0.5 - 32dp。
 */
class StatsFab @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    companion object {
        private const val FAB_HEIGHT_DP = 56f
        private const val ICON_BLOCK_DP = 56f
        private const val MARGIN_DP = 16f
        private const val ANIM_DURATION_MS = 320L
    }

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).toInt()

    private val collapsedWidth = dp(FAB_HEIGHT_DP)
    private val iconBlockWidth = dp(ICON_BLOCK_DP)

    /** 展开最大宽度 = 屏宽一半 - 16dp*2 */
    private val maxExpandedWidth: Int
        get() = (resources.displayMetrics.widthPixels * 0.5f - dp(MARGIN_DP) * 2).toInt()

    private val statsColumn: LinearLayout
    private val rxText: TextView
    private val txText: TextView
    private val latencyText: TextView
    private val iconView: ImageView
    private val progress: CircularProgressIndicator

    private var expanded = false
    private var widthAnimator: ValueAnimator? = null
    private var shown = true

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        elevation = 6 * density

        // 固定经典高亮蓝胶囊背景，圆角 = 高度/2
        val capsule = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = FAB_HEIGHT_DP / 2f * density
            setColor(context.getColour(R.color.fab_background))
        }
        background = capsule

        // 左侧数据区（三行）：展开时可见
        statsColumn = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14f), dp(6f), dp(2f), dp(6f))
            visibility = View.GONE
            layoutParams = LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
        }
        rxText = makeLine()
        txText = makeLine()
        latencyText = makeLine()
        statsColumn.addView(rxText)
        statsColumn.addView(txText)
        statsColumn.addView(latencyText)
        addView(statsColumn)

        // 右侧图标区（56dp 固定块）：图标物理位置严格固定，不随展开位移
        iconView = ImageView(context).apply {
            layoutParams = LayoutParams(iconBlockWidth, LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER
            setImageResource(R.drawable.ic_play_arrow_24)
            setColorFilter(Color.WHITE)
        }
        addView(iconView)

        // 连接中转圈指示
        progress = CircularProgressIndicator(context).apply {
            layoutParams = LayoutParams(iconBlockWidth, LayoutParams.MATCH_PARENT)
            isIndeterminate = true
            indicatorSize = dp(26f)
            setIndicatorColor(Color.WHITE)
            trackColor = Color.TRANSPARENT
            visibility = View.GONE
        }
        addView(progress)

        updateSpeed(0, 0)
        setLatency(null)
    }

    private fun makeLine(): TextView {
        return TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT
            )
            setTextColor(Color.WHITE)
            textSize = 11f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
    }

    /** 十进制单位速率格式化：xx.x B/KB/MB/GB */
    private fun formatSpeed(bytesPerSec: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        val v = bytesPerSec.toDouble()
        return when {
            v >= gb -> String.format("%.1f GB", v / gb)
            v >= mb -> String.format("%.1f MB", v / mb)
            v >= kb -> String.format("%.1f KB", v / kb)
            else -> String.format("%.0f B", v)
        }
    }

    fun updateSpeed(txRate: Long, rxRate: Long) {
        txText.text = "▲  ${context.getString(R.string.speed, formatSpeed(txRate))}"
        rxText.text = "▼  ${context.getString(R.string.speed, formatSpeed(rxRate))}"
    }

    fun setLatency(elapsed: Int?) {
        latencyText.text = if (elapsed != null && elapsed >= 0) {
            context.getString(R.string.fab_latency, elapsed)
        } else {
            context.getString(R.string.fab_latency_none)
        }
    }

    /** 服务状态切换：Idle/Stopped → ▶ 收起；Connecting → 转圈；Connected → ⏸ 展开 */
    fun changeState(state: BaseService.State) {
        when (state) {
            BaseService.State.Connecting -> {
                iconView.visibility = View.GONE
                progress.visibility = View.VISIBLE
                collapse()
            }

            BaseService.State.Connected -> {
                progress.visibility = View.GONE
                iconView.visibility = View.VISIBLE
                iconView.setImageResource(R.drawable.ic_pause_24)
                expand()
            }

            BaseService.State.Stopping -> {
                // 停止过程中保持 ⏸ 与展开态，Stopped 时收起
                progress.visibility = View.GONE
                iconView.visibility = View.VISIBLE
                iconView.setImageResource(R.drawable.ic_pause_24)
            }

            else -> {
                progress.visibility = View.GONE
                iconView.visibility = View.VISIBLE
                iconView.setImageResource(R.drawable.ic_play_arrow_24)
                collapse()
            }
        }
        contentDescription = context.getText(if (state.canStop) R.string.stop else R.string.connect)
    }

    /** 计算数据区自然宽度（三行文本最大宽度 + 内边距） */
    private fun naturalStatsWidth(): Int {
        var w = 0
        for (tv in listOf(rxText, txText, latencyText)) {
            tv.measure(
                View.MeasureSpec.makeMeasureSpec(maxExpandedWidth, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            w = max(w, tv.measuredWidth)
        }
        return w + statsColumn.paddingLeft + statsColumn.paddingRight
    }

    private fun animateWidthTo(target: Int) {
        widthAnimator?.cancel()
        val start = layoutParams.width
        if (start == target) return
        widthAnimator = ValueAnimator.ofInt(start, target).apply {
            duration = ANIM_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                layoutParams.width = anim.animatedValue as Int
                requestLayout()
            }
            start()
        }
    }

    private fun expand() {
        if (expanded) return
        expanded = true
        statsColumn.visibility = View.VISIBLE
        val natural = iconBlockWidth + naturalStatsWidth()
        // 严禁侵入左半屏：最大宽度 = 屏宽/2 - 32dp
        val target = min(natural, maxExpandedWidth).coerceAtLeast(collapsedWidth)
        animateWidthTo(target)
    }

    private fun collapse() {
        if (!expanded) return
        expanded = false
        animateWidthTo(collapsedWidth)
    }

    /** 与原 FAB 的 show/hide 语义对齐：非配置页隐藏悬浮胶囊 */
    fun show(animate: Boolean = true) {
        if (shown) return
        shown = true
        widthAnimator?.cancel()
        animate().cancel()
        if (animate) {
            alpha = 0f
            scaleX = 0.8f
            scaleY = 0.8f
            visibility = View.VISIBLE
            animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
        } else {
            visibility = View.VISIBLE
            alpha = 1f
            scaleX = 1f
            scaleY = 1f
        }
    }

    fun hide(animate: Boolean = true) {
        if (!shown) return
        shown = false
        widthAnimator?.cancel()
        animate().cancel()
        if (animate && isLaidOut) {
            animate().alpha(0f).scaleX(0.8f).scaleY(0.8f).setDuration(200)
                .withEndAction { visibility = View.INVISIBLE }.start()
        } else {
            visibility = View.INVISIBLE
        }
    }

    override fun onDetachedFromWindow() {
        widthAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
