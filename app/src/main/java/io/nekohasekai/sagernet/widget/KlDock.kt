package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.text.format.Formatter
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.TooltipCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * kl dock —— 取代上游 ServiceButton(FAB) + StatsBar(BottomAppBar) 两个控件。
 *
 * 完全照 HTML 原型 `.dock` 的结构与状态机实现：
 *   收起态：58×58 圆角方形（radius 17dp），只有 play 按钮。
 *   展开态：174×58，左侧 116dp 是 dock-info（▼下行 / ▲上行 / 延迟 三行），
 *           右侧仍是 58dp 的 power 按钮。两者之间不放任何分割线（用户要求），
 *           靠同一块主题色底板连体，展开 = 向右拓展。
 *   过渡：宽度动画 380ms cubic-bezier(.32,.72,0,1)，与原型 `transition:width .38s` 一致。
 *   配色：背景 = ?colorPrimary（与上游 FAB/StatsBar 的 backgroundTint 同源，跟随主题），
 *         前景 = 白色（与上游 ic_service_* 矢量及 StatsBar 文字一致）。
 *
 * 原型里 dock 展开与否只跟「是否运行」绑定（`state.dockExpanded = state.running`），
 * 这里同样由 changeState 驱动，不额外暴露展开开关。
 */
class KlDock @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    companion object {
        /** 原型 .dock height/width:58px、.dock.expanded width:174px、.dock-info 116px */
        private const val SIZE_DP = 58
        private const val INFO_DP = 116

        /** 原型 transition:width .38s cubic-bezier(.32,.72,0,1) */
        private const val DURATION_MS = 380L
    }

    private val sizePx = dp2px(SIZE_DP)
    private val infoPx = dp2px(INFO_DP)

    private val infoView: LinearLayout
    private val powerView: ImageView
    private val rxText: TextView
    private val txText: TextView
    private val latencyText: TextView

    private var widthAnimator: ValueAnimator? = null
    private var expanded = false
    private var currentState = BaseService.State.Idle

    /** dock-info 整块的点击回调：原型 data-action="dock-test"（跑当前分组延迟测试） */
    var onTestClick: (() -> Unit)? = null

    /** power 按钮点击：原型 data-action="dock-power" */
    var onPowerClick: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        clipChildren = true
        clipToPadding = true
        background = context.getDrawable(R.drawable.kl_dock_bg)
        // 上游 ServiceButton(FAB) / StatsBar 都是 app:backgroundTint="?colorPrimary"，
        // dock 沿用同一套主题色：底板 drawable 是纯白，这里用 backgroundTintList 染色，
        // 换主题（粉/蓝/…）时按钮自动跟着变，不再钉死原型取色板的 #0BA8E5。
        backgroundTintList = ColorStateList.valueOf(context.getColorAttr(R.attr.colorPrimary))
        elevation = dp2px(8).toFloat()

        LayoutInflater.from(context).inflate(R.layout.layout_kl_dock, this, true)
        infoView = findViewById(R.id.dock_info)
        powerView = findViewById(R.id.dock_power)
        rxText = findViewById(R.id.dock_rx)
        txText = findViewById(R.id.dock_tx)
        latencyText = findViewById(R.id.dock_latency)

        infoView.setOnClickListener { onTestClick?.invoke() }
        powerView.setOnClickListener { onPowerClick?.invoke() }

        // 收起态初始值：info 宽 0、分割线不可见（原型 .dock-power{border-left:0}）
        applyInfoWidth(0)
        updateSpeed(0, 0)
        setLatencyIdle()
    }

    /**
     * 收起时 info 宽 0 —— 注意不能用 visibility=GONE，否则宽度动画没有中间态可插值。
     * 原型踩过同一个坑（注释「dock 收起态修复」）：靠 .dock 自己 58px + overflow:hidden
     * 会把 power 按钮挤出可视区，剩一块空蓝方块。所以让 info 自己的宽度参与过渡。
     */
    private fun applyInfoWidth(px: Int) {
        infoView.layoutParams = (infoView.layoutParams as LayoutParams).apply { width = px }
        infoView.alpha = if (infoPx == 0) 0f else (px.toFloat() / infoPx).coerceIn(0f, 1f)
        requestLayout()
    }

    private fun setExpanded(target: Boolean, animate: Boolean) {
        if (expanded == target && widthAnimator == null) return
        expanded = target
        val from = (infoView.layoutParams as LayoutParams).width.coerceAtLeast(0)
        val to = if (target) infoPx else 0
        widthAnimator?.cancel()
        widthAnimator = null
        if (!animate || !isLaidOut) {
            applyInfoWidth(to)
            return
        }
        if (from == to) return
        widthAnimator = ValueAnimator.ofInt(from, to).apply {
            duration = DURATION_MS
            interpolator = PathInterpolator(0.32f, 0.72f, 0f, 1f)
            addUpdateListener { applyInfoWidth(it.animatedValue as Int) }
            start()
        }
    }

    /**
     * 服务状态 → 图标 + 展开态。
     * 原型只有 play/pause 两个图标，没有上游那套 connecting/stopping AnimatedVectorDrawable
     * 过场动画，这里按原型语义走：能停就显示 pause，其余显示 play。
     */
    fun changeState(state: BaseService.State, animate: Boolean) {
        currentState = state
        val running = state.canStop
        powerView.setImageResource(if (running) R.drawable.ic_kl_pause else R.drawable.ic_kl_play)
        val description = context.getText(if (running) R.string.stop else R.string.connect)
        powerView.contentDescription = description
        TooltipCompat.setTooltipText(powerView, description)
        // Connecting/Stopping 期间禁点，避免重复下发
        powerView.isEnabled = state.canStop || state == BaseService.State.Stopped
        setExpanded(state == BaseService.State.Connected, animate)
        if (state != BaseService.State.Connected) {
            updateSpeed(0, 0)
            setLatencyIdle()
        }
    }

    @SuppressLint("SetTextI18n")
    fun updateSpeed(txRate: Long, rxRate: Long) {
        rxText.text = "▼ ${Formatter.formatFileSize(context, rxRate)}/s"
        txText.text = "▲ ${Formatter.formatFileSize(context, txRate)}/s"
    }

    /** 第三行：原型 `${mode}: ${latency}`，测试中显示「测试中」 */
    @SuppressLint("SetTextI18n")
    fun setLatency(mode: String, text: CharSequence) {
        latencyText.text = "$mode: $text"
    }

    private fun setLatencyIdle() {
        latencyText.text = context.getText(R.string.not_connected)
    }
}
