package io.nekohasekai.sagernet.widget

import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * kl 版 Snackbar 排版：条子只占屏幕左半边，右半边让给 dock。
 *
 * 为什么在 onShown 里改而不是 show() 之前：Snackbar 只有在 show() 时才把自己的 view
 * attach 到 parent 并生成 LayoutParams，提前读 view.layoutParams 拿到的是 null；
 * 且 SnackbarManager 每次显示都会重新走一遍布局，所以宽度必须每次 onShown 重写。
 *
 * 宽度砍到一半后，Material 的 SnackbarContentLayout 会按「文本 + action 能否放进一行」
 * 决定单行/多行布局，「还原」很容易被推到第二行 —— 所以同时把 snackbar_text 压成单行省略。
 */
object KlSnackbar {

    /** 左半屏 */
    private const val WIDTH_FRACTION = 0.5f

    fun halfWidth(snackbar: Snackbar): Snackbar {
        snackbar.addCallback(object : Snackbar.Callback() {
            override fun onShown(sb: Snackbar?) = applyNow(snackbar)
        })
        return snackbar
    }

    private fun applyNow(snackbar: Snackbar) {
        val view = snackbar.view
        val parent = view.parent as? ViewGroup ?: return
        val available = parent.width.takeIf { it > 0 } ?: return
        val target = (available * WIDTH_FRACTION).toInt()
        val side = dp2px(8)

        when (val lp = view.layoutParams) {
            is CoordinatorLayout.LayoutParams -> {
                lp.width = target
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.leftMargin = side
                lp.rightMargin = 0
                view.layoutParams = lp
            }

            is FrameLayout.LayoutParams -> {
                lp.width = target
                lp.gravity = Gravity.BOTTOM or Gravity.START
                lp.leftMargin = side
                lp.rightMargin = 0
                view.layoutParams = lp
            }

            else -> {
                val other = view.layoutParams ?: return
                other.width = target
                view.layoutParams = other
            }
        }

        // 单行化。注意 snackbar.view 的直接子节点是 SnackbarContentLayout，
        // 文本和按钮在它里面，所以要用 findViewById 而不是遍历直接子节点。
        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        view.findViewById<Button>(com.google.android.material.R.id.snackbar_action)?.apply {
            maxLines = 1
            minWidth = 0
            minimumWidth = 0
        }
    }
}
