package io.nekohasekai.sagernet.widget

import android.text.TextUtils
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.coordinatorlayout.widget.CoordinatorLayout
import com.google.android.material.snackbar.Snackbar

/**
 * kl 版 Snackbar 排版：只把宽度压到左半屏（50%），其余（位置、边距、锚点）
 * 与上游 NekoBox 完全一致 —— 条子仍由 snackbarInternal 锚在底栏上方。
 *
 * 宽度必须在 onShown 里改：Snackbar 在 show() 时才 attach 到 parent 并生成
 * LayoutParams，提前读是 null；且每次显示都会重走布局，所以每次都要重写。
 * 宽度砍半后 Material 会把 action（还原）挤到第二行，故同时把文本压成单行。
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

        when (val lp = view.layoutParams) {
            is CoordinatorLayout.LayoutParams -> {
                lp.width = (available * WIDTH_FRACTION).toInt()
                view.layoutParams = lp
            }

            else -> {
                val other = view.layoutParams ?: return
                other.width = (available * WIDTH_FRACTION).toInt()
                view.layoutParams = other
            }
        }

        view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)?.apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        view.findViewById<Button>(com.google.android.material.R.id.snackbar_action)?.apply {
            maxLines = 1
        }
    }
}
