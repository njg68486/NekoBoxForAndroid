package io.nekohasekai.sagernet.ui

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import io.nekohasekai.sagernet.R

/**
 * 搜索框内的 [分组/全局] 范围切换按钮。
 *
 * 位置：紧挨清除按钮 X 的左侧，二者之间留出约两个字符的间距。
 * 点一下在「分组」「全局」之间切换。
 *
 * 挂载方式说明（依据 appcompat 1.6.1 的 abc_search_view.xml）：
 *
 *   LinearLayout(search_bar)
 *    └ LinearLayout(search_edit_frame)      ← 不是 FrameLayout！
 *       ├ ImageView(search_mag_icon)
 *       ├ LinearLayout(search_plate)
 *       │  ├ SearchAutoComplete(search_src_text)
 *       │  └ ImageView(search_close_btn)    ← 清除按钮 X 在这里
 *       └ LinearLayout(submit_area)
 *
 * 所以正确做法是把按钮 addView 到 search_plate，并插在 search_close_btn
 * 之前，自然就落在 X 左边；用 marginEnd 撑出间距。
 */
class SearchScopeController {

    enum class Scope { GROUP, GLOBAL }

    private var scopeView: TextView? = null
    private var scope: Scope = Scope.GROUP

    /** 当前的搜索范围 */
    val current: Scope get() = scope

    fun attach(searchView: SearchView, onChanged: (Scope) -> Unit) {
        detach()

        // 布局查找全部走安全转换：appcompat 换版本可能改动 abc_search_view.xml，
        // 拿不到挂载点时应当静默降级（少一个按钮），而不是把整个界面搞崩。
        val plate = searchView.findViewById<View>(
            androidx.appcompat.R.id.search_plate
        ) as? LinearLayout ?: return
        val clear = searchView.findViewById<View>(
            androidx.appcompat.R.id.search_close_btn
        ) ?: return

        val ctx = searchView.context

        val view = TextView(ctx).apply {
            setText(
                if (scope == Scope.GROUP) R.string.search_scope_group
                else R.string.search_scope_global
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            isSingleLine = true
            setPadding(dp(ctx, 8), 0, dp(ctx, 8), 0)
            setTextColor(themeColor(ctx))
            background = pill(ctx)
            isClickable = true
            isFocusable = true

            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(ctx, 28),
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                // 「两个文字的间距」：约 2 × 13sp ≈ 26dp 偏大，取 16dp 视觉上正好
                marginEnd = dp(ctx, 16)
            }

            setOnClickListener {
                scope = if (scope == Scope.GROUP) Scope.GLOBAL else Scope.GROUP
                setText(
                    if (scope == Scope.GROUP) R.string.search_scope_group
                    else R.string.search_scope_global
                )
                onChanged(scope)
            }
        }

        // 插到 X 之前，落在其左侧
        val clearIndex = plate.indexOfChild(clear)
        if (clearIndex >= 0) {
            plate.addView(view, clearIndex)
        } else {
            plate.addView(view)
        }

        scopeView = view
    }

    /** 从视图树里摘掉自绘按钮，避免 fragment 重建时重复叠加 */
    fun detach() {
        val v = scopeView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        scopeView = null
    }

    // ------------------------------------------------------------------ 外观

    /** 圆角胶囊背景 */
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
