package io.nekohasekai.sagernet.ui

import android.animation.ValueAnimator
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R

/**
 * 搜索框内的 [删除] 与 [分组/全局] 按钮控制器。
 *
 * 动画结构：
 *   外层使用水平 LinearLayout 作为容器，内含：
 *   - deleteView: [删除] 按钮，elevation 设置低于 scopeView（确保在视觉底层）
 *   - scopeView: [分组/全局] 按钮，elevation 高于 deleteView，充当遮挡物
 *
 * 宽度与平移动画：
 *   通过动态改变 deleteView 的 layoutParams.width 和 translationX：
 *   - 滑出时：宽度从 0 展开到目标宽度，同时 translationX 从 scopeView 宽度位移回 0，
 *     视觉上完美呈现从 [分组/全局] 按钮底部/背后向左滑动出来的效果！
 *   - 缩回时：宽度从目标宽度收缩至 0，translationX 滑回背后并设 GONE。
 */
class SearchScopeController {

    enum class Scope { GROUP, GLOBAL }

    private var wrapper: LinearLayout? = null
    private var scopeView: TextView? = null
    private var deleteView: TextView? = null
    private var scope: Scope = Scope.GROUP

    private var isDeleteVisible = false
    private var currentAnimator: ValueAnimator? = null
    private var targetDeleteWidth = 0

    /** 当前的搜索范围 */
    val current: Scope get() = scope

    fun attach(
        searchView: SearchView,
        onScopeChanged: (Scope) -> Unit,
        onDeleteClicked: () -> Unit
    ) {
        detach()

        val plate = searchView.findViewById<View>(
            androidx.appcompat.R.id.search_plate
        ) as? LinearLayout ?: return
        val clear = searchView.findViewById<View>(
            androidx.appcompat.R.id.search_close_btn
        ) ?: return

        val ctx = searchView.context

        // 统一药丸样式的创建函数
        fun createPillButton(textRes: Int, onClick: () -> Unit) = TextView(ctx).apply {
            setText(textRes)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
            setTextColor(themeColor(ctx))
            background = pill(ctx)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        // [删除] 按钮（初始宽度为0，不可见）
        val delBtn = createPillButton(R.string.delete) {
            onDeleteClicked()
        }.apply {
            visibility = View.GONE
            ViewCompat.setElevation(this, 1f)
            layoutParams = LinearLayout.LayoutParams(
                0,
                dp(ctx, 28)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = 0
            }
        }

        // [分组/全局] 按钮（层级更高，位于表面）
        val scpBtn = createPillButton(
            if (scope == Scope.GROUP) R.string.search_scope_group
            else R.string.search_scope_global
        ) {
            scope = if (scope == Scope.GROUP) Scope.GLOBAL else Scope.GROUP
            scopeView?.setText(
                if (scope == Scope.GROUP) R.string.search_scope_group
                else R.string.search_scope_global
            )
            onScopeChanged(scope)
        }.apply {
            ViewCompat.setElevation(this, 2f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(ctx, 28)
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginEnd = dp(ctx, 16)
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = true // 裁剪保证缩回时不溢出
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }

            addView(delBtn)
            addView(scpBtn)
        }

        val clearIndex = plate.indexOfChild(clear)
        if (clearIndex >= 0) {
            plate.addView(container, clearIndex)
        } else {
            plate.addView(container)
        }

        wrapper = container
        scopeView = scpBtn
        deleteView = delBtn
        isDeleteVisible = false

        // 预先测出删除按钮的真实展开宽度
        delBtn.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        targetDeleteWidth = delBtn.measuredWidth
    }

    /**
     * 更新删除按钮显示状态：
     * 从 [分组/全局] 按钮背后向左滑出展开，或缩回隐藏
     */
    fun updateDeleteVisibility(show: Boolean) {
        val del = deleteView ?: return
        val scp = scopeView ?: return
        if (isDeleteVisible == show) return
        isDeleteVisible = show

        currentAnimator?.cancel()

        val fullWidth = if (targetDeleteWidth > 0) targetDeleteWidth else {
            del.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
            )
            del.measuredWidth.also { targetDeleteWidth = it }
        }
        val fullMarginEnd = dp(del.context, 8)

        if (show) {
            del.visibility = View.VISIBLE
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 220
                addUpdateListener { va ->
                    val fraction = va.animatedFraction
                    val lp = del.layoutParams as? LinearLayout.LayoutParams ?: return@addUpdateListener
                    lp.width = (fullWidth * fraction).toInt()
                    lp.marginEnd = (fullMarginEnd * fraction).toInt()
                    del.layoutParams = lp
                    // translationX 配合：从右侧背后向左滑出
                    del.translationX = (fullWidth * (1f - fraction))
                    del.alpha = fraction
                }
            }
            currentAnimator = animator
            animator.start()
        } else {
            val startWidth = del.width
            val startMargin = (del.layoutParams as? LinearLayout.LayoutParams)?.marginEnd ?: fullMarginEnd
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 180
                addUpdateListener { va ->
                    val fraction = va.animatedFraction
                    val lp = del.layoutParams as? LinearLayout.LayoutParams ?: return@addUpdateListener
                    lp.width = (startWidth * (1f - fraction)).toInt()
                    lp.marginEnd = (startMargin * (1f - fraction)).toInt()
                    del.layoutParams = lp
                    del.translationX = (fullWidth * fraction)
                    del.alpha = 1f - fraction
                    if (fraction >= 1f) {
                        del.visibility = View.GONE
                        del.translationX = 0f
                    }
                }
            }
            currentAnimator = animator
            animator.start()
        }
    }

    /** 从视图树里摘掉自绘按钮 */
    fun detach() {
        currentAnimator?.cancel()
        currentAnimator = null
        val w = wrapper ?: return
        (w.parent as? ViewGroup)?.removeView(w)
        wrapper = null
        scopeView = null
        deleteView = null
        isDeleteVisible = false
    }

    // ------------------------------------------------------------------ 外观

    private fun pill(ctx: android.content.Context): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(ctx, 14).toFloat()
        setColor(0x22FFFFFF)
        setStroke(dp(ctx, 1), 0x44FFFFFF)
    }

    private fun themeColor(ctx: android.content.Context): Int {
        val ta = ctx.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary)
        )
        val c = ta.getColor(0, 0xFFFFFFFF.toInt())
        ta.recycle()
        return c
    }

    private fun dp(ctx: android.content.Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
}
