package io.nekohasekai.sagernet.ui

import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.view.menu.MenuBuilder
import androidx.appcompat.view.menu.MenuPopupHelper
import androidx.appcompat.widget.PopupMenu
import io.nekohasekai.sagernet.ktx.Logs

/**
 * 把菜单挂到任意锚点视图下方弹出。
 *
 * 顶栏的 📄+ 与 ⋮ 是自绘图标，不是 AppCompat 的 action item，所以要自己
 * 指定锚点弹菜单。菜单本身仍然挂在（已隐藏的）Toolbar 上，这里取它的子菜单。
 *
 * MenuPopupHelper 是 AppCompat 内部弹菜单的实现（PopupMenu 只是它的一层壳），
 * 可以绑到任意 View 上，且天然支持子菜单级联展开（📄+ 的「手动设置」是二级菜单）。
 * 它是 @RestrictTo 内部类，但 @RestrictTo 只影响 lint，编译期可访问；
 * 这里仍整体兜底，异常时退回 PopupMenu。
 */
object AppCompatPopupWindowHelper {

    fun show(
        menu: Menu?,
        anchor: View,
        onClick: (MenuItem) -> Boolean,
        fallback: () -> Unit = {},
    ) {
        if (menu == null) {
            fallback()
            return
        }

        try {
            val builder = menu as MenuBuilder
            builder.setCallback(object : MenuBuilder.Callback {
                override fun onMenuItemSelected(menu: MenuBuilder, item: MenuItem): Boolean =
                    onClick(item)

                override fun onMenuModeChange(menu: MenuBuilder) = Unit
            })

            val helper = MenuPopupHelper(anchor.context, builder, anchor)
            helper.setForceShowIcon(true)
            helper.setGravity(Gravity.END)
            helper.show()
        } catch (e: Throwable) {
            Logs.w(e)
            try {
                val pm = PopupMenu(anchor.context, anchor)
                cloneMenu(menu, pm.menu)
                pm.setOnMenuItemClickListener { onClick(it) }
                pm.show()
            } catch (e2: Throwable) {
                Logs.w(e2)
                fallback()
            }
        }
    }

    /** 极简菜单克隆（兜底路径，正常走不到） */
    private fun cloneMenu(src: Menu, dst: Menu) {
        for (i in 0 until src.size()) {
            val it = src.getItem(i)
            val added = dst.add(it.groupId, it.itemId, it.order, it.title)
            added.isCheckable = it.isCheckable
            added.isChecked = it.isChecked
            added.isEnabled = it.isEnabled
            it.icon?.let { ic -> added.icon = ic }
            if (it.hasSubMenu()) {
                it.subMenu?.let { sub -> cloneMenu(sub, added.subMenu!!) }
            }
        }
    }
}