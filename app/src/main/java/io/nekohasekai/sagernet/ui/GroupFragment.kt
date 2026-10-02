package io.nekohasekai.sagernet.ui

import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.format.Formatter
import android.view.MenuItem
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.PopupWindow
import android.widget.FrameLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.toUniversalLink
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.QRCodeDialog
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import kotlinx.coroutines.delay
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.toBytesString
import java.lang.NumberFormatException
import java.util.*
import kotlin.math.abs

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>

    /**
     * 分组卡片的拖动排序。☷ 按钮要直接 startDrag()，得留个引用；
     * 同时也挂上了「固定滚动速度」的覆写（见 Callback 里的注释）。
     */
    private lateinit var itemTouchHelper: ItemTouchHelper

    // ------------------------------------------------------ 顶栏：批量操作 / 过滤排序

    /** 顶栏右侧滑出的 [取消｜删除] 条 */
    private lateinit var batchBar: View

    /** [默认｜排序] 的两格 */
    private lateinit var filterDefaultCell: TextView
    private lateinit var filterSortCell: TextView

    /** 「默认」格弹出的来源选择列表 */
    private var filterPopup: PopupWindow? = null

    /** 当前过滤：null = 默认（全部） */
    private var filterSubscription: Boolean? = null

    /** 当前排序：SORT_DEFAULT = 默认（原始顺序）/ SORT_ASC = 升序 / SORT_DESC = 降序 */
    private var sortMode = SORT_DEFAULT

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_group)
        toolbar.inflateMenu(R.menu.add_group_menu)
        toolbar.setOnMenuItemClickListener(this)

        groupListView = view.findViewById(R.id.group_list)
        layoutManager = FixedLinearLayoutManager(groupListView)
        groupListView.layoutManager = layoutManager
        groupAdapter = GroupAdapter()
        GroupManager.addListener(groupAdapter)
        groupListView.adapter = groupAdapter

        batchBar = view.findViewById(R.id.batch_bar)
        setupBatchBar(view)
        setupFilterBar()

        undoManager = UndoSnackbarManager(activity, groupAdapter)

                itemTouchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            // 只保留拖动排序。
            // swipeDirs = 0：左滑不再交给 ItemTouchHelper —— 官方那套「划走即删除」
            // 太容易误触，已改为自己实现的「滑出删除按钮，再点一下才删」
            // （见 GroupHolder#onItemTouch），所以这里彻底交出 swipe。
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            /**
             * 长按卡片拖动排序已移除 —— 现在由 ☷ 按钮触发（GroupHolder#bind）。
             * 只关掉长按入口，startDrag() 仍可正常拖动。
             */
            override fun isLongPressDragEnabled(): Boolean = false

            /**
             * 记录 ☷ 拖动是否进行中。
             * 拖动期间卡片的左滑手势必须让路 —— 同一根手指不能又拖卡片又拉删除按钮。
             */
            override fun onSelectedChanged(
                viewHolder: RecyclerView.ViewHolder?,
                actionState: Int,
            ) {
                super.onSelectedChanged(viewHolder, actionState)
                dragActive = actionState != ItemTouchHelper.ACTION_STATE_IDLE
            }

            /**
             * 覆写自动滚动：速度恒定，不随时间、也不随拖出的距离变化。
             *
             * 官方实现是两个插值器相乘：
             *   ① sDragViewScrollCapInterpolator：随「拖出边界的距离占比」→ 越拖越远越快
             *   ② sDragScrollInterpolator：随「贴住边缘持续的毫秒数/2000」= t^5
             * ② 让速度随时间飙升（1 秒时才 3%，2 秒满速），① 又让它随距离变化 ——
             * 合起来就是「同一段拖动里速度一直在变」，即所谓不统一。
             *
             * 这里①②全去掉，直接返回固定值：不管按住多久、拖出多远，
             * 只要贴住边缘就是同一个速度。
             *
             * 取值 = @dimen/item_touch_helper_max_drag_scroll_per_frame
             *        （库中为 20dp，已核对 aar）* 50% = 10dp/帧。
             */
            override fun interpolateOutOfBoundsScroll(
                recyclerView: RecyclerView,
                viewSize: Int,
                viewSizeOutOfBounds: Int,
                totalSize: Int,
                msSinceStartScroll: Long,
            ): Int {
                val maxScroll = recyclerView.resources.getDimensionPixelSize(
                    R.dimen.item_touch_helper_max_drag_scroll_per_frame
                )
                val speed = (maxScroll * DRAG_SCROLL_RATIO).toInt().coerceAtLeast(1)
                return if (viewSizeOutOfBounds > 0) speed else -speed
            }

            override fun getDragDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val proxyGroup = (viewHolder as GroupHolder).proxyGroup
                if (proxyGroup.ungrouped || proxyGroup.id in GroupUpdater.updating) {
                    return 0
                }
                return super.getDragDirs(recyclerView, viewHolder)
            }

            /**
             * SimpleCallback 把 onSwiped 留成了抽象方法，必须给出实现。
             * swipeDirs 已经是 0，这里不会再被调用 —— 左滑改由 GroupHolder
             * 自己实现的「滑出删除按钮」处理。
             */
            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
            ): Boolean {
                groupAdapter.move(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun clearView(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
            ) {
                super.clearView(recyclerView, viewHolder)
                groupAdapter.commitMove()
            }
        })
        itemTouchHelper.attachToRecyclerView(groupListView)

    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_new_group -> {
                startActivity(Intent(context, GroupSettingsActivity::class.java))
            }

            R.id.action_update_all -> {
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                    .setMessage(R.string.update_all_subscription)
                    .setPositiveButton(R.string.yes) { _, _ ->
                        SagerDatabase.groupDao.allGroups()
                            .filter { it.type == GroupType.SUBSCRIPTION }
                            .forEach {
                                GroupUpdater.startUpdate(it, true)
                            }
                    }
                    .setNegativeButton(R.string.no, null)
                    .show()
            }
        }
        return true
    }

    // ================================================================ 顶栏：批量操作条

    /**
     * 顶栏右上角滑出的 [取消｜删除]。
     *
     * 与「左滑滑出删除按钮」是同一个手感：整块保持自身尺寸不变，
     * 只靠 translationX 平移 —— 收起时被推到屏幕右边缘之外（width），
     * 滑出时回到 0。之所以不平移成「改变宽度」：宽度变化过程中
     * 「删除」二字会先折行，出现竖排文字。
     *
     * 触发条件：左滑展开的分组卡片 ≥ 2 个（见 GroupHolder#setReveal）。
     */
    private fun setupBatchBar(root: View) {
        // 初始：整块推到屏幕右边缘之外
        batchBar.post { batchBar.translationX = batchBar.width.toFloat() }
        batchBar.visibility = View.INVISIBLE
        batchBar.background = topbarSegmentBackground()

        // 两格的样式走 applySegmentCellStyle —— 与 [默认｜排序] **同一个函数、同一套参数**。
        // 标准模板就是 [默认｜排序]，[取消｜删除] 逐项照抄，以后只改一处，两条一起变。
        val barForeground = toolbarForeground()

        // [取消]：所有滑开的卡片收回，本条也跟着缩回去
        root.findViewById<TextView>(R.id.batch_cancel).apply {
            applySegmentCellStyle(this, barForeground)
            setOnClickListener {
                groupAdapter.collapseAllRevealed()
                updateBatchBar()
            }
        }

        // [删除]：把滑开的卡片全部删掉（进 UndoSnackbar，可撤销）
        root.findViewById<TextView>(R.id.batch_delete).apply {
            applySegmentCellStyle(this, barForeground)
            setOnClickListener {
                val positions = revealedGroupIds
                    .mapNotNull { id ->
                        groupAdapter.groupList.indexOfFirst { it.id == id }.takeIf { it >= 0 }
                    }
                    .sorted()
                if (positions.isEmpty()) return@setOnClickListener

                val items = positions.map { it to groupAdapter.groupList[it] }
                // 先一次性收回动画与集合，再移除数据（顺序与单条删除一致）
                groupAdapter.collapseAllRevealed()
                // 从后往前删，避免前面的下标失效
                items.sortedByDescending { it.first }.forEach { groupAdapter.remove(it.first) }
                undoManager.remove(items)
                updateBatchBar()
            }
        }
    }

    /**
     * 顶栏连体按钮的背景：圆角长方形 + **比主题色浅一档的实色** + 描边。
     *
     * 关键（用户明确要求）：底色要「比主题色浅」，而且**必须是实色、不能透明**。
     * 所以这里拿 ?attr/colorPrimary 向白色混 30%，算出一个不透明的浅色；
     * 而不是拿前景色去叠 alpha —— 叠 alpha 看起来就是一层半透明薄纱，
     * 一眼就能看穿，用户明确否掉了那种做法。
     *
     * 顶栏底色本身就是 colorPrimary，按钮比它浅一档才看得出边界。
     * 因为算的是「colorPrimary 变浅」，换主题、切日间/夜间都会跟着变，
     * 没有任何写死的颜色。
     *
     * 描边用 @color/segment_stroke：日间黑、夜间白
     * （values/colors.xml 与 values-night/colors.xml 各一份）。
     */
    private fun topbarSegmentBackground(): Drawable {
        val ctx = requireContext()
        val primary = ctx.getColorAttr(com.google.android.material.R.attr.colorPrimary)
        val fill = ColorUtils.blendARGB(primary, Color.WHITE, TOPBAR_SEGMENT_LIGHTEN)

        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = resources.getDimension(R.dimen.group_delete_corner)
            setColor(fill)
            setStroke(
                resources.getDimensionPixelSize(R.dimen.card_stroke_width),
                ContextCompat.getColor(ctx, R.color.segment_stroke),
            )
        }
    }

    /**
     * 按「滑开的分组数」决定批量条是否滑出。
     *
     * ≥ 2 才出现 —— 只有一条滑开时用卡片自己那个删除按钮就够了，
     * 顶栏再冒一条出来反而碍事。
     *
     * 每次都用 cancel + 重新 animate：不要用「正在动画就跳过」的守卫，
     * 那样动画途中状态又变了会早退，最终停在错误位置。
     */
    private fun updateBatchBar() {
        if (!::batchBar.isInitialized) return
        val show = revealedGroupIds.size >= 2
        val target = if (show) 0f else batchBar.width.toFloat()

        if (show) batchBar.visibility = View.VISIBLE
        batchBar.animate().cancel()
        batchBar.animate()
            .translationX(target)
            .setDuration(150)
            .withEndAction {
                if (!show) batchBar.visibility = View.INVISIBLE
            }
            .start()
    }

    // ================================================================ 顶栏：[默认｜排序]

    /**
     * 「分组」二字右边的 [默认｜排序] 连体按钮。
     *
     * 挂在 Toolbar 上而不是塞进 AppBarLayout —— 因为要让它紧跟在标题右边。
     * Toolbar 的非保留子视图会进它的 content 区（mChildren），在标题之后
     * 依次左→右排布，所以加进去就正好落在「分组」右侧。
     *
     * 单击「默认」→ 弹圆角列表，含 ☁️订阅 / 📂本地 两项（即当前分组）；
     * 单击「排序」→ 直接切换升序 / 降序。
     * 处于「默认」时排序不生效（排序只作用于订阅或本地这一类）。
     */
    private fun setupFilterBar() {
        val ctx = requireContext()

        // 与 [取消｜删除]（layout_appbar_group.xml）**同一套** @dimen/topbar_segment_*，
        // 且每格样式都走 applySegmentCellStyle —— 两条连体按钮就是同一个模板的产物。
        val segmentHeight = resources.getDimensionPixelSize(R.dimen.topbar_segment_height)

        // 顶栏上的文字要用**顶栏自己的前景色**（与那排图标同源）。
        // 不能用 themeTextColor()（那是 ?android:textColorPrimary，是页面正文色：
        // 日间是黑、夜间是白）—— 顶栏底色是 colorPrimary，正文色在它上面
        // 不一定读得清，而 actionBarTheme 下的前景色才是「为这个底准备的」。
        val barForeground = toolbarForeground()

        fun cell(onClick: (View) -> Unit) = TextView(ctx).apply {
            applySegmentCellStyle(this, barForeground)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, segmentHeight,
            )
            setOnClickListener { onClick(it) }
        }

        val defaultCell = cell { showFilterPicker(it) }
        val sortCell = cell { showSortPicker(it) }

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = topbarSegmentBackground()

            // 分隔线用显式 View：dividerDrawable / showDividers 是 API 29+，
            // 本项目 minSdk = 21，不能用。
            // 颜色走 @color/segment_stroke —— 日间黑 / 夜间白，与连体按钮描边同色。
            val divider = View(ctx).apply {
                setBackgroundColor(ContextCompat.getColor(ctx, R.color.segment_stroke))
            }

            addView(defaultCell)
            addView(
                divider,
                LinearLayout.LayoutParams(dp2px(1), ViewGroup.LayoutParams.MATCH_PARENT),
            )
            addView(sortCell)
        }

        // 挂到 Toolbar 的 content 区（标题右侧）。
        // Toolbar 的非保留子视图会进它自己的 mChildren，在标题之后依次左→右
        // 排布，所以加进去就正好落在「分组」二字右边。
        val lp = Toolbar.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, segmentHeight,
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginEnd = dp2px(8)
        }
        toolbar.addView(bar, lp)

        filterDefaultCell = defaultCell
        filterSortCell = sortCell
        updateFilterBar()
    }

    /**
     * 顶栏自己的前景色（actionBarTheme 下的 ?attr/colorControlNormal）。
     *
     * 与顶栏那排图标（☰ ⊙ ☴ 🔍 📄+ ⋮）是**同一个色源**，
     * 所以放在顶栏上的文字/按钮永远和图标同色系。
     */
    private fun toolbarForeground(): Int {
        val ta = toolbar.context.obtainStyledAttributes(
            intArrayOf(androidx.appcompat.R.attr.colorControlNormal)
        )
        val c = ta.getColor(0, 0xFFFFFFFF.toInt())
        ta.recycle()
        return c
    }

    /**
     * 连体按钮「一格」的统一样式 —— [默认｜排序] 与 [取消｜删除] 共用这一个函数。
     *
     * 用户标准：以 [默认｜排序] 为基准模板，[取消｜删除] 照抄。
     * 字号（13sp）、前景色（顶栏图标同源）、居中对齐、最小格宽（48dp）、
     * 左右内边距（8dp）、点按涟漪全部收口在这里 —— 任何一条要调，两条一起变，
     * 杜绝「两条各写各的、越走越偏」再次发生。
     */
    private fun applySegmentCellStyle(tv: TextView, foreground: Int) {
        tv.textSize = 13f
        tv.setTextColor(foreground)
        tv.gravity = Gravity.CENTER
        tv.minWidth = resources.getDimensionPixelSize(R.dimen.topbar_segment_cell_min_width)
        val padH = resources.getDimensionPixelSize(R.dimen.topbar_segment_cell_padding_h)
        tv.setPadding(padH, 0, padH, 0)
        tv.background = rippleDrawable()
    }

    /** 主题里的无界涟漪（点按反馈） */
    private fun rippleDrawable(): android.graphics.drawable.Drawable? {
        val ta = requireContext().obtainStyledAttributes(
            intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
        )
        val d = ta.getDrawable(0)
        ta.recycle()
        return d
    }

    /** 当前主题的字体色（夜间白、日间黑），「取消 / 默认」二字用它 */
    private fun themeTextColor(): Int {
        val ta = requireContext().obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary)
        )
        val c = ta.getColor(0, 0xFF000000.toInt())
        ta.recycle()
        return c
    }

    /**
     * 刷新 [默认｜排序] 两格的文字。
     *
     * 第一格显示当前来源：默认 / ☁️订阅 / 📂本地；
     * 第二格显示当前顺序：排序（未排）/ 升序 / 降序。
     * 两格互相独立、任何时候都能点，没有置灰态。
     */
    private fun updateFilterBar() {
        if (!::filterDefaultCell.isInitialized) return
        val ctx = requireContext()
        // 第一格：当前来源 —— 纯文字，不放任何图标
        filterDefaultCell.text = when (filterSubscription) {
            null -> ctx.getString(R.string.filter_default)
            true -> ctx.getString(R.string.subscription)
            false -> ctx.getString(R.string.filter_local)
        }
        // 第二格：当前顺序 —— 未排序时显示「排序」这个功能名
        filterSortCell.text = when (sortMode) {
            SORT_ASC -> ctx.getString(R.string.sort_ascending)
            SORT_DESC -> ctx.getString(R.string.sort_descending)
            else -> ctx.getString(R.string.group_order)
        }
        // 两格任何时候都能点，不再有置灰态
        filterSortCell.alpha = 1f
        filterDefaultCell.alpha = 1f
    }

    /** 「默认」格：弹出圆角列表，默认 / 订阅 / 本地 三选一 —— 纯文字，无任何图标 */
    private fun showFilterPicker(anchor: View) {
        val ctx = requireContext()
        val content = pickerContent()

        val defaultItem = filterRow(
            ctx.getString(R.string.filter_default),
            filterSubscription == null,
        )
        defaultItem.setOnClickListener { dismissFilterPicker(); setFilter(null) }

        // 纯文字：不带 ☁️ / 📂 之类的前缀图标（用户明确要求，弹窗里只要字）
        val subItem = filterRow(
            ctx.getString(R.string.subscription),
            filterSubscription == true,
        )
        subItem.setOnClickListener { dismissFilterPicker(); setFilter(true) }

        val localItem = filterRow(
            ctx.getString(R.string.filter_local),
            filterSubscription == false,
        )
        localItem.setOnClickListener { dismissFilterPicker(); setFilter(false) }

        addPickerRow(content, defaultItem)
        addPickerRow(content, subItem)
        addPickerRow(content, localItem)

        showPickerWindow(content, anchor)
    }

    /**
     * 「排序」格：弹出圆角列表，默认 / 升序 / 降序 三选一。
     *
     * 跟左边的来源过滤**互不影响** —— 左边是「默认」时照样能排序，
     * 选订阅 / 本地时当然也能。
     */
    private fun showSortPicker(anchor: View) {
        val ctx = requireContext()
        val content = pickerContent()

        listOf(
            SORT_DEFAULT to ctx.getString(R.string.filter_default),
            SORT_ASC to ctx.getString(R.string.sort_ascending),
            SORT_DESC to ctx.getString(R.string.sort_descending),
        ).forEach { (mode, label) ->
            val row = filterRow(label, sortMode == mode)
            row.setOnClickListener { dismissFilterPicker(); setSort(mode) }
            addPickerRow(content, row)
        }

        showPickerWindow(content, anchor)
    }

    /** 弹出列表的空白内容容器（上下留 6dp，圆角与描边由外层容器负责） */
    private fun pickerContent(): LinearLayout = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp2px(6), 0, dp2px(6))
    }

    /**
     * 往弹窗内容里塞一行。
     *
     * 行宽用 MATCH_PARENT：弹窗宽度由 [showPickerWindow] 按**最宽一行**算出来，
     * 每行都铺满整宽，点按涟漪才是整条，而不是只有文字那一小块。
     */
    private fun addPickerRow(content: LinearLayout, row: View) {
        content.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
    }

    /** 把弹出列表挂到 [anchor] 下面显示 */
    private fun showPickerWindow(content: LinearLayout, anchor: View) {
        val ctx = requireContext()

        // 弹窗宽度 = 最宽那一行的**自然宽度**（行的左右 padding 已含在内）。
        // 原先写死 160dp，「默认 / 升序 / 降序」这种两字行右边就空掉一大片，
        // 用户直接点名「列表框太大，右边全是留白」。这里按内容收窄，
        // 行是 MATCH_PARENT，所以每行的涟漪/背景仍然铺满整宽。
        val unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        var popupWidth = 0
        for (i in 0 until content.childCount) {
            val row = content.getChildAt(i)
            row.measure(unspecified, unspecified)
            popupWidth = maxOf(popupWidth, row.measuredWidth)
        }
        // 兜底，避免量出 0 宽的窗
        popupWidth = popupWidth.coerceAtLeast(dp2px(72))

        val container = FrameLayout(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_popup_menu)
            clipToOutline = true
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val popup = PopupWindow(
            container,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = dp2px(8).toFloat()
            setBackgroundDrawable(ColorDrawable(0x00000000))
        }
        popup.showAsDropDown(anchor, 0, dp2px(4))
        filterPopup = popup
    }

    private fun dismissFilterPicker() {
        filterPopup?.dismiss()
        filterPopup = null
    }

    private fun filterRow(label: String, selected: Boolean): TextView =
        TextView(requireContext()).apply {
            text = label
            textSize = 14f
            setTextColor(themeTextColor())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(20), dp2px(12), dp2px(20), dp2px(12))
            isClickable = true
            background = rippleDrawable()
            // 选中项加粗，与 ☴ 列表里当前分组的表现一致
            if (selected) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    /** 应用「默认 / ☁️订阅 / 📂本地」过滤 */
    private fun setFilter(subscriptionOnly: Boolean?) {
        filterSubscription = subscriptionOnly
        groupAdapter.applyFilter()
        updateFilterBar()
    }

    /** 设置排序：默认（原始顺序）/ 升序 / 降序 —— 与过滤互不影响 */
    private fun setSort(mode: Int) {
        sortMode = mode
        groupAdapter.applyFilter()
        updateFilterBar()
    }

    private fun dp2px(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private lateinit var selectedGroup: ProxyGroup

    private val exportProfiles =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            if (data != null) {
                runOnDefaultDispatcher {
                    val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                    val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                    try {
                        (requireActivity() as MainActivity).contentResolver.openOutputStream(
                            data
                        )!!.bufferedWriter().use {
                            it.write(links)
                        }
                        onMainDispatcher {
                            snackbar(getString(R.string.action_export_msg)).show()
                        }
                    } catch (e: Exception) {
                        Logs.w(e)
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        val groupList = ArrayList<ProxyGroup>()

        /**
         * 未经过滤 / 排序的完整列表（数据库里的原始顺序）。
         *
         * groupList 是「当前显示的那一份」：applyFilter() 会按 ☁️订阅 / 📂本地
         * 过滤、并按需升/降序重排它。保留这一份原始数据，取消过滤时才能原样恢复。
         */
        private val fullList = ArrayList<ProxyGroup>()

        suspend fun reload() {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            if (groups.size > 1 && SagerDatabase.proxyDao.countByGroup(groups.find { it.ungrouped }!!.id) == 0L) groups.removeAll { it.ungrouped }
            fullList.clear()
            fullList.addAll(groups)
            rebuildVisible()
            groupListView.post {
                notifyDataSetChanged()
            }
        }

        /**
         * 按当前的「过滤 / 排序」状态重建 groupList。
         *
         * 过滤：null = 默认（全部）；true = 只看订阅；false = 只看本地（非订阅）。
         * 排序：SORT_DEFAULT = 保持数据库原始顺序；SORT_ASC / SORT_DESC = 按名称排。
         * 两者**互相独立** —— 过滤选「默认」时照样能排序。
         */
        private fun rebuildVisible() {
            var list = fullList.toList()
            val filter = filterSubscription
            if (filter != null) {
                list = list.filter { (it.type == GroupType.SUBSCRIPTION) == filter }
            }
            list = when (sortMode) {
                SORT_ASC -> list.sortedBy { it.displayName().lowercase() }
                SORT_DESC -> list.sortedByDescending { it.displayName().lowercase() }
                else -> list
            }
            groupList.clear()
            groupList.addAll(list)
        }

        /** 顶栏 [默认｜排序] 改了状态后由 Fragment 调用 */
        fun applyFilter() {
            rebuildVisible()
            notifyDataSetChanged()
        }

        /**
         * 把所有滑开的卡片一次收回。
         *
         * 先清空集合再逐个收起 —— 不能边收边让 setReveal 去动集合，
         * 否则循环途中顶栏批量条会「先闪出来又缩回去」。
         */
        fun collapseAllRevealed() {
            val ids = revealedGroupIds.toList()
            revealedGroupIds.clear()
            ids.forEach { id ->
                val index = groupList.indexOfFirst { it.id == id }
                if (index >= 0) {
                    (groupListView.findViewHolderForAdapterPosition(index) as? GroupHolder)?.collapse()
                }
            }
        }

        init {
            setHasStableIds(true)

            runOnDefaultDispatcher {
                reload()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupHolder {
            return GroupHolder(LayoutGroupItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: GroupHolder, position: Int) {
            holder.bind(groupList[position])
        }

        override fun getItemCount(): Int {
            return groupList.size
        }

        override fun getItemId(position: Int): Long {
            return groupList[position].id
        }

        private val updated = HashSet<ProxyGroup>()

        fun move(from: Int, to: Int) {
            val first = groupList[from]
            var previousOrder = first.userOrder
            val (step, range) = if (from < to) Pair(1, from until to) else Pair(
                -1, to + 1 downTo from
            )
            for (i in range) {
                val next = groupList[i + step]
                val order = next.userOrder
                next.userOrder = previousOrder
                previousOrder = order
                groupList[i] = next
                updated.add(next)
            }
            first.userOrder = previousOrder
            groupList[to] = first
            updated.add(first)
            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { SagerDatabase.groupDao.updateGroup(it) }
            updated.clear()
        }

        fun remove(index: Int) {
            groupList.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                notifyItemInserted(index)
            }
        }

        override fun commit(actions: List<Pair<Int, ProxyGroup>>) {
            val groups = actions.map { it.second }
            runOnDefaultDispatcher {
                GroupManager.deleteGroup(groups)
                reload()
            }
        }

        override suspend fun groupAdd(group: ProxyGroup) {
            groupList.add(group)
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                notifyItemInserted(groupList.size - 1)

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) return
            onMainDispatcher {
                undoManager.flush()
                if (SagerDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAt(index)
                    notifyItemRemoved(index)
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = groupList.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return
            }
            groupList[index] = group
            onMainDispatcher {
                undoManager.flush()

                notifyItemChanged(index)
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = groupList.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                notifyItemChanged(index)
            }
        }

    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupHolder(binding: LayoutGroupItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        PopupMenu.OnMenuItemClickListener {

        lateinit var proxyGroup: ProxyGroup
        val groupName = binding.groupName
        val groupStatus = binding.groupStatus
        val groupTraffic = binding.groupTraffic
        val groupUser = binding.groupUser
        val editButton = binding.edit
        val optionsButton = binding.options
        val sortButton = binding.groupSort
        val updateButton = binding.groupUpdate
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress
        val deleteButton = binding.groupDelete

        // ---------------------------------------------------- 左滑展开「删除」按钮

        /**
         * 展开进度 0f..1f。
         *
         * 卡片本身完全不动：删除按钮**保持满宽 96dp 不变**，只是整块平移 ——
         * 收起时被卡片右边缘挡在外面（translationX = 满宽），滑开时移回原位
         * （translationX = 0）。所以是「从卡片右边滑出来」，而且全程不会变形。
         *
         * 为什么不再用「改宽度」的办法：宽度从 0 变到 96dp 的过程中，按钮太窄
         * 放不下「删除」两个字，TextView 会自动折行 → 文字先竖排、宽了才横排。
         * 平移法没有这个问题，文字始终横排。
         */
        private var revealProgress = 0f
        private var revealAnim: ValueAnimator? = null
        private var revealDecided = false
        private var revealTracking = false
        private var downRawX = 0f
        private var downRawY = 0f
        private var downProgress = 0f

        /** 展开满宽：⋮(48dp) + ✏️(48dp) = 96dp，正好覆盖到铅笔的位置 */
        private val revealWidthPx: Int
            get() = itemView.resources.getDimensionPixelSize(R.dimen.group_delete_reveal_width)

        init {
            itemView.setOnTouchListener { _, event -> onItemTouch(event) }
            deleteButton.setOnClickListener { deleteSelf() }
        }

        /**
         * 卡片上的横向滑动手势。
         *
         * 只做一件事：按手指位移线性地平移「删除」按钮。
         * 纵向位移一旦超过阈值就立刻放行（返回 false），让列表正常上下滚 ——
         * 这样不会跟 RecyclerView 的滚动打架。
         */
        private fun onItemTouch(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downProgress = revealProgress
                    revealDecided = false
                    revealTracking = true
                    // 必须吃下 DOWN（返回 true）。
                    // 之前返回 false，事件落到卡片自己身上 —— 卡片是可点击的，
                    // 立刻进入按压态、涟漪开始画；随后我们接管 MOVE 并
                    // requestDisallowInterceptTouchEvent(true)，卡片永远等不到
                    // UP/CANCEL，涟漪就卡住不消失，看起来像「☷ 被点住了、
                    // 整张卡片也被点住了」。
                    // 这里吃掉之后卡片根本不会进入按压态，也就没有涟漪和误点击。
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!revealTracking || dragActive) return false
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!revealDecided) {
                        val slop = ViewConfiguration.get(itemView.context).scaledTouchSlop
                        when {
                            // 横向意图明确 → 接管这次手势，并阻止列表抢走
                            abs(dx) > slop && abs(dx) > abs(dy) -> {
                                revealDecided = true
                                itemView.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            // 纵向手势 → 把事件交还给列表滚动
                            abs(dy) > slop -> {
                                itemView.parent?.requestDisallowInterceptTouchEvent(false)
                                revealTracking = false
                                return false
                            }
                            else -> return true
                        }
                    }
                    // 完全跟手：同一个手势里左滑就滑出、又滑回来就缩回去。
                    // 已展开时继续右滑同样有效（downProgress 就是按下时的进度）。
                    setReveal(downProgress - dx / revealWidthPx)
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val decided = revealDecided
                    revealTracking = false
                    revealDecided = false
                    if (decided) {
                        // 滑过一半就留住（可以直接点「删除」），否则缩回去
                        animateReveal(if (revealProgress >= 0.5f) 1f else 0f)
                    }
                    // 已展开时点一下**不再**收起 —— 需求是「滑出来就常驻，
                    // 只有右滑才收回」。点空白处只把事件消费掉，避免误触卡片。
                    // 一律消费，避免顺手触发卡片自己的点击
                    return true
                }
            }
            return false
        }

        private fun setReveal(progress: Float) {
            val p = progress.coerceIn(0f, 1f)
            if (p == revealProgress) return
            revealProgress = p
            // 收起 = 整块移到卡片右边缘之外；展开 = 回到原位。宽度始终是满宽。
            deleteButton.translationX = revealWidthPx * (1f - p)
            deleteButton.isClickable = p > 0.99f
            // 只在两端记录，中间过程不动集合 —— 否则拖到一半松手又弹回去，
            // 集合会来回抖动
            if (p >= 1f) {
                revealedGroupIds.add(proxyGroup.id)
            } else if (p <= 0f) {
                revealedGroupIds.remove(proxyGroup.id)
            } else {
                // 中间过程不更新集合，但要刷新顶栏（≥2 时才滑出）
            }
            if (p >= 1f || p <= 0f) updateBatchBar()
        }

        /** 把「删除」按钮收回原位（顶栏批量条的 [取消] / [删除] 用） */
        fun collapse() {
            revealAnim?.cancel()
            setReveal(0f)
        }

        private fun animateReveal(target: Float) {
            if (revealProgress == target) return
            revealAnim?.cancel()
            revealAnim = ValueAnimator.ofFloat(revealProgress, target).apply {
                duration = 150
                addUpdateListener { setReveal(it.animatedValue as Float) }
                start()
            }
        }

        /**
         * 点「删除」才真的删。
         * 走的是原来 onSwiped 那条路 —— 同样进 UndoSnackbar，可以撤销。
         */
        private fun deleteSelf() {
            val index = bindingAdapterPosition
            if (index == RecyclerView.NO_POSITION) return
            // 先把它移出「已滑开」集合：SQLite 的 rowid 会被复用，
            // 残留的 id 可能让将来某个新分组一建出来就带着展开状态。
            revealedGroupIds.remove(proxyGroup.id)
            animateReveal(0f)
            groupAdapter.remove(index)
            undoManager.remove(index to proxyGroup)
        }

        override fun onMenuItemClick(item: MenuItem): Boolean {

            fun export(link: String) {
                val success = SagerNet.trySetPrimaryClip(link)
                activity.snackbar(if (success) R.string.action_export_msg else R.string.action_export_err)
                    .show()
            }

            when (item.itemId) {
                R.id.action_universal_qr -> {
                    QRCodeDialog(
                        proxyGroup.toUniversalLink(), proxyGroup.displayName()
                    ).showAllowingStateLoss(parentFragmentManager)
                }

                R.id.action_universal_clipboard -> {
                    export(proxyGroup.toUniversalLink())
                }

                R.id.action_export_clipboard -> {
                    runOnDefaultDispatcher {
                        val profiles = SagerDatabase.proxyDao.getByGroup(selectedGroup.id)
                        val links = profiles.joinToString("\n") { it.toStdLink(compact = true) }
                        onMainDispatcher {
                            SagerNet.trySetPrimaryClip(links)
                            snackbar(getString(R.string.copy_toast_msg)).show()
                        }
                    }
                }

                R.id.action_export_file -> {
                    startFilesForResult(exportProfiles, "profiles_${proxyGroup.displayName()}.txt")
                }

                R.id.action_clear -> {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                        .setMessage(R.string.clear_profiles_message)
                        .setPositiveButton(R.string.yes) { _, _ ->
                            runOnDefaultDispatcher {
                                GroupManager.clearGroup(proxyGroup.id)
                            }
                        }
                        .setNegativeButton(android.R.string.cancel, null)
                        .show()
                }
            }

            return true
        }


        fun bind(group: ProxyGroup) {
            proxyGroup = group

            itemView.setOnClickListener { }

            // 复用/重建时按集合恢复状态：
            // 之前滑开过、且还没被右滑收回的分组，这里要重新摆成展开的样子。
            // 直接写状态而不走 setReveal()，避免它把 id 从集合里抹掉。
            revealAnim?.cancel()
            val revealed = proxyGroup.id in revealedGroupIds
            revealProgress = if (revealed) 1f else 0f
            deleteButton.translationX = if (revealed) 0f else revealWidthPx.toFloat()
            deleteButton.isClickable = revealed

            editButton.isGone = proxyGroup.ungrouped
            sortButton.isGone = proxyGroup.ungrouped
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
            }

            /*
             * ☷：按下即开始拖动排序。
             *
             * 不用 setOnClickListener —— 那样得先抬手（ACTION_UP）才触发，
             * 「点一下立刻拖」就变成了「点一下、松手、再拖」。这里直接吃下
             * ACTION_DOWN，手指不动也仍算「按住」，往后一移就是拖动，
             * 手感与长按卡片完全一致。
             *
             * 时序上安全：ItemTouchHelper 的 OnItemTouchListener 在 ACTION_DOWN
             * 时就把坐标存进 mInitialTouchX/Y 了（RecyclerView 的
             * dispatchOnItemTouchIntercept 在子视图处理之前跑），所以这里
             * startDrag() 之后用同一个 DOWN 事件算出的拖动距离正好是 0，不会跳。
             * 随后的 MOVE 事件由它自己的 OnItemTouchListener 接管。
             *
             * 用 ACTION_DOWN 而不是 MOVE 响应：手指不离开 RecyclerView 时拖动
             * 能持续；若真滑出列表外拖动会结束，这是正常行为。
             */
            sortButton.setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN &&
                    this@GroupHolder.bindingAdapterPosition != RecyclerView.NO_POSITION
                ) {
                    itemTouchHelper.startDrag(this@GroupHolder)
                }
                false
            }

            updateButton.setOnClickListener {
                GroupUpdater.startUpdate(proxyGroup, true)
            }

            optionsButton.setOnClickListener {
                selectedGroup = proxyGroup

                val popup = PopupMenu(requireContext(), it)
                popup.menuInflater.inflate(R.menu.group_action_menu, popup.menu)

                if (proxyGroup.type != GroupType.SUBSCRIPTION) {
                    popup.menu.removeItem(R.id.action_share_subscription)
                }
                popup.setOnMenuItemClickListener(this)
                popup.show()
            }

            if (proxyGroup.id in GroupUpdater.updating) {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(11), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = true

                if (!GroupUpdater.progress.containsKey(proxyGroup.id)) {
                    subscriptionUpdateProgress.isIndeterminate = true
                } else {
                    subscriptionUpdateProgress.isIndeterminate = false
                    GroupUpdater.progress[proxyGroup.id]?.let {
                        subscriptionUpdateProgress.max = it.max
                        subscriptionUpdateProgress.progress = it.progress
                    }
                }

                updateButton.isInvisible = true
                editButton.isGone = true
                sortButton.isGone = true
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
                editButton.isGone = proxyGroup.ungrouped
                sortButton.isGone = proxyGroup.ungrouped
            }

            val subscription = proxyGroup.subscription
            if (subscription != null && subscription.bytesUsed > 0L) { // SIP008 & Open Online Config
                groupTraffic.isVisible = true
                groupTraffic.text = if (subscription.bytesRemaining > 0L) {
                    app.getString(
                        R.string.subscription_traffic, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        ), Formatter.formatFileSize(
                            app, subscription.bytesRemaining
                        )
                    )
                } else {
                    app.getString(
                        R.string.subscription_used, Formatter.formatFileSize(
                            app, subscription.bytesUsed
                        )
                    )
                }
                groupStatus.setPadding(0)
            } else if (subscription != null && !subscription.subscriptionUserinfo.isNullOrBlank()) { // Raw
                var text = ""

                fun get(regex: String): String? {
                    return regex.toRegex().findAll(subscription.subscriptionUserinfo).mapNotNull {
                        if (it.groupValues.size > 1) it.groupValues[1] else null
                    }.firstOrNull()
                }

                try {
                    var used: Long = 0
                    get("upload=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    get("download=([0-9]+)")?.apply {
                        used += toLong()
                    }
                    val total = get("total=([0-9]+)")?.toLong() ?: 0
                    val remain = total - used
                    if (used > 0 || total > 0) {
                        text += if (remain > 0) {
                            getString(
                                R.string.subscription_traffic,
                                used.toBytesString(),
                                remain.toBytesString()
                            )
                        } else {
                            getString(R.string.subscription_used, used.toBytesString())
                        }
                    }
                    get("expire=([0-9]+)")?.apply {
                        text += "\n"
                        text += getString(
                            R.string.subscription_expire,
                            Util.timeStamp2Text(this.toLong() * 1000)
                        )
                    }
                } catch (_: NumberFormatException) {
                    // ignore
                }

                if (text.isNotEmpty()) {
                    groupTraffic.isVisible = true
                    groupTraffic.text = text
                    groupStatus.setPadding(0)
                }
            } else {
                groupTraffic.isVisible = false
                groupStatus.setPadding(0, 0, 0, dp2px(4))
            }

            groupUser.text = subscription?.username ?: ""

            runOnDefaultDispatcher {
                val size = SagerDatabase.proxyDao.countByGroup(group.id)
                onMainDispatcher {
                    @Suppress("DEPRECATION") when (group.type) {
                        GroupType.BASIC -> {
                            if (size == 0L) {
                                groupStatus.setText(R.string.group_status_empty)
                            } else {
                                groupStatus.text = getString(R.string.group_status_proxies, size)
                            }
                        }

                        GroupType.SUBSCRIPTION -> {
                            groupStatus.text = if (size == 0L) {
                                getString(R.string.group_status_empty_subscription)
                            } else {
                                val date = Date(group.subscription!!.lastUpdated * 1000L)
                                getString(
                                    R.string.group_status_proxies_subscription,
                                    size,
                                    "${date.month + 1} - ${date.date}"
                                )
                            }

                        }
                    }
                }

            }

        }
    }

    /** ☷ 拖动是否进行中（拖动期间左滑手势必须让路，否则一根手指两个用途） */
    private var dragActive = false

    /**
     * 已经滑开「删除」按钮的分组 id。
     *
     * 列表滚动时行会被回收重建，bind() 会把状态复位 —— 没有这个集合的话，
     * 「滑开之后滚一下列表就自己收回去了」。记在 id 上就能跨复用保留：
     * 只有右滑（或删掉该分组）才会把它移出集合。
     */
    private val revealedGroupIds = HashSet<Long>()

    companion object {
        /**
         * 拖动到列表上下边缘时自动滚动的速度比例。
         *
         * 取固定值：不随时间、也不随拖出边界的距离变化（官方那两个 t^5 插值器
         * 全部去掉），这样整段拖动的滚动速度是统一的。
         * 官方峰值 = @dimen/item_touch_helper_max_drag_scroll_per_frame
         * （库中 20dp/帧）→ 40% = 8dp/帧。
         */
        private const val DRAG_SCROLL_RATIO = 0.4f

        /**
         * 连体按钮底色 = colorPrimary 向白色混合的比例（越大越浅，结果始终不透明）。
         * 0.30 大约比顶栏底色浅一档 —— 块状边界清楚，又不会抢过顶栏本身。
         */
        private const val TOPBAR_SEGMENT_LIGHTEN = 0.30f

        /** 排序三态：默认（原始顺序）/ 升序 / 降序 */
        private const val SORT_DEFAULT = 0
        private const val SORT_ASC = 1
        private const val SORT_DESC = 2
    }

}