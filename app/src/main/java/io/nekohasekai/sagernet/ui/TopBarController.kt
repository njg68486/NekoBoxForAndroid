package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.GravityCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.snackbar

/**
 * 配置页顶栏。
 *
 * 目标（一排横向，顺序固定、间隔完全一致）：
 *
 *     ☰   Neko   ⊙   ☴   🔍   📄➕   ⋮
 *
 * ---------------------------------------------------------------------------
 * 为什么容器换成 AppBarLayout、并把 Toolbar 藏起来
 *
 * 之前的做法是「一半自绘 + 一半交给 AppCompat 菜单」：☰/Neko/⊙/☴ 是 Toolbar
 * 的自绘子视图，🔍/📄+/⋮ 是菜单项。菜单项永远被 AppCompat 靠右摆、自绘那半
 * 靠左摆，中间必然剩一大段空档；而且两边各自的内边距也不同。这种拓扑下
 * **间隔不可能做到一致**，只能靠 padding 微调，空隙永远集中在中间。
 *
 * 现在：七个控件全部放进同一个 LinearLayout，相邻之间插等权重 Space，
 * 剩余宽度被平均分成 6 份 —— 每个间隔在数学上完全相等，与屏幕宽窄无关。
 *
 * Toolbar 则设为 GONE：
 *  - 它一个 item 都不能显示（显示了就会和自绘这一排重叠、还会自带 ⋮ 溢出按钮）；
 *  - 但菜单必须保留并 inflate（checkOrderMenu / global_mode 勾选都要 findItem），
 *    所以菜单仍挂在 toolbar 上，只是整个 toolbar 不显示。
 *
 * 左侧留白：整排左右各 6dp，图标本身左右各 6dp，于是第一个图标的左边缘落在
 * 距屏幕 12dp 处 —— 与原版 Toolbar 上 navigationIcon 的位置对齐（用户要求 ☰ 靠左）。
 *
 * ---------------------------------------------------------------------------
 * 图标为什么全部是自绘矢量
 *
 * 踩过的坑：
 *  - 文字符号：⊙(U+2299) 加粗看不出；☴(U+2634) 缺字形时只占位不画图。
 *  - 复用项目旧 drawable：ic_neko_group 形状是「两条等长+一条短横线」，
 *    不是 ☴ 的真实卦形；且它们带 ?attr/colorControlNormal 主题 tint，
 *    手动 setImageResource 时这条链路不可靠、同样不画。
 * 所以顶栏用 ic_topbar_*：纯白填充、不写 android:tint，着色一律代码里
 * setColorFilter()。不依赖字体字形，也不依赖主题解析。
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
internal class TopBarController(
    private val host: ConfigurationFragment,
) : View.OnTouchListener {

    private val context: Context get() = host.requireContext()

    /** 被藏起来的 Toolbar（菜单数据还在它身上） */
    private var toolbarRef: Toolbar? = null

    /** 整排容器 */
    private var row: LinearLayout? = null

    /** 已解析的前景色 */
    private var iconColor: Int = 0xFFFFFFFF.toInt()

    // ---------------------------------------------------------------- 控件

    private var drawerView: View? = null
    private var titleView: View? = null
    private var jumpView: View? = null
    private var groupView: View? = null
    private var searchIcon: View? = null
    private var addIcon: View? = null
    private var moreIcon: View? = null

    /** 七个控件的固定顺序 */
    private val order = ArrayList<View>(7)

    var searchField: SearchView? = null
        private set
    private var searchExpanded = false

    private var callbacks: Callbacks = Noop

    private var groupPopup: PopupWindow? = null

    /** ⊙ 点击循环进度：false=下次去选定节点分组，true=下次去第一个分组 */
    private var jumpToggle = false

    /** 长按判定时长：0.5s */
    private val longPressMs = 500L

    private val longPressRunnable = Runnable {
        val v = pressedView ?: return@Runnable
        longPressFired = true
        when (v) {
            jumpView -> onLongPress()
            groupView -> showGroupPicker(v)
        }
    }
    private var longPressFired = false
    private var pressedView: View? = null

    // ---------------------------------------------------------------- 接口

    interface Callbacks {
        fun onOpenDrawer()
        fun onTitleClicked()
        fun onAddClicked(anchor: View)
        fun onMoreClicked(anchor: View)
    }

    private object Noop : Callbacks {
        override fun onOpenDrawer() = Unit
        override fun onTitleClicked() = Unit
        override fun onAddClicked(anchor: View) = Unit
        override fun onMoreClicked(anchor: View) = Unit
    }

    // ---------------------------------------------------------------- 挂载

    fun attach(toolbar: Toolbar, callbacks: Callbacks) {
        detach()
        this.callbacks = callbacks
        this.toolbarRef = toolbar
        iconColor = resolveForeground(toolbar)

        jumpToggle = false
        longPressFired = false
        pressedView = null
        searchExpanded = false

        toolbar.navigationIcon = null
        toolbar.title = ""
        toolbar.subtitle = ""

        val container: ViewGroup = toolbar.parent as? ViewGroup ?: toolbar
        val hidingToolbar = container !== toolbar

        buildRow(container, hidingToolbar)
        if (hidingToolbar) toolbar.visibility = View.GONE
        Logs.d("TopBar: attached, hidingToolbar=$hidingToolbar controls=${order.size}")
    }

    fun detach() {
        dismissGroupPicker()
        searchExpanded = false
        searchField?.let { sv ->
            (sv.parent as? ViewGroup)?.removeView(sv)
            sv.setQuery("", false)
        }
        row?.removeAllViews()
        row?.let { (it.parent as? ViewGroup)?.removeView(it) }
        row = null
        // 还原 Toolbar，避免换页/重建后它一直是隐藏的
        toolbarRef?.visibility = View.VISIBLE
        toolbarRef = null
        drawerView = null
        titleView = null
        jumpView = null
        groupView = null
        searchIcon = null
        addIcon = null
        moreIcon = null
        order.clear()
        callbacks = Noop
    }

    private fun buildRow(container: ViewGroup, hidingToolbar: Boolean) {
        val barHeight = actionBarSizePx()

        val containerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 左右各 6dp：配合图标自身的 6dp 内边距，第一个图标左边缘落在 12dp，
            // 与原版 navigationIcon 的位置一致（用户要求 ☰ 靠左）。
            setPadding(dp(6), 0, dp(6), 0)
            layoutParams = if (hidingToolbar) {
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, barHeight,
                )
            } else {
                Toolbar.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
        }

        // ☰ 抽屉
        drawerView = iconView(R.drawable.ic_topbar_drawer, R.string.topbar_drawer_desc)
            .apply {
                setOnClickListener {
                    (host.activity as? MainActivity)
                        ?.binding?.drawerLayout?.openDrawer(GravityCompat.START)
                }
            }

        // Neko（粗体）
        titleView = TextView(context).apply {
            text = context.getString(R.string.app_name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            setSingleLine(true)
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(iconColor)
            isClickable = true
            isFocusable = true
            setOnClickListener { callbacks.onTitleClicked() }
        }

        // ⊙ 跳转分组（单击双态循环 / 长按跳末尾，走自定义触摸）
        jumpView = iconView(R.drawable.ic_topbar_jump, R.string.jump_to_group)
            .apply { setOnTouchListener(this@TopBarController) }

        // ☴ 分组快速列表
        groupView = iconView(R.drawable.ic_topbar_group, R.string.topbar_group_desc)
            .apply { setOnTouchListener(this@TopBarController) }

        // 🔍 搜索
        searchIcon = iconView(R.drawable.ic_topbar_search, R.string.topbar_search_desc)
            .apply { setOnClickListener { expandSearch() } }

        // 📄➕ 添加配置
        addIcon = iconView(R.drawable.ic_topbar_add, R.string.topbar_add_desc)
            .apply { setOnClickListener { v -> callbacks.onAddClicked(v) } }

        // ⋮ 溢出菜单
        moreIcon = iconView(R.drawable.ic_topbar_more, R.string.topbar_more_desc)
            .apply { setOnClickListener { v -> callbacks.onMoreClicked(v) } }

        order.clear()
        order.addAll(
            listOfNotNull(
                drawerView, titleView, jumpView, groupView,
                searchIcon, addIcon, moreIcon,
            )
        )

        row = containerRow
        showPersistent()
        container.addView(containerRow)
    }

    /**
     * 七个控件按固定顺序铺开，相邻之间插等权重 Space。
     *
     * 每个图标左右内边距相同（ICON_PAD），Space 由剩余宽度平均分配（6 份，
     * 彼此相等），所以：
     *     可见间隔 = PAD + space + PAD   —— 任意相邻两项完全一致。
     */
    private fun showPersistent() {
        val container = row ?: return
        container.removeAllViews()
        order.forEachIndexed { index, v ->
            (v.parent as? ViewGroup)?.removeView(v)
            container.addView(
                v,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            if (index != order.size - 1) container.addView(spacer())
        }
    }

    /** 等权重占位：剩余宽度被它平均分掉 */
    private fun spacer(): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
    }

    /**
     * 顶栏图标：24dp 矢量 + 左右各 ICON_PAD。
     *
     * 注意这里**不**挂触摸监听 —— 触摸监听会吞掉事件、View 自身的点击就不
     * 触发（☰ 曾被这个坑变成点不动的死图标）。只有 ⊙/☴ 需要长按，由调用处
     * 显式挂上。
     */
    private fun iconView(resId: Int, contentDescRes: Int): ImageView {
        return ImageView(context).apply {
            setImageResource(resId)
            contentDescription = context.getString(contentDescRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setColorFilter(iconColor)
            setPadding(dp(ICON_PAD), 0, dp(ICON_PAD), 0)
            isClickable = true
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    // ---------------------------------------------------------- 搜索展开 / 收起

    /** 展开搜索：整排让位，只留「✕ + 搜索框」 */
    fun expandSearch() {
        if (searchExpanded) return
        val container = row ?: return
        searchExpanded = true

        val sv = searchField ?: host.createSearchView().also { searchField = it }
        configureSearchField(sv)

        container.removeAllViews()

        // SearchView + 右侧常驻的 ✕（见 closeSearchButton）。
        //
        // 顺序保持「搜索框在左、✕ 在右」。SearchView 自带的 search_close_btn
        // 只在有输入时出现、且点它是清空而不是退出，已在 configureSearchField
        // 里藏掉；「分组 / 全局」药丸由 SearchScopeController 插在搜索框内部
        // （search_plate 里、原自带 ✕ 的位置），所以整排读起来是：
        //     [🔍 搜索 …… 分组]  ✕
        container.addView(
            sv,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f),
        )
        // 右侧常驻的 ✕：点它退出搜索
        container.addView(closeSearchButton())

        container.requestLayout()
        sv.requestFocus()
        host.showKeyboard(sv)
        host.onSearchStateChanged(true)
    }

    /** 收起搜索 */
    fun collapseSearch() {
        if (!searchExpanded) return
        searchExpanded = false
        searchField?.let { sv ->
            (sv.parent as? ViewGroup)?.removeView(sv)
            sv.setQuery("", false)
        }
        host.clearSearchQuery()
        host.hideKeyboard()
        host.onSearchStateChanged(false)
        showPersistent()
        row?.requestLayout()
    }

    /**
     * 搜索时右侧常驻的 ✕：点它退出搜索。
     *
     * 为什么不用 SearchView 自带的 search_close_btn：
     *   ① 它只在有输入内容时才显示，空着的时候整个 ✕ 都不见了；
     *   ② 点它是「清空文字」而不是「退出搜索」—— 空文本时它内部的
     *      onCloseClicked() 判断 mIconified=false 之后什么都不做。
     * 所以自己放一个常驻 ✕。自带那个仍然留在视图树里（只是藏起来），
     * 因为 SearchScopeController 拿它当插入锚点：找不到就直接 return，
     * 连「分组 / 全局」药丸都不会出现。
     */
    private fun closeSearchButton(): ImageView =
        iconView(R.drawable.ic_topbar_close, android.R.string.cancel).apply {
            setOnClickListener { collapseSearch() }
        }

    private fun configureSearchField(sv: SearchView) {
        sv.setIconifiedByDefault(false)
        sv.isIconified = false
        sv.queryHint = context.getString(R.string.search)
        sv.maxWidth = Int.MAX_VALUE
        sv.findViewById<View>(androidx.appcompat.R.id.search_plate)
            ?.setBackgroundColor(0x00000000)
        sv.findViewById<EditText>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(iconColor)
            setHintTextColor(0x99FFFFFF.toInt())
        }
        // 不再隐藏 search_mag_icon：原版展开后左侧就是这枚放大镜，
        // 隐掉它反而和原版长得不一样。
        sv.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)?.apply {
            setColorFilter(iconColor)
            // 换成右侧那个常驻 ✕，自带的这个藏起来。
            // 只设 GONE 不够：appcompat 在有文字时会把自带 ✕ 再置回 VISIBLE，
            // 于是和自绘的 ✕ 并排出现两个。把尺寸也压成 0 就彻底不占位、不可见，
            // 但仍留在视图树里给 SearchScopeController 当插入锚点（找不到它会直接 return）。
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(0, 0)
        }
        sv.setOnQueryTextListener(host)

        // 原版的退出方式之一：失焦即收起。
        // （「分组/全局」药丸虽然 isFocusable，但没有 focusableInTouchMode，
        //   触摸模式下不会抢焦点，所以点它不会把搜索框收掉。）
        sv.setOnQueryTextFocusChangeListener { _, hasFocus ->
            if (!hasFocus) collapseSearch()
        }
    }

    // ---------------------------------------------------------------- 外观

    /** 顶栏前景色：从 toolbar 的 context 取（带 actionBarTheme，才是白色） */
    private fun resolveForeground(toolbar: Toolbar): Int {
        val ta = toolbar.context.obtainStyledAttributes(
            intArrayOf(android.R.attr.textColorPrimary)
        )
        val color = ta.getColor(0, 0xFFFFFFFF.toInt())
        ta.recycle()
        return color
    }

    private fun actionBarSizePx(): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(android.R.attr.actionBarSize))
        val v = ta.getDimensionPixelSize(0, dp(56))
        ta.recycle()
        return v
    }

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 触摸

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                longPressFired = false
                pressedView = v
                v.postDelayed(longPressRunnable, longPressMs)
                v.isPressed = true
                return true
            }

            MotionEvent.ACTION_UP -> {
                v.removeCallbacks(longPressRunnable)
                v.isPressed = false
                if (!longPressFired) {
                    when (v) {
                        jumpView -> onJumpTap()
                        groupView -> showGroupPicker(v)
                    }
                }
                longPressFired = false
                pressedView = null
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                v.removeCallbacks(longPressRunnable)
                v.isPressed = false
                longPressFired = false
                pressedView = null
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (event.x < 0 || event.y < 0 || event.x > v.width || event.y > v.height) {
                    v.removeCallbacks(longPressRunnable)
                    v.isPressed = false
                    pressedView = null
                }
                return true
            }
        }
        return false
    }

    /** ⊙ 单击：1) 选定节点所在分组  2) 第一个分组，往复循环 */
    private fun onJumpTap() {
        if (jumpToggle) {
            jumpToggle = false
            jumpToFirstGroup()
        } else {
            jumpToggle = true
            jumpToSelectedGroup()
        }
    }

    /** ⊙ 长按（0.5s）：跳到末尾分组 */
    private fun onLongPress() {
        longPressFired = true
        jumpToggle = false
        jumpToLastGroup()
    }

    // ---------------------------------------------------------------- 分组动作

    private fun groups(): List<ProxyGroup> = host.adapter.groupList

    private fun jumpTo(index: Int) {
        val list = groups()
        if (list.isEmpty()) {
            host.snackbar(R.string.jump_hint_none)
            return
        }
        val target = index.coerceIn(0, list.size - 1)
        host.groupPager.setCurrentItem(target, false)
        DataStore.selectedGroup = list[target].id
    }

    private fun jumpToFirstGroup() = jumpTo(0)

    private fun jumpToLastGroup() = jumpTo(groups().size - 1)

    private fun jumpToSelectedGroup() {
        val list = groups()
        if (list.isEmpty()) {
            host.snackbar(R.string.jump_hint_none)
            return
        }
        val selectedId = host.selectedItem?.id ?: DataStore.selectedProxy
        if (selectedId > 0L) {
            val entity = SagerDatabase.proxyDao.getById(selectedId)
            if (entity != null) {
                val index = list.indexOfFirst { it.id == entity.groupId }
                if (index >= 0) {
                    jumpTo(index)
                    host.snackbar(R.string.jump_hint_selected)
                    return
                }
            }
        }
        jumpToFirstGroup()
    }

    // ------------------------------------------------------------ 分组快速列表

    fun dismissGroupPicker() {
        groupPopup?.dismiss()
        groupPopup = null
    }

    private fun showGroupPicker(anchor: View) {
        dismissGroupPicker()

        val list = groups()
        if (list.isEmpty()) {
            host.snackbar(R.string.jump_hint_none)
            return
        }

        val currentId = DataStore.selectedGroup

        /*
         * 顺序保持原样，一个都不动。
         *
         * 之前那版是错的：把选中的分组从原位挪到列表第一个。你要的不是这个 ——
         * 「显示在第一」指的是**打开列表时滚动位置就在它身上**，让它一眼可见；
         * 它在原排序里前面还有几个分组，就照样在前面，可以往上滚。
         * 选中项排在末尾时也一样，列表就停在最后那几个上。
         *
         * 所以排序逻辑整体删掉，改由下面的 scrollView.post { scrollTo } 完成。
         */

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
            // 这里**不**设背景、也不设 clipToOutline。
            //
            // 描边必须只由外面那层固定框（ScrollView）来画：card 是滚动内容，
            // 一旦给它描边，那张描边就会跟着内容一起滚走 —— 内容够长时它还会
            // 撑满整个高度、把固定框的上下描边盖住，看起来就是「滑动时上下描边
            // 消失，滚到两端才冒出来」。
            //
            // 透明的滚动内容 + 固定框自己裁剪 = 框线始终定在那里不动。
        }

        // 记下选中项那一行，稍后用滚动位置把它带到视口里
        var selectedRow: View? = null

        for ((index, g) in list.withIndex()) {
            val isCurrent = g.id == currentId

            val label = TextView(context).apply {
                text = g.displayName()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                if (isCurrent) setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(android.R.attr.textColorPrimary))
            }

            /*
             * 配置名称的圆角矩形背景。
             *
             * 外观照搬抽屉菜单的选中项 + 节点卡片的选中态：
             *   · 圆角 = @dimen/nav_item_corner_radius (14dp)
             *   · 底色 = ?attr/colorPrimary 运行时解析后取 16% alpha
             *   · **选中时加主题色描边** —— 与配置列表里「选定了节点」完全一致
             *     （ConfigurationFragment#applySelected：strokeColor = colorPrimary，
             *      strokeWidth = @dimen/card_stroke_width_selected），
             *     这样哪一条是当前分组一眼就能看出来。
             *
             * 圆角矩形只包住文字，左右两侧是留白。
             */
            val nameCard = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = groupItemCard(isCurrent)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                )
            }
            nameCard.addView(
                label,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    1f,
                ),
            )

            val rowView = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // 上下各 2dp：相邻两个圆角矩形之间正好隔 4dp，
                // 与配置列表里节点卡片之间的间隔一致（layout_profile.xml 的
                // android:layout_margin="2dp" 两侧相加）。
                // 左右 12dp：绿点已移除，这里的留白相应收窄一点。
                setPadding(dp(12), dp(2), dp(12), dp(2))
                isClickable = true
            }

            rowView.addView(nameCard)

            if (isCurrent) selectedRow = rowView

            rowView.setOnClickListener {
                dismissGroupPicker()
                // index 就是 groups() 的下标，与 jumpTo(target) 同一套编号，
                // 不必再 indexOfFirst 匹配一遍（项目里跳转选中分组也是这么做的）
                jumpTo(index)
            }

            // 长按分组名 → 进该分组的设置编辑界面
            rowView.setOnLongClickListener {
                dismissGroupPicker()
                openGroupSettings(g.id)
                true
            }

            card.addView(rowView)
        }

        val scroll = ScrollView(context).apply {
            addView(card)
            isFillViewport = false
            // 这一层只负责滚动，**不画任何东西** —— 背景与描边全部交给外层容器。
            isVerticalScrollBarEnabled = false
        }

        /*
         * 固定容器：整个浮层就是它，尺寸不再变。
         *
         * 描边只画在这一层，而且它是 PopupWindow 的 content view ——
         * 尺寸就是窗口尺寸、不参与滚动，所以四条边永远定格。
         *
         * 为什么必须单独有这一层：ScrollView 的内容可以在 onMeasure 阶段决定
         * 自己的高度，配合 WRAP_CONTENT 的窗口曾经出现过「背景比可视窗口更高、
         * 上下边缘被裁在视野之外」的情况 —— 那种情况下上下描边会"消失"，
         * 只有把边缘滚进视野才看得到（用户实测到的正是这个）。把描边放在
         * 尺寸固定的容器上，结构上就不可能再出现这种问题。
         */
        val container = FrameLayout(context).apply {
            background = roundedCard()
            clipToOutline = true
            addView(
                scroll,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val popup = PopupWindow(
            container,
            pickerWidth(),
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            elevation = dp(8).toFloat()
            setBackgroundDrawable(ColorDrawable(0x00000000))
        }

        popup.showAsDropDown(anchor, pickerOffsetX(anchor, popup.width), dp(4))

        /*
         * 列表顺序完全不动，只把滚动位置对准当前选中的分组。
         *
         * 「选中配置分组显示在第一」= 打开列表时它就在视口里、一眼看到；
         * 它在原排序里前面有几个分组，前面就照样有几个（可以往上滚），
         * 排在末尾时撑不到更大滚动量、列表自然停在最后那几个上 —— 正是要的效果。
         *
         * 必须 post 到布局完成之后：showAsDropDown 之前 ScrollView 还没测量，
         * 这时 scrollTo 会被当成 0。
         */
        val target = selectedRow
        if (target != null) {
            scroll.post {
                // target.top 是相对 card 的坐标；减去 card 的内边距，
                // 让选中行的上边缘正好落在视口顶部，不露出一截上一条。
                scroll.scrollTo(0, (target.top - card.paddingTop).coerceAtLeast(0))
            }
        }

        groupPopup = popup
    }

    private fun openGroupSettings(groupId: Long) {
        context.startActivity(
            Intent(context, GroupSettingsActivity::class.java)
                .putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, groupId)
        )
    }

    /**
     * 弹出列表的卡片背景：圆角 + 描边。
     *
     * 数值与项目统一样式 bg_popup_menu.xml 对齐（⋮/📄+ 那些菜单用的就是它）：
     *   corners = @dimen/popup_corner_radius (12dp)
     *   stroke  = @dimen/card_stroke_width (1dp) + @color/card_stroke (#29000000)
     * 直接用 xml 会带 ?attr/colorSurface（按主题解析），这里照数值用 GradientDrawable
     * 画，solid 走解析后的 colorBackground，保证和主题一致。
     */
    /**
     * ☴ 列表里每个配置名称的圆角矩形背景。
     *
     * **颜色跟着主题走，不是固定色**：
     *   底色 = ?attr/colorPrimary 在运行时解析后取 16% alpha，
     *   与抽屉菜单选中项（res/color/nav_item_fill.xml）完全同源 ——
     *   那边就是 `?attr/colorPrimary` + alpha 0.16。
     *   所以换主题（含日间/夜间、各种配色）时这里会自动跟着变。
     *
     * 圆角同样取主题里那套：@dimen/nav_item_corner_radius (14dp)。
     *
     * 选中项额外描一圈主题色边 —— 与配置列表里「选定了节点」一模一样
     * （ConfigurationFragment#applySelected）：
     *   strokeColor = colorPrimary
     *   strokeWidth = @dimen/card_stroke_width_selected (2dp)
     * 这样当前分组一眼可见。
     *
     * 注意不要改成 setColor(固定值)：一旦写死，切主题就不会跟着变了。
     */
    private fun groupItemCard(selected: Boolean): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.resources.getDimension(R.dimen.nav_item_corner_radius)
        // 解析当前主题的 colorPrimary，再按 nav_item_fill 的 16% 上透明
        val primary = themeColor(android.R.attr.colorPrimary)
        setColor(
            ColorUtils.setAlphaComponent(
                primary,
                (Color.alpha(primary) * NAV_ITEM_FILL_ALPHA).toInt(),
            )
        )
        if (selected) {
            setStroke(
                context.resources.getDimensionPixelSize(R.dimen.card_stroke_width_selected),
                primary,
            )
        }
    }

    private fun roundedCard(): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = context.resources.getDimension(R.dimen.popup_corner_radius)
        setColor(themeColor(android.R.attr.colorBackground))
        setStroke(
            context.resources.getDimensionPixelSize(R.dimen.card_stroke_width),
            ContextCompat.getColor(context, R.color.card_stroke),
        )
    }

    private fun dotDrawable(color: Int): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun themeColor(attr: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0xFF888888.toInt())
        ta.recycle()
        return c
    }

    private fun pickerWidth(): Int {
        val screen = context.resources.displayMetrics.widthPixels
        return minOf(dp(240), (screen * 0.7f).toInt())
    }

    /**
     * ☴ 列表相对锚点的水平偏移。
     *
     * 默认「弹窗左边 = 图标左边」（xoff = 0）。但 ☴ 在顶栏靠右，
     * 240dp 的列表从左对齐会直接顶出屏幕右边 —— 系统虽然会把它拉回来，
     * 结果是**右边缘紧贴屏幕边、一点缝都没有**（用户反馈的原话）。
     *
     * 所以这里自己算：左右各留 [PICKER_SCREEN_MARGIN] dp，
     * 放不下就整体左移，和顶栏 ⋮ 菜单那种「弹窗离屏幕边有缝」的观感一致。
     */
    private fun pickerOffsetX(anchor: View, width: Int): Int {
        val margin = dp(PICKER_SCREEN_MARGIN)
        val screen = context.resources.displayMetrics.widthPixels
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        var x = loc[0]
        // 右边顶出去 → 左移，让右边缘正好留出 margin
        if (x + width > screen - margin) x = screen - margin - width
        // 左移过头 → 再夹回左边界
        if (x < margin) x = margin
        return x - loc[0]
    }

    companion object {
        /** 每个图标左右内边距：24dp 图标 + 两边各 6dp = 36dp 一格 */
        private const val ICON_PAD = 6

        /**
         * ☴ 列表圆角矩形的填充透明度。
         * 与 res/color/nav_item_fill.xml 里那个 alpha 保持一致（0.16），
         * 这样它和抽屉菜单选中项是同一种底色。
         */
        private const val NAV_ITEM_FILL_ALPHA = 0.16f

        /**
         * ☴ 列表与屏幕左右边缘的最小间距（dp）。
         * 8dp ≈ 顶栏 ⋮ 菜单离屏幕边的观感，窄屏上也不会显得贴边。
         */
        private const val PICKER_SCREEN_MARGIN = 8
    }
}