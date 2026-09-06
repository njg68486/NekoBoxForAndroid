package io.nekohasekai.sagernet.widget

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import io.nekohasekai.sagernet.R

/**
 * kl 分组卡片的左滑露出删除按钮。
 *
 * 行为：
 *   · 左滑 → 卡片右边缘向左「长出」一个正方形删除按钮，盖住编辑 / ⋮ 两个键。
 *     内容本身不位移，只是被盖住 —— 所以这里是覆盖层做宽度揭示，不是整行平移。
 *   · 右滑 → 收回。
 *   · 打开状态不互斥：可以同时滑开多张卡，再逐个点删除。
 *
 * 两个坑，都踩过：
 * 1. 打开状态必须由外部（adapter）按 item id 持有，不能存在 View/ViewHolder 里。
 *    RecyclerView 复用 + notifyItemRemoved 之后槽位下移，holder 内的状态会跟错行，
 *    表现就是「删掉一张，别的展开卡自己合上了」。所以这里只暴露 setOpened/onOpenChanged，
 *    自己不记账。
 * 2. 位置只能有一个真相源。拖动中写 offset、松手用动画从**当前 offset**继续，
 *    不要在松手瞬间切换到另一套动画状态（会先跳回原位再滑开）。
 */
class KlSwipeRevealLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    companion object {
        private const val SETTLE_MS = 220L
    }

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    private lateinit var revealView: View

    /** 揭示宽度 = 正方形边长，随卡片高度在 onMeasure 里定 */
    private var revealWidth = 0

    /** 当前露出量，0 = 收起，revealWidth = 全开。唯一真相源。 */
    private var offset = 0f

    private var animator: ValueAnimator? = null
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var offsetAtDown = 0f

    /** 打开状态变化回调（外部按 id 记账） */
    var onOpenChanged: ((Boolean) -> Unit)? = null

    val isOpened: Boolean get() = offset > 0f

    override fun onFinishInflate() {
        super.onFinishInflate()
        revealView = findViewById(R.id.kl_swipe_reveal)
        clipChildren = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        // 正方形：边长取卡片高度，但不超过卡片宽度
        val target = measuredHeight.coerceAtMost(measuredWidth)
        if (target > 0 && revealView.layoutParams.width != target) {
            revealView.layoutParams = revealView.layoutParams.apply { width = target }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
        revealWidth = revealView.measuredWidth
        if (offset > revealWidth) offset = revealWidth.toFloat()
        pendingOpened?.let {
            pendingOpened = null
            offset = if (it) revealWidth.toFloat() else 0f
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyOffset()
    }

    private fun applyOffset() {
        // 覆盖层始终贴右边缘，靠 translationX 把自己推到卡片外，由 clipChildren 裁掉
        revealView.translationX = (revealWidth - offset)
        revealView.visibility = if (offset <= 0f) INVISIBLE else VISIBLE
    }

    /**
     * 待落位的目标状态：复用时 bind 阶段还没测量出 revealWidth，
     * 先记语义，等 onMeasure 拿到宽度再补 offset。
     */
    private var pendingOpened: Boolean? = null

    /** 外部恢复状态用；animate=false 时立即落位，避免复用时看到动画 */
    fun setOpened(opened: Boolean, animate: Boolean) {
        animator?.cancel()
        animator = null
        if (revealWidth == 0) {
            pendingOpened = opened
            offset = 0f
            applyOffset()
            return
        }
        pendingOpened = null
        val target = if (opened) revealWidth.toFloat() else 0f
        if (!animate || !isLaidOut) {
            offset = target
            applyOffset()
            return
        }
        animateTo(target)
    }

    private fun animateTo(target: Float) {
        animator?.cancel()
        if (offset == target) {
            applyOffset()
            return
        }
        animator = ValueAnimator.ofFloat(offset, target).apply {
            duration = SETTLE_MS
            interpolator = PathInterpolator(0.32f, 0.72f, 0f, 1f)
            addUpdateListener {
                offset = it.animatedValue as Float
                applyOffset()
            }
            start()
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                offsetAtDown = offset
                dragging = false
                // 抓住正在运动的卡片：从当前值接着走，不重置
                animator?.cancel()
                animator = null
            }

            MotionEvent.ACTION_MOVE -> {
                if (revealWidth == 0) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                // 横向意图明显才拦截，否则让 RecyclerView 继续竖滚
                if (kotlin.math.abs(dx) > touchSlop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                    // 已收起时只接受左滑；已展开时只接受右滑
                    if ((offset <= 0f && dx < 0) || (offset > 0f && dx > 0)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (revealWidth == 0) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                offsetAtDown = offset
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = event.x - downX
                    if (kotlin.math.abs(dx) <= touchSlop) return true
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                // 左滑（dx<0）增加露出量
                offset = (offsetAtDown - (event.x - downX)).coerceIn(0f, revealWidth.toFloat())
                applyOffset()
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    val wasOpen = offsetAtDown > 0f
                    val open = offset > revealWidth / 2f
                    animateTo(if (open) revealWidth.toFloat() else 0f)
                    if (open != wasOpen) onOpenChanged?.invoke(open)
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    /** 点删除后由外部调用：无动画归零，防止 item 被移除时残留状态 */
    fun resetImmediately() {
        animator?.cancel()
        animator = null
        offset = 0f
        applyOffset()
    }
}
