package io.nekohasekai.sagernet.group

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class GroupInterfaceAdapter(val context: ThemedActivity) : GroupManager.Interface {

    override suspend fun confirm(message: String): Boolean {
        return suspendCoroutine {
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
                    .setMessage(message)
                    .setPositiveButton(R.string.yes) { _, _ -> it.resume(true) }
                    .setNegativeButton(R.string.no) { _, _ -> it.resume(false) }
                    .setOnCancelListener { _ -> it.resume(false) }
                    .show()
            }
        }
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean
    ) {
        if (changed == 0 && duplicate.isEmpty()) {
            if (byUser) context.snackbar(
                    context.getString(
                            R.string.group_no_difference, group.displayName()
                    )
            ).show()
        } else {
            context.snackbar(context.getString(R.string.group_updated, group.name, changed)).show()
            // kl: 订阅更新的「配置差异预览」弹窗（group_diff：新增/变更/删除/重复清单）
            //     按用户要求移除 —— 添加或更新订阅只保留「更新了 N 个节点」的 snackbar，
            //     结果直接看分组卡片上的配置数即可，不再打断用户。
        }

    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        onMainDispatcher {
            context.snackbar(message).show()
        }
    }

    override suspend fun alert(message: String) {
        return suspendCoroutine {
            runOnMainDispatcher {
                MaterialAlertDialogBuilder(context).setTitle(R.string.ooc_warning)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> it.resume(Unit) }
                    .setOnCancelListener { _ -> it.resume(Unit) }
                    .show()
            }
        }
    }

}