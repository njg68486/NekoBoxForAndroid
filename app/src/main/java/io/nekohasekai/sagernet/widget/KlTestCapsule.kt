package io.nekohasekai.sagernet.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.animation.Animation
import android.view.animation.LinearInterpolator
import android.view.animation.RotateAnimation
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * kl 顶栏测试胶囊 v2 —— HTML 原型 .top-capsule：
 *
 *   [ TCP/HTTP ] ┊ [ ⚡ 延迟测试 ]
 *
 * 改动（用户实机反馈）：
 *  · 紧凑化：模式 34dp + 测试 72dp ≈ 107dp，给标题/搜索腾位
 *  · 测试反馈：setTesting(true) 时右半变「测试中」并持续旋转图标（静默测试不能毫无指示）
 *  · 按钮加 ripple，点击有手感
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
    private val testIcon: ImageView
    private val testLabel: TextView

    private var tcpMode = true
    private var testing = false

    var onModeToggle: ((tcp: Boolean) -> Unit)? = null
    var onTest: (() -> Unit)? = null

    private val spinAnimation = RotateAnimation(
        0f, 360f,
        Animation.RELATIVE_TO_SELF, 0.5f,
        Animation.RELATIVE_TO_SELF, 0.5f
    ).apply {
        duration = 900
        repeatCount = Animation.INFINITE
        interpolator = LinearInterpolator()
    }

    init {
        orientation = HORIZONTAL
        background = GradientDrawable().apply {
            cornerRadius = dp2px(13).toFloat()
            setColor(BG)
            setStroke(dp2px(1), STROKE)
        }

        val rippleBg = context.obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        ).let { val d = it.getDrawable(0); it.recycle(); d }

        modeButton = TextView(context).apply {
            gravity = Gravity.CENTER
            text = "TCP"
            textSize = 11f
            setTextColor(FG)
            isAllCaps = false
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = rippleBg?.constantState?.newDrawable()?.mutate()
        }
        addView(
            modeButton,
            LayoutParams(dp2px(34), LayoutParams.MATCH_PARENT).apply {
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

        testIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_baseline_speed_24)
            setColorFilter(FG)
            layoutParams = LinearLayout.LayoutParams(dp2px(14), dp2px(14)).apply {
                marginEnd = dp2px(3)
                gravity = Gravity.CENTER_VERTICAL
            }
        }
        testLabel = TextView(context).apply {
            setText(R.string.kl_dock_test)
            textSize = 10f
            setTextColor(FG)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        testButton = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rippleBg?.constantState?.newDrawable()?.mutate()
            addView(testIcon)
            addView(testLabel)
        }
        addView(
            testButton,
            LayoutParams(dp2px(72), LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        modeButton.setOnClickListener {
            if (testing) return@setOnClickListener
            tcpMode = !tcpMode
            syncModeLabel()
            onModeToggle?.invoke(tcpMode)
        }
        testButton.setOnClickListener { if (!testing) onTest?.invoke() }
    }

    fun setMode(tcp: Boolean) {
        tcpMode = tcp
        syncModeLabel()
    }

    /** 测试中状态：右半文案「测试中」+ 图标持续旋转，结束后复原 */
    fun setTesting(running: Boolean) {
        if (testing == running) return
        testing = running
        if (running) {
            testLabel.setText(R.string.kl_testing)
            testIcon.startAnimation(spinAnimation)
        } else {
            testIcon.clearAnimation()
            testLabel.setText(R.string.kl_dock_test)
        }
    }

    private fun syncModeLabel() {
        modeButton.text = if (tcpMode) "TCP" else "HTTP"
    }

    /** 竖向虚线分割线：原型 .cap-divider */
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
