package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.fragment.app.Fragment
import io.nekohasekai.sagernet.R

open class ToolbarFragment : Fragment {

    constructor() : super()
    constructor(contentLayoutId: Int) : super(contentLayoutId)

    lateinit var toolbar: Toolbar

    /**
     * kl: 二级页返回键。返回 null = 顶栏页（底栏直系，无返回箭头）；
     * 返回目标 id = 显示返回箭头，点击跳回该页（设置页系的二级页都回 hub）。
     */
    open fun klBackTarget(): Int? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = view.findViewById(R.id.toolbar)
        // kl: 侧边抽屉已下线，顶栏页左侧无图标；二级页显示返回箭头
        klBackTarget()?.let { target ->
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationOnClickListener {
                (activity as? MainActivity)?.displayFragmentWithId(target)
            }
        }
    }

    open fun onKeyDown(ketCode: Int, event: KeyEvent) = false
    open fun onBackPressed(): Boolean = false
}
