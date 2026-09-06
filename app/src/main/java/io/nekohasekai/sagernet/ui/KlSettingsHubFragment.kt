package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.widget.ListListener

/**
 * kl 设置页：五个入口
 *   软件设置（原 SettingsFragment）/ 日志 / 工具 / 文档 / 关于
 *
 * 纯代码行，不用 PreferenceScreen：只有五个固定行、每行是「跳一个页面」而不是偏好项。
 * 五项都不在底栏菜单里，displayFragmentWithId 会把底栏高亮钉回「设置」。
 */
class KlSettingsHubFragment : ToolbarFragment() {

    private data class Entry(
        val titleRes: Int,
        val iconRes: Int,
        val action: () -> Unit,
    )

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?,
    ): View {
        val activity = requireActivity() as MainActivity
        val entries = listOf(
            Entry(R.string.kl_settings_app, R.drawable.ic_action_settings) {
                activity.displayFragmentWithId(R.id.nav_app_settings)
            },
            Entry(R.string.menu_log, R.drawable.ic_baseline_bug_report_24) {
                activity.displayFragmentWithId(R.id.nav_logcat)
            },
            Entry(R.string.menu_tools, R.drawable.baseline_construction_24) {
                activity.displayFragmentWithId(R.id.nav_tools)
            },
            Entry(R.string.document, R.drawable.ic_device_data_usage) {
                requireActivity().launchCustomTab("https://matsuridayo.github.io/")
            },
            Entry(R.string.menu_about, R.drawable.ic_baseline_info_24) {
                activity.displayFragmentWithId(R.id.nav_about)
            },
        )

        val pad = dp2px(16)
        return LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(pad, pad, pad, pad)

            entries.forEach { entry ->
                addView(entryRow(entry))
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.settings)
    }

    /** 一行：图标 + 标题，整体可点，涟漪用主题默认 */
    @SuppressLint("UseCompatLoadingForDrawables")
    private fun entryRow(entry: Entry): View {
        val context = requireContext()
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp2px(14), dp2px(16), dp2px(14), dp2px(16))
            background = context.obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground)).let {
                val d = it.getDrawable(0); it.recycle(); d
            }
            isClickable = true
            isFocusable = true

            addView(ImageView(context).apply {
                setImageResource(entry.iconRes)
                layoutParams = LinearLayout.LayoutParams(dp2px(24), dp2px(24)).apply {
                    marginEnd = dp2px(20)
                }
            })

            addView(TextView(context).apply {
                setText(entry.titleRes)
                textSize = 16f
            })

            setOnClickListener { entry.action() }
        }
    }
}
