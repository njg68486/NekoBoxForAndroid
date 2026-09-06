package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.databinding.LayoutGroupBinding
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
import java.text.SimpleDateFormat
import java.util.*

class GroupFragment : ToolbarFragment(R.layout.layout_group),
    Toolbar.OnMenuItemClickListener {

    /** kl: 分段筛选（默认/订阅/本地），不持久化，进页面重置 */
    enum class KlTab { DEFAULT, SUB, LOCAL }

    /** kl: 排序模式。默认 = userOrder 原样 */
    enum class KlSort { DEFAULT, ASC, DESC }

    private var currentTab: KlTab = KlTab.DEFAULT
    private var currentSort: KlSort = KlSort.DEFAULT

    lateinit var activity: MainActivity
    lateinit var groupListView: RecyclerView
    lateinit var layoutManager: LinearLayoutManager
    lateinit var groupAdapter: GroupAdapter
    lateinit var undoManager: UndoSnackbarManager<ProxyGroup>
    private lateinit var binding: LayoutGroupBinding

    /** 排序弹出小列表（圆角） */
    private var sortPopup: PopupWindow? = null

    private val touchHelper by lazy { ItemTouchHelper(dragCallback) }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity = requireActivity() as MainActivity

        binding = LayoutGroupBinding.bind(view)
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

        undoManager = UndoSnackbarManager(activity, groupAdapter)

        touchHelper.attachToRecyclerView(groupListView)

        // kl: [默认|订阅|本地] 连体分段 + 排序键。
        // include 进来的子树 id 不进 LayoutGroupBinding，直接 findViewById。
        val segmentGroup = view.findViewById<com.google.android.material.button.MaterialButtonToggleGroup>(R.id.kl_segment_group)
        sortButtonView = view.findViewById(R.id.kl_sort_button)
        segmentGroup.check(R.id.kl_tab_default)
        segmentGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            currentTab = when (checkedId) {
                R.id.kl_tab_sub -> KlTab.SUB
                R.id.kl_tab_local -> KlTab.LOCAL
                else -> KlTab.DEFAULT
            }
            groupAdapter.reloadNow()
        }
        sortButtonView.setOnClickListener { anchor ->
            showSortMenu(anchor)
        }
    }

    /** 圆角小列表：默认 / 升序 / 降序 */
    private fun showSortMenu(anchor: View) {
        val itemLayout = android.R.layout.simple_list_item_1
        val items = listOf(
            R.string.kl_sort_default to KlSort.DEFAULT,
            R.string.kl_sort_asc to KlSort.ASC,
            R.string.kl_sort_desc to KlSort.DESC,
        )
        val container = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.kl_sort_popup_bg)
            val pad = dp2px(4)
            setPadding(pad, pad, pad, pad)
        }
        val popup = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        )
        sortPopup = popup
        items.forEach { (labelRes, mode) ->
            val row = layoutInflater.inflate(itemLayout, container, false) as TextView
            row.setText(labelRes)
            row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            row.setPadding(dp2px(16), dp2px(12), dp2px(16), dp2px(12))
            val isCurrent = mode == currentSort
            row.setTypeface(row.typeface, if (isCurrent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            row.setOnClickListener {
                currentSort = mode
                popup.dismiss()
                groupAdapter.reloadNow()
            }
            container.addView(row)
        }
        popup.isOutsideTouchable = true
        popup.showAsDropDown(anchor, 0, -dp2px(6))
    }

    private lateinit var sortButtonView: androidx.appcompat.widget.AppCompatImageButton

    /** 排序键亮起与否与当前模式联动 */
    private fun syncSortButton() {
        if (!::sortButtonView.isInitialized) return
        sortButtonView.alpha = if (currentSort == KlSort.DEFAULT) 0.6f else 1f
        sortButtonView.setImageResource(R.drawable.ic_baseline_filter_list_24)
    }

    // ==================== 拖拽排序（只允许 ☰ 手柄发起） ====================

    private val dragCallback = object : ItemTouchHelper.Callback() {
        override fun getMovementFlags(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
        ): Int {
            val group = (viewHolder as? GroupHolder)?.proxyGroup ?: return 0
            if (group.ungrouped || group.id in GroupUpdater.updating) return 0
            // 排序/筛选视图里位置与 userOrder 不对应，拖动会乱序，先禁掉
            if (currentTab != KlTab.DEFAULT || currentSort != KlSort.DEFAULT) return 0
            return makeMovementFlags(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
            )
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder,
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            groupAdapter.move(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

        override fun isLongPressDragEnabled() = false // 只走 ☰ 手柄

        override fun clearView(
            recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
        ) {
            super.clearView(recyclerView, viewHolder)
            groupAdapter.commitMove()
        }
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
                        onMainDispatcher {
                            snackbar(e.readableMessage).show()
                        }
                    }

                }
            }
        }

    override fun onDestroyView() {
        sortPopup?.dismiss()
        sortPopup = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        if (::groupAdapter.isInitialized) {
            GroupManager.removeListener(groupAdapter)
        }

        super.onDestroy()

        if (!::undoManager.isInitialized) return
        undoManager.flush()
    }

    inner class GroupAdapter : RecyclerView.Adapter<GroupHolder>(),
        GroupManager.Listener,
        UndoSnackbarManager.Interface<ProxyGroup> {

        /** 全量数据（未筛选排序），reload 的真相源 */
        val allGroups = ArrayList<ProxyGroup>()

        /** 展示数据 = allGroups 经 tab 筛选 + sort 排序 */
        val groupList = ArrayList<ProxyGroup>()

        /**
         * 左滑展开的分组 id 集合。放在 adapter 而不是 holder/View 里：
         * RecyclerView 复用 + notifyItemRemoved 会让槽位下移，holder 内的状态会跟错行，
         * 表现就是删掉一张、别的展开卡自己合上。用 id 记账就与位置无关，
         * 因此可以同时滑开多张、再逐个点删除。
         */
        val openedGroupIds = HashSet<Long>()

        suspend fun reload() {
            val groups = SagerDatabase.groupDao.allGroups().toMutableList()
            if (groups.size > 1 && SagerDatabase.proxyDao.countByGroup(groups.find { it.ungrouped }!!.id) == 0L) groups.removeAll { it.ungrouped }
            allGroups.clear()
            allGroups.addAll(groups)
            applyFilterSort()
        }

        /** 主线程立即刷新展示列表 */
        fun reloadNow() {
            applyFilterSort()
        }

        private fun applyFilterSort() {
            val base = when (currentTab) {
                KlTab.DEFAULT -> allGroups.toList()
                KlTab.SUB -> allGroups.filter { it.type == GroupType.SUBSCRIPTION }
                KlTab.LOCAL -> allGroups.filter { it.type != GroupType.SUBSCRIPTION && !it.ungrouped }
            }
            val sorted = when (currentSort) {
                KlSort.DEFAULT -> base
                KlSort.ASC -> when (currentTab) {
                    // 订阅按到期时间（expiryDate 秒）；本地按创建时间
                    KlTab.SUB -> base.sortedBy { it.subscription?.expiryDate?.times(1000L) ?: it.createdAt }
                    else -> base.sortedBy { it.createdAt }
                }

                KlSort.DESC -> when (currentTab) {
                    KlTab.SUB -> base.sortedByDescending { it.subscription?.expiryDate?.times(1000L) ?: it.createdAt }
                    else -> base.sortedByDescending { it.createdAt }
                }
            }
            groupList.clear()
            groupList.addAll(sorted)
            groupListView.post {
                notifyDataSetChanged()
            }
            syncSortButton()
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

            // allGroups 同步换序，避免切回「默认」时旧顺序闪回
            allGroups.clear()
            allGroups.addAll(groupList)

            notifyItemMoved(from, to)
        }

        fun commitMove() = runOnDefaultDispatcher {
            updated.forEach { SagerDatabase.groupDao.updateGroup(it) }
            updated.clear()
        }

        fun remove(index: Int) {
            val group = groupList.removeAt(index)
            allGroups.removeAll { it.id == group.id }
            openedGroupIds.remove(group.id)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, ProxyGroup>>) {
            for ((index, item) in actions) {
                groupList.add(index, item)
                if (allGroups.none { it.id == item.id }) allGroups.add(item)
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
            allGroups.add(group)
            delay(300L)

            onMainDispatcher {
                undoManager.flush()
                applyFilterSort()

                if (group.type == GroupType.SUBSCRIPTION) {
                    GroupUpdater.startUpdate(group, true)
                }
            }
        }

        override suspend fun groupRemoved(groupId: Long) {
            allGroups.removeAll { it.id == groupId }
            onMainDispatcher {
                undoManager.flush()
                if (SagerDatabase.groupDao.allGroups().size <= 2) {
                    runOnDefaultDispatcher {
                        reload()
                    }
                } else {
                    groupList.removeAll { it.id == groupId }
                    notifyDataSetChanged()
                }
            }
        }

        override suspend fun groupUpdated(group: ProxyGroup) {
            val index = allGroups.indexOfFirst { it.id == group.id }
            if (index == -1) {
                reload()
                return
            }
            allGroups[index] = group
            onMainDispatcher {
                undoManager.flush()
                applyFilterSort()
            }
        }

        override suspend fun groupUpdated(groupId: Long) {
            val index = allGroups.indexOfFirst { it.id == groupId }
            if (index == -1) {
                reload()
                return
            }
            onMainDispatcher {
                applyFilterSort()
            }
        }

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
        val updateButton = binding.groupUpdate
        val subscriptionUpdateProgress = binding.subscriptionUpdateProgress
        val swipeRoot = binding.klSwipeRoot
        val deletePanel = binding.klSwipeReveal
        val dragHandle = binding.dragHandle
        val groupCreated = binding.groupCreated

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

            // kl: 点卡片 = 选定该分组并切到配置页（原逻辑是空监听）
            itemView.setOnClickListener {
                DataStore.selectedGroup = group.id
                activity.displayFragmentWithId(R.id.nav_configuration)
            }

            // kl: ☰ 手柄按下即拖动排序
            dragHandle.setOnTouchListener { v, event ->
                if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                    v.performClick()
                    touchHelper.startDrag(this)
                }
                false // 不消费，让长按等默认行为照常
            }

            // kl: 左滑展开状态从 adapter 的 id 集合恢复（不存 holder 里，防复用错位）
            swipeRoot.setOpened(group.id in groupAdapter.openedGroupIds, animate = false)
            swipeRoot.onOpenChanged = { open ->
                if (open) groupAdapter.openedGroupIds.add(group.id)
                else groupAdapter.openedGroupIds.remove(group.id)
            }

            // kl: 点删除面板 → 删除（有撤销条兜底）
            deletePanel.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION) {
                    swipeRoot.resetImmediately()
                    groupAdapter.remove(index)
                    undoManager.remove(index to proxyGroup)
                }
            }

            editButton.isGone = proxyGroup.ungrouped
            updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
            groupName.text = proxyGroup.displayName()

            // kl: 「创建·M-d」；旧数据 createdAt=0 不显示，避免满屏 1970-1-1
            if (proxyGroup.createdAt > 0L) {
                groupCreated.isVisible = true
                val fmt = SimpleDateFormat("M-d", Locale.getDefault())
                groupCreated.text = getString(R.string.kl_created_at, fmt.format(Date(proxyGroup.createdAt)))
            } else {
                groupCreated.isVisible = false
            }

            editButton.setOnClickListener {
                startActivity(Intent(it.context, GroupSettingsActivity::class.java).apply {
                    putExtra(GroupSettingsActivity.EXTRA_GROUP_ID, group.id)
                })
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
                dragHandle.isGone = true
            } else {
                (groupName.parent as LinearLayout).apply {
                    setPadding(paddingLeft, dp2px(15), paddingRight, paddingBottom)
                }

                subscriptionUpdateProgress.isVisible = false
                updateButton.isInvisible = proxyGroup.type != GroupType.SUBSCRIPTION
                editButton.isGone = proxyGroup.ungrouped
                dragHandle.isGone = proxyGroup.ungrouped
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

}
