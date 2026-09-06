package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * kl 顶栏测试胶囊 —— 对应 HTML 原型 .top-capsule：
 *
 *   [ TCP/HTTP ] ┊ [ ⚡ 延迟测试 ]
 *   ─ 圆角框（radius 13dp、1dp 描边、深底）
 *   ─ 中间竖向虚线分割（DashPathEffect 3-3，上下各留 7dp）
 *   ─ 左 35% 点一下切 TCP/HTTP 模式；右 65% 点一下按当前模式静默开测
 *
 * 用 menu.add(...).setActionView(...) 挂进 toolbar，位于 ☰ 右侧（原型里胶囊在最右）。
 */
class KlTestCapsule @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    companion object {
        private const val BG = 0xFF273640.toInt()
        private const val STROKE = 0xFF43525C.toInt()
        private const val FG = 0xFF91A2AD.toInt()
    }

    private val modeButton: TextView
    private val testButton: LinearLayout

    /** true = TCPing，false = URL Test */
    private var tcpMode = true

    var onModeToggle: ((tcp: Boolean) -> Unit)? = null
    var onTest: (() -> Unit)? = null

    init {
        orientation = HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp2px(13).toFloat()
            setColor(BG)
            setStroke(dp2px(1), STROKE)
        }

        modeButton = TextView(context).apply {
            gravity = Gravity.CENTER
            text = "TCP"
            textSize = 11f
            setTextColor(FG)
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        addView(
            modeButton,
            LayoutParams(dp2px(40), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        addView(
            KlDashedDivider(context),
            LayoutParams(dp2px(1), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_VERTICAL
                topMargin = dp2px(7)
                bottomMargin = dp2px(7)
            }
        )

        testButton = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_baseline_speed_24)
                setColorFilter(FG)
                layoutParams = LayoutParams(dp2px(15), dp2px(15)).apply {
                    marginEnd = dp2px(4)
                    gravity = Gravity.CENTER_VERTICAL
                }
            })
            addView(TextView(context).apply {
                setText(R.string.kl_dock_test) // 延迟测试
                textSize = 10f
                setTextColor(FG)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
        }
        addView(
            testButton,
            LayoutParams(dp2px(80), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        modeButton.setOnClickListener {
            tcpMode = !tcpMode
            syncModeLabel()
            onModeToggle?.invoke(tcpMode)
        }
        testButton.setOnClickListener { onTest?.invoke() }
    }

    fun setMode(tcp: Boolean) {
        tcpMode = tcp
        syncModeLabel()
    }

    private fun syncModeLabel() {
        modeButton.text = if (tcpMode) "TCP" else "HTTP"
    }

    /** 竖向虚线分割线：原型 .cap-divider 的 repeating-linear-gradient */
    private class KlDashedDivider(context: Context) :
        android.view.View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = FG
            strokeWidth = dp2px(1).toFloat()
            style = Paint.Style.STROKE
            pathEffect = DashPathEffect(
                floatArrayOf(dp2px(3).toFloat(), dp2px(3).toFloat()), 0f
            )
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            canvas.drawLine(cx, 0f, cx, height.toFloat(), paint)
        }
    }
}
