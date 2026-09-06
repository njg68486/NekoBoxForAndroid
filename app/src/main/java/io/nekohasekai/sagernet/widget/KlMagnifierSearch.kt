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
import androidx.appcompat.widget.Toolbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * kl 放大镜形变搜索框 v3 —— 按用户 round5 规格重做：
 *
 *   收起：就是一个白体放大镜图标（用户 SVG：circle(11,11,r7) + 45° 手柄，stroke 2 round），
 *         没有任何外框 —— 挂在工具栏菜单 action 位（右侧控件组最左，＋/⋮/胶囊 的旁边）。
 *   展开：图标整个消失，一个占满整条顶栏的搜索框：
 *         [分组│全局] [ 输入框................. ] [ ❌ ]
 *
 * 动画（展开）：图标 0.18s 淡出 → 宽度 0.58s cubic-bezier(.22,1,.36,1) 撑满 →
 *              圆角 19→12dp 0.44s → 材质 0.32s → 内容 0.24s 后淡入。
 * 收起错峰：宽 0.52s 延迟 0.08s；圆角 0.44s 延迟 0.12s；材质 0.28s；图标 0.28s 延迟 0.32s。
 */
class KlMagnifierSearch @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    companion object {
        private const val COLLAPSED_WIDTH_DP = 28
        private const val BAR_HEIGHT_DP = 38
        private const val RADIUS_BOX_DP = 12

        private val EASE = PathInterpolator(0.22f, 1f, 0.36f, 1f)

        /** 展开态底色/描边（原型 .searchbar：#27343D / #43515A） */
        private const val BAR_BG = 0xFF27343D.toInt()
        private const val BAR_STROKE = 0xFF43515A.toInt()
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
        // 收起态：完全透明（只有图标）；材质随展开动画淡入。
        // 上一版的 bug：初始 alpha 默认 255，收起时也画出一个白描边胶囊罩住图标。
        bgDrawable.setColor(BAR_BG)
        bgDrawable.cornerRadius = dp2px(BAR_HEIGHT_DP) / 2f
        bgDrawable.setStroke(dp2px(1), BAR_STROKE)
        bgDrawable.alpha = 0
        background = bgDrawable

        addView(
            magnifier,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )

        // [分组│全局] 切换钮：短文案、胶囊底（原型 .search-group #2F7FE5）
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
            setPadding(dp2px(8), 0, dp2px(6), 0)
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // 作为 menu action view 挂进来时 LayoutParams 宽可能是 WRAP_CONTENT —— 钉成收起宽
        if (layoutParams != null && layoutParams.width <= 0) {
            layoutParams.width = dp2px(COLLAPSED_WIDTH_DP)
        }
    }

    /**
     * 菜单 action view 会被 ActionMenuView 以 MATCH_PARENT 高度塞进工具栏 ——
     * 强制 38dp 高（胶囊 34dp、分段控件 30dp，搜索条略高一点压得住视觉）。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(dp2px(BAR_HEIGHT_DP), MeasureSpec.EXACTLY)
        )
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

        // ① 图标整体淡出（0.18s）—— 用户要求：点开后不再显示放大镜图标
        magnifier.animate().alpha(0f).setDuration(180).withEndAction {
            magnifier.visibility = View.GONE
        }.start()

        val target = targetWidth()
        animateWidth(dp2px(COLLAPSED_WIDTH_DP), target, 580, 0) // ② 撑满顶栏
        animateRadius(dp2px(BAR_HEIGHT_DP) / 2f, dp2px(RADIUS_BOX_DP).toFloat(), 440, 0)
        animateMaterial(0, 255, 320) // ③ 材质淡入

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
        animateMaterial(255, 0, 280)
        magnifier.postDelayed({
            magnifier.visibility = View.VISIBLE
            magnifier.alpha = 0f
            magnifier.animate().alpha(1f).setDuration(280).start()
        }, 320)

        postDelayed({ onChromeToggle?.invoke(false) }, 640)
    }

    // ==================== 动画件 ====================

    private fun currentWidth(): Int =
        layoutParams?.width?.takeIf { it > 0 } ?: dp2px(COLLAPSED_WIDTH_DP)

    /** 展开目标 = 宿主 Toolbar 内容宽（标题此时已隐藏，菜单只剩搜索自己） */
    private fun targetWidth(): Int {
        var p: View? = this
        while (p != null && p !is Toolbar) p = p.parent as? View
        val toolbar = p as? Toolbar
        val available = toolbar?.let { it.width - it.paddingLeft - it.paddingRight } ?: 0
        return (available - dp2px(24)).coerceAtLeast(dp2px(220))
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

    private fun animateMaterial(fromA: Int, toA: Int, duration: Long) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            this.duration = duration
            this.interpolator = EASE
            addUpdateListener {
                val t = it.animatedValue as Float
                bgDrawable.alpha = (fromA + (toA - fromA) * t).toInt()
                bgDrawable.invalidateSelf()
            }
            start()
        }
    }

    private fun imm() =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
}

/**
 * 放大镜图形 —— 用户给的 SVG 原样：
 *   viewBox 24，circle(11,11,r7)，path M16,16 → L21,21，stroke 2，round cap/join。
 * 24×24 图形在视图内居中绘制。
 */
class KlMagnifierIcon @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : android.view.View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val u = dp2px(1).toFloat() // 1 SVG unit = 1dp
        canvas.save()
        canvas.translate((width - 24f * u) / 2f, (height - 24f * u) / 2f)
        paint.strokeWidth = 2f * u

        canvas.drawCircle(11f * u, 11f * u, 7f * u, paint)

        // 手柄 M16,16 L21,21（45°，长 5 unit）
        canvas.drawLine(16f * u, 16f * u, 21f * u, 21f * u, paint)
        canvas.restore()
    }
}
