package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    // 是否为二级页面：true 时左上角显示返回箭头（点击返回上级）。
    // 底部导航的一级页面(配置/分组/分流/设置中心)为 false。
    open val showBackNav: Boolean = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        // 首页左上角 ☰ 已迁移为底部固定导航栏。
        // 二级页面显示返回箭头，一级页面不显示。
        if (showBackNav) {
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener {
                navigateUp()
            }
        } else {
            toolbar.navigationIcon = null
        }
    }

    protected fun navigateUp() {
        (activity as? MainActivity)?.let { it.displayFragmentWithId(R.id.nav_settings) }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
