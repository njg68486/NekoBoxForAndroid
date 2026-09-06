package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.widget.ListListener

/**
 * kl 服务器配置选择页（链式代理/前置/落地/路由出站共用）。
 *
 * 两阶段：
 *   阶段一：分组卡列表 —— 点某分组进入阶段二
 *   阶段二：该分组节点以双列网格展开 —— 迷你卡只有 名称(≤2行) + 左下角协议，
 *           没有任何 ⋮/编辑/分享/删除（这里只是选一个配置）
 *   点节点卡 → (activity as SelectCallback).returnProfile(id)
 *
 * 顶栏：阶段一 = ❌（finish），阶段二 = ←（回阶段一）。
 */
class KlProfilePickerFragment : ToolbarFragment(R.layout.layout_kl_pick) {

    /** 当前要高亮的已选节点（链式代理里点替换时传入） */
    private var selectedProfileId: Long = 0L

    private lateinit var picker: RecyclerView
    private var stage = STAGE_GROUPS
    private var currentGroup: ProxyGroup? = null
    private var groups: List<ProxyGroup> = emptyList()

    companion object {
        private const val STAGE_GROUPS = 0
        private const val STAGE_PROFILES = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedProfileId = requireActivity().intent.getParcelableExtra<ProxyEntity>(
            ProfileSelectActivity.EXTRA_SELECTED
        )?.id ?: 0L
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.select_profile)

        picker = view.findViewById(R.id.kl_pick_list)
        picker.layoutManager = LinearLayoutManager(requireContext())
        picker.adapter = GroupPickAdapter()

        enterGroupsStage()
    }

    // ==================== 阶段一：分组 ====================

    private fun enterGroupsStage() {
        stage = STAGE_GROUPS
        currentGroup = null
        toolbar.title = getString(R.string.select_profile)
        toolbar.setNavigationIcon(R.drawable.ic_navigation_close)
        toolbar.setNavigationOnClickListener {
            requireActivity().finish()
        }
        runOnDefaultDispatcher {
            var list = SagerDatabase.groupDao.allGroups()
                .filter { !it.ungrouped || SagerDatabase.proxyDao.countByGroup(it.id) > 0 }
            if (list.size == 1) {
                // 只有一个分组：直接进节点网格，省一步点击
                groups = list
                runOnMainDispatcher { enterProfilesStage(list[0]) }
                return@runOnDefaultDispatcher
            }
            groups = list
            // 注意：layoutManager/adapter 赋值会触发 requestLayout，必须在主线程 ——
            // 之前直接写在 worker 里，实机 CalledFromWrongThreadException 闪退
            runOnMainDispatcher {
                picker.layoutManager = LinearLayoutManager(requireContext())
                if (picker.adapter !is GroupPickAdapter) {
                    picker.adapter = GroupPickAdapter()
                }
                (picker.adapter as GroupPickAdapter).reload()
            }
        }
    }

    private fun enterProfilesStage(group: ProxyGroup) {
        stage = STAGE_PROFILES
        currentGroup = group
        toolbar.title = group.displayName()
        toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        toolbar.setNavigationOnClickListener {
            // 只有一个分组时返回没有意义（会立刻又进来），直接关页面
            if (groups.size <= 1) requireActivity().finish() else enterGroupsStage()
        }

        picker.layoutManager = GridLayoutManager(requireContext(), 2)
        picker.adapter = ProfilePickAdapter()
        runOnDefaultDispatcher {
            val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
            runOnMainDispatcher {
                (picker.adapter as ProfilePickAdapter).submit(profiles)
            }
        }
    }

    // ==================== 阶段一 adapter ====================

    private inner class GroupPickAdapter : RecyclerView.Adapter<GroupPickHolder>() {

        private val items = ArrayList<ProxyGroup>()

        fun reload() {
            items.clear()
            items.addAll(groups)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = GroupPickHolder(
            layoutInflater.inflate(R.layout.layout_kl_pick_group, parent, false)
        )

        override fun onBindViewHolder(holder: GroupPickHolder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount() = items.size
    }

    private inner class GroupPickHolder(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind(group: ProxyGroup) {
            view.findViewById<TextView>(R.id.pick_group_name).text = group.displayName()
            val type = if (group.type == GroupType.SUBSCRIPTION)
                getString(R.string.kl_tab_sub) else getString(R.string.kl_tab_local)
            view.setOnClickListener { enterProfilesStage(group) }
            // 数量异步补
            runOnDefaultDispatcher {
                val n = SagerDatabase.proxyDao.countByGroup(group.id)
                runOnMainDispatcher {
                    view.findViewById<TextView>(R.id.pick_group_status).text = "$type · $n"
                }
            }
        }
    }

    // ==================== 阶段二 adapter ====================

    private inner class ProfilePickAdapter : RecyclerView.Adapter<ProfilePickHolder>() {

        private val items = ArrayList<ProxyEntity>()

        fun submit(list: List<ProxyEntity>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ProfilePickHolder(
            layoutInflater.inflate(R.layout.layout_kl_pick_profile, parent, false)
        )

        override fun onBindViewHolder(holder: ProfilePickHolder, position: Int) =
            holder.bind(items[position])

        override fun getItemCount() = items.size
    }

    private inner class ProfilePickHolder(val view: View) : RecyclerView.ViewHolder(view) {
        fun bind(profile: ProxyEntity) {
            view.findViewById<TextView>(R.id.mini_name).text = profile.displayName()
            view.findViewById<TextView>(R.id.mini_protocol).text = profile.displayType()
            val card = view.findViewById<MaterialCardView>(R.id.mini_card)
            // 已选节点描边高亮
            card.strokeWidth = if (profile.id == selectedProfileId) dp2px(2) else dp2px(1)
            card.strokeColor = if (profile.id == selectedProfileId)
                requireContext().getColor(R.color.kl_dock)
            else requireContext().getColor(R.color.kl_pick_stroke)

            view.setOnClickListener {
                (requireActivity() as? ConfigurationFragment.SelectCallback)
                    ?.returnProfile(profile.id)
            }
        }
    }
}
