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
 * kl 放大镜形变搜索框 —— 按《搜索框形变说明.md》的时序实现：
 *
 * 展开（点击放大镜）：
 *   ① 手柄 0.18s 缩没（scaleX 1→0 + 淡出）
 *   ② 镜圈撑成框：宽度 0.58s（cubic-bezier(.22,1,.36,1)），圆角 99dp→10dp 0.44s
 *   ③ 材质渐变 0.32s：透明→深底 #2F3640，描边 2dp→1dp 半透明
 *   ④ 内容（分组徽章 + 输入框 + 关闭）在 ~0.24s 后淡入
 * 收起（点关闭）按错峰延迟逆向：
 *   宽度 0.52s 延迟 0.08s；圆角 0.44s 延迟 0.12s；材质褪色 0.28s；
 *   手柄 0.28s 延迟 0.32s 伸回。
 *
 * 作为 Toolbar 子视图挂在标题右侧（原型 NAV 顺序：标题、搜索、导入、更多、胶囊）。
 * 展开时通过 onChromeToggle 让宿主藏掉标题和全部菜单图标（原型搜索态替换整个顶栏）。
 */
class KlMagnifierSearch @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        /** md: 宽度撑开 min(288px, 84vw) */
        private const val MAX_WIDTH_DP = 288
        private const val COLLAPSED_WIDTH_DP = 28
        private const val BAR_HEIGHT_DP = 42
        private const val RADIUS_BOX_DP = 10

        /** md: cubic-bezier(0.22, 1, 0.36, 1) */
        private val EASE = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        private const val BAR_BG = 0xFF2F3640.toInt()
        private const val BAR_STROKE = 0x38FFFFFF
        private const val CHIP_BG = 0xFF2F7FE5.toInt()
    }

    /** 输入变化（空串 = 清空过滤） */
    var onQueryChange: ((String) -> Unit)? = null

    /** true = 展开（宿主藏标题/菜单），false = 已收回 */
    var onChromeToggle: ((Boolean) -> Unit)? = null

    /** 分组徽章被点（宿主弹 全局/分组 选择菜单，anchor = 徽章） */
    var onScopeClick: ((View) -> Unit)? = null

    private var opened = false
    private val bgDrawable = GradientDrawable()
    private val magnifier = KlMagnifierIcon(context)
    private val chip = TextView(context)
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

        chip.apply {
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = dp2px(7).toFloat()
                setColor(CHIP_BG)
            })
            setTextColor(Color.WHITE)
            textSize = 11f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(dp2px(8), 0, dp2px(8), 0)
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
            setPadding(dp2px(9), 0, dp2px(6), 0)
            addView(
                chip,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp2px(30)
                ).apply { marginEnd = dp2px(7) }
            )
            addView(
                magnifierGlyph(),
                LinearLayout.LayoutParams(dp2px(18), dp2px(18)).apply { marginEnd = dp2px(4) }
            )
            addView(
                input,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            addView(
                closeBtn,
                LinearLayout.LayoutParams(dp2px(30), dp2px(30))
            )
        }
        addView(contentRow, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        setOnClickListener { if (!opened) open() }
        chip.setOnClickListener { onScopeClick?.invoke(chip) }
        closeBtn.setOnClickListener { close() }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: android.text.Editable?) {
                onQueryChange?.invoke(s?.toString() ?: "")
            }
        })
    }

    private fun magnifierGlyph(): View =
        KlMagnifierIcon(context).apply { isClickable = false }

    /** 分组徽章文案（宿主设置：当前分组名 / 目标分组名 / 全局） */
    fun setScopeLabel(text: String) {
        chip.text = text
    }

    /** 当前输入框文本 */
    fun queryText(): String = input.text.toString()

    // ==================== 展开 ====================

    @SuppressLint("SetTextI18n")
    fun open() {
        if (opened) return
        opened = true
        onChromeToggle?.invoke(true)

        // ① 手柄极速隐缩 0.18s
        magnifier.animateHandle(0f, 180)

        // ② 镜圈横向扩张 0.58s（高度固定，宽度从收起宽撑到目标宽）
        val target = targetWidth()
        animateWidth(dp2px(COLLAPSED_WIDTH_DP), target, 580, 0)

        // ② 圆角重塑 99→10dp 0.44s
        animateRadius(dp2px(BAR_HEIGHT_DP) / 2f, dp2px(RADIUS_BOX_DP).toFloat(), 440, 0)

        // ③ 材质转换 0.32s：透明→深底、描边 2dp→1dp
        animateMaterial(0, 255, dp2px(2), dp2px(1), 320)

        // ④ 内容淡入 + 拉起键盘
        postDelayed({
            if (!opened) return@postDelayed
            contentRow.visibility = ViewGroup.VISIBLE
            contentRow.animate().alpha(1f).setDuration(200).start()
            input.requestFocus()
            imm().showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }, 240)
    }

    // ==================== 收起（错峰延迟） ====================

    fun close() {
        if (!opened) return
        opened = false
        imm().hideSoftInputFromWindow(windowToken, 0)

        // 清空过滤（触发 onQueryChange("")）
        if (input.text.isNotEmpty()) input.setText("") else onQueryChange?.invoke("")

        // 内容先淡出
        contentRow.animate().alpha(0f).setDuration(120).withEndAction {
            contentRow.visibility = ViewGroup.INVISIBLE
        }.start()

        // 宽度回缩 0.52s 延迟 0.08s
        animateWidth(currentWidth(), dp2px(COLLAPSED_WIDTH_DP), 520, 80)
        // 圆角复原 0.44s 延迟 0.12s
        animateRadius(dp2px(RADIUS_BOX_DP).toFloat(), dp2px(BAR_HEIGHT_DP) / 2f, 440, 120)
        // 材质褪色 0.28s（边框 0.18s 延迟后加粗回 2dp）
        animateMaterial(255, 0, dp2px(1), dp2px(2), 280)
        // ③ 手柄延迟伸出长成 0.28s 延迟 0.32s
        magnifier.postDelayed({ magnifier.animateHandle(1f, 280) }, 320)

        postDelayed({ onChromeToggle?.invoke(false) }, 660)
    }

    // ==================== 动画件 ====================

    private fun currentWidth(): Int =
        layoutParams?.width?.takeIf { it > 0 } ?: dp2px(COLLAPSED_WIDTH_DP)

    private fun targetWidth(): Int {
        val parentWidth = (parent as? View)?.width ?: 0
        val cap = dp2px(MAX_WIDTH_DP)
        return if (parentWidth > 0) (parentWidth - dp2px(24)).coerceAtMost(cap)
            .coerceAtLeast(dp2px(220))
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
 * 放大镜图形：镜圈 + 45° 手柄，纯 canvas 绘制（md 的静态形态构成）。
 * 手柄可动画（展开时 scaleX→0）。
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

        // 镜圈
        canvas.drawCircle(cx, cy, r, paint)

        // 手柄：从镜圈边缘沿 45° 伸出 7dp，随 handleScale 缩没
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
