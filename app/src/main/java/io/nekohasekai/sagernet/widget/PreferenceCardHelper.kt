package io.nekohasekai.sagernet.widget

import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R

/**
 * 设置界面分组圆角卡片化辅助类。
 *
 * 将 PreferenceScreen 中每个 PreferenceCategory 下的连续条目渲染为一个
 * 16dp 圆角卡片：首条顶部圆角、末条底部圆角、条目间 1dp 细分割线。
 * 分组标题(PreferenceCategory)位于卡片外部上方，不设卡片背景。
 */
class PreferenceCardHelper(private val listView: RecyclerView) {

    private enum class CardRole { Top, Middle, Bottom, Single, Header }

    private val roleMap = HashMap<Int, CardRole>()

    fun apply() {
        buildRoleMap()
        applyToVisibleChildren()
    }

    /** 遍历 adapter，按 category 边界计算每个位置的角色。 */
    private fun buildRoleMap() {
        roleMap.clear()
        val adapter = listView.adapter as? PreferenceGroupAdapter ?: return
        val count = adapter.itemCount
        var i = 0
        while (i < count) {
            val pref = adapter.getItem(i) ?: break
            if (pref is PreferenceCategory) {
                roleMap[i] = CardRole.Header
                i++
                continue
            }
            // 收集本分类连续的非 category 条目
            val items = ArrayList<Pair<Int, Preference>>()
            while (i < count) {
                val p = adapter.getItem(i) ?: break
                if (p is PreferenceCategory) break
                items.add(i to p)
                i++
            }
            when (items.size) {
                0 -> Unit
                1 -> roleMap[items[0].first] = CardRole.Single
                else -> {
                    roleMap[items.first().first] = CardRole.Top
                    for (idx in 1 until items.size - 1) {
                        roleMap[items[idx].first] = CardRole.Middle
                    }
                    roleMap[items.last().first] = CardRole.Bottom
                }
            }
        }
    }

    /** 给当前可见的所有条目应用背景。 */
    private fun applyToVisibleChildren() {
        for (i in 0 until listView.childCount) {
            val child = listView.getChildAt(i)
            val pos = listView.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) continue
            val role = roleMap[pos] ?: continue
            child.setBackgroundResource(
                when (role) {
                    CardRole.Top -> R.drawable.bg_pref_item_top
                    CardRole.Middle -> R.drawable.bg_pref_item_middle
                    CardRole.Bottom -> R.drawable.bg_pref_item_bottom
                    CardRole.Single -> R.drawable.bg_pref_item_single
                    CardRole.Header -> R.drawable.bg_pref_card_header
                }
            )
        }
    }

    /**
     * 挂载到 listView：数据变化和滚动时重新应用卡片背景。
     */
    fun attach() {
        listView.post {
            listView.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
                override fun onChanged() = apply()
                override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = apply()
                override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = apply()
                override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = apply()
                override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = apply()
            })
            listView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    applyToVisibleChildren()
                }
            })
            apply()
        }
    }

}
