package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.view.animation.PathInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import kotlin.math.sqrt

/**
 * kl 放大镜形变搜索框 v2 —— 展开/收起时序照《搜索框形变说明.md》，内容布局按用户规格：
 *
 *   展开后： [分组│全局] [ 输入框................. ] [ ❌ ]
 *            └ 左端切换钮：点击在「分组搜索」和「全局搜索」之间互相切换（无弹窗）
 *            └ 右端 ❌：收起
 *
 * 动画（展开）：手柄 0.18s 缩没 → 宽度 0.58s cubic-bezier(.22,1,.36,1) →
 *              圆角 99→10dp 0.44s → 材质 0.32s → 内容 0.24s 后淡入。
 * 收起错峰：宽 0.52s 延迟 0.08s；圆角 0.44s 延迟 0.12s；材质 0.28s；手柄 0.28s 延迟 0.32s。
 */
class KlMagnifierSearch @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        private const val MAX_WIDTH_DP = 260
        private const val COLLAPSED_WIDTH_DP = 26
        private const val BAR_HEIGHT_DP = 38
        private const val RADIUS_BOX_DP = 10

        private val EASE = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        private const val BAR_BG = 0xFF2F3640.toInt()
        private const val BAR_STROKE = 0x38FFFFFF
        private const val SCOPE_BG = 0xFF2F7FE5.toInt()
    }

    /** 输入变化（空串 = 清空过滤） */
    var onQueryChange: ((String) -> Unit)? = null

    /** true = 展开（宿主藏标题/菜单），false = 已收回 */
    var onChromeToggle: ((Boolean) -> Unit)? = null

    /** 分组↔全局 切换（true = 切到全局；回调里再调 setScopeGlobal 同步文案） */
    var onScopeToggle: (() -> Unit)? = null

    private var opened = false
    private var scopeAll = false

    private val bgDrawable = GradientDrawable()
    private val magnifier = KlMagnifierIcon(context)
    private val scopeButton = TextView(context)
    private val input = EditText(context)
    private val contentRow = LinearLayout(context)

    init {
        bgDrawable.setColor(Color.TRANSPARENT)
        bgDrawable.cornerRadius = dp2px(BAR_HEIGHT_DP) / 2f
        bgDrawable.setStroke(dp2px(2), Color.WHITE)
        background = bgDrawable

        addView(
            magnifier,
            LayoutParams(dp2px(24), LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )

        // [分组│全局] 切换钮：短文案、胶囊底
        scopeButton.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp2px(7).toFloat()
                setColor(SCOPE_BG)
            })
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            gravity = Gravity.CENTER
            setPadding(dp2px(10), 0, dp2px(10), 0)
            text = context.getText(R.string.kl_search_scope_group_short)
        }
        input.apply {
            hint = context.getText(R.string.kl_search_hint)
            setHintTextColor(0x99FFFFFF.toInt())
            setTextColor(Color.WHITE)
            textSize = 14f
            background = null
            maxLines = 1
            setSingleLine(true)
            setPadding(0, 0, 0, 0)
        }
        val closeBtn = ImageView(context).apply {
            setImageResource(R.drawable.ic_navigation_close)
            setColorFilter(Color.WHITE)
        }

        contentRow.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = ViewGroup.INVISIBLE
            alpha = 0f
            setPadding(dp2px(6), 0, dp2px(4), 0)
            addView(
                scopeButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp2px(26)
                ).apply { marginEnd = dp2px(8) }
            )
            addView(
                input,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            addView(
                closeBtn,
                LinearLayout.LayoutParams(dp2px(28), dp2px(28))
            )
        }
        addView(contentRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setOnClickListener { if (!opened) open() }
        scopeButton.setOnClickListener { onScopeToggle?.invoke() }
        closeBtn.setOnClickListener { close() }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                onQueryChange?.invoke(s?.toString() ?: "")
            }
        })
    }

    /** 当前输入框文本 */
    fun queryText(): String = input.text.toString()

    /** 同步切换钮文案：全局时亮橙色，分组时蓝色 */
    fun setScopeGlobal(all: Boolean) {
        scopeAll = all
        scopeButton.text = context.getText(
            if (all) R.string.kl_search_scope_all_short
            else R.string.kl_search_scope_group_short
        )
        (scopeButton.background as? GradientDrawable)?.setColor(
            if (all) 0xFFE08E45.toInt() else SCOPE_BG
        )
    }

    // ==================== 展开 ====================

    fun open() {
        if (opened) return
        opened = true
        onChromeToggle?.invoke(true)

        magnifier.animateHandle(0f, 180) // ① 手柄缩没

        val target = targetWidth()
        animateWidth(dp2px(COLLAPSED_WIDTH_DP), target, 580, 0) // ② 撑宽
        animateRadius(dp2px(BAR_HEIGHT_DP) / 2f, dp2px(RADIUS_BOX_DP).toFloat(), 440, 0)
        animateMaterial(0, 255, dp2px(2), dp2px(1), 320) // ③ 材质

        postDelayed({
            if (!opened) return@postDelayed
            contentRow.visibility = ViewGroup.VISIBLE
            contentRow.animate().alpha(1f).setDuration(200).start()
            input.requestFocus()
            imm().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 240)
    }

    // ==================== 收起（错峰） ====================

    fun close() {
        if (!opened) return
        opened = false
        imm().hideSoftInputFromWindow(windowToken, 0)

        if (input.text.isNotEmpty()) input.setText("") else onQueryChange?.invoke("")

        contentRow.animate().alpha(0f).setDuration(120).withEndAction {
            contentRow.visibility = ViewGroup.INVISIBLE
        }.start()

        animateWidth(currentWidth(), dp2px(COLLAPSED_WIDTH_DP), 520, 80)
        animateRadius(dp2px(RADIUS_BOX_DP).toFloat(), dp2px(BAR_HEIGHT_DP) / 2f, 440, 120)
        animateMaterial(255, 0, dp2px(1), dp2px(2), 280)
        magnifier.postDelayed({ magnifier.animateHandle(1f, 280) }, 320)

        postDelayed({ onChromeToggle?.invoke(false) }, 640)
    }

    // ==================== 动画件 ====================

    private fun currentWidth(): Int =
        layoutParams?.width?.takeIf { it > 0 } ?: dp2px(COLLAPSED_WIDTH_DP)

    private fun targetWidth(): Int {
        val parentWidth = (parent as? View)?.width ?: 0
        val cap = dp2px(MAX_WIDTH_DP)
        return if (parentWidth > 0) (parentWidth - dp2px(16)).coerceAtMost(cap)
            .coerceAtLeast(dp2px(200))
        else cap
    }

    private fun animateWidth(from: Int, to: Int, duration: Long, delay: Long) {
        ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            this.interpolator = EASE
            this.startDelay = delay
            addUpdateListener {
                layoutParams.width = it.animatedValue as Int
                requestLayout()
            }
            start()
        }
    }

    private fun animateRadius(from: Float, to: Float, duration: Long, delay: Long) {
        ValueAnimator.ofFloat(from, to).apply {
            this.duration = duration
            this.interpolator = EASE
            this.startDelay = delay
            addUpdateListener {
                bgDrawable.cornerRadius = it.animatedValue as Float
                bgDrawable.invalidateSelf()
            }
            start()
        }
    }

    private fun animateMaterial(fromA: Int, toA: Int, fromStroke: Int, toStroke: Int, duration: Long) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.interpolator = EASE
            addUpdateListener {
                val t = it.animatedValue as Float
                bgDrawable.alpha = (fromA + (toA - fromA) * t).toInt()
                val stroke = fromStroke + (toStroke - fromStroke) * t
                bgDrawable.setStroke(stroke.toInt(), if (toA > 0) BAR_STROKE else Color.WHITE)
                bgDrawable.invalidateSelf()
            }
            start()
        }
    }

    private fun imm() =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}

/**
 * 放大镜图形：镜圈 + 45° 手柄（canvas 绘制，手柄可动画缩没）。
 */
class KlMagnifierIcon @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : android.view.View(context, attrs) {

    private var handleScale = 1f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    fun animateHandle(to: Float, duration: Long) {
        ValueAnimator.ofFloat(handleScale, to).apply {
            this.duration = duration
            addUpdateListener {
                handleScale = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stroke = dp2px(2).toFloat()
        paint.strokeWidth = stroke
        val r = (width.coerceAtMost(height)) / 2f - stroke
        val cx = width / 2f - dp2px(2)
        val cy = height / 2f - dp2px(2)

        canvas.drawCircle(cx, cy, r, paint)

        if (handleScale > 0.01f) {
            val k = r / sqrt(2f)
            canvas.save()
            canvas.translate(cx + k, cy + k)
            canvas.rotate(45f)
            canvas.scale(handleScale, 1f)
            canvas.drawLine(0f, 0f, dp2px(7).toFloat(), 0f, paint)
            canvas.restore()
        }
    }
}
