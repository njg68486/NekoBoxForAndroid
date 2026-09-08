package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import io.nekohasekai.sagernet.R

/**
 * 设置中心：收纳原侧边栏中的辅助功能入口。
 * 包含：软件设置 / 日志 / 工具 / 文档(关于)。
 */
class SettingsHubFragment : ToolbarFragment(R.layout.layout_settings_hub) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_settings_hub)

        view.findViewById<View>(R.id.hub_settings).setOnClickListener {
            (activity as MainActivity).displayFragment(SettingsFragment())
        }
        view.findViewById<View>(R.id.hub_logcat).setOnClickListener {
            (activity as MainActivity).displayFragment(LogcatFragment())
        }
        view.findViewById<View>(R.id.hub_tools).setOnClickListener {
            (activity as MainActivity).displayFragment(ToolsFragment())
        }
        view.findViewById<View>(R.id.hub_docs).setOnClickListener {
            (activity as MainActivity).displayFragment(AboutFragment())
        }
    }

}
