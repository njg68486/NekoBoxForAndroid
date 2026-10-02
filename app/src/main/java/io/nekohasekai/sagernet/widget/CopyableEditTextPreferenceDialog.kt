package io.nekohasekai.sagernet.widget

import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.fragment.app.Fragment
import androidx.preference.EditTextPreferenceDialogFragmentCompat
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet

/**
 * 所有「点一下弹输入框」的配置项共用的对话框。
 * 在自带行为上只加两件事：**直接显示内容**、**补上「清空 / 复制」按钮**。
 *
 * ## 一、为什么不换布局（踩过的坑，别再动）
 *
 * 曾经为了去掉圆角外框，把布局换成统一的「裸 EditText」，结果：
 *
 * 1. **闪退**：订阅链接用的是 [LinkOrContentPreference]，它的
 *    `OnBindEditTextListener` 里要
 *    `findViewById<TextInputLayout>(R.id.input_layout)` 并操作它显示校验错误；
 *    URL 测试用的是 `moe.matsuri.nb4a.ui.UrlTestPreference`，要
 *    `concurrent_layout` / `timeout_layout` / `edit_concurrent` / `edit_timeout`。
 *    这些视图都长在各自的 dialogLayout 里 —— 换成通用布局就会
 *    `NullPointerException: TextInputLayout.setError(...) on a null object reference`。
 *
 * 2. **按钮消失**：按钮由 `AlertController.installContent()` 创建，而它要等
 *    `AlertDialog.onCreate()`（即 `dialog.show()`）之后才跑。在
 *    `onCreateDialog()` 里 `getButton()` 只会拿到 null，然后整段静默跳过。
 *
 * 结论：[androidx.preference.DialogPreference.getDialogLayoutResource] 是
 * **按偏好定制**的（订阅链接、URL 测试各有一套），不能统一替换。
 * 所以这里完全不碰布局，需要什么就在现场处理什么：
 *
 * | 问题 | 现场处理 |
 * |---|---|
 * | 密码/UUID 显示成圆点 | 摘掉 transformationMethod 与密码变体 |
 * | 右上角的密码眼睛 | 关掉外层 TextInputLayout 的 endIconMode |
 * | 圆角外框 | TextInputLayout 的 boxBackgroundMode 设为 NONE |
 * | 单行横向滚动 | setSingleLine(false) 并关掉横向滚动 |
 *
 * 这样只改「显示」这一件事，不动任何偏好自己的视图结构。
 *
 * ## 二、按钮
 *
 * 自带只有「取消 / 确定」。这里往原生按钮栏里补上「清空」与「复制」，
 * 并把「确定」改名为「保存」—— 但**它的点击监听不动**，所以写回逻辑
 * 仍是自带的 `onDialogClosed(true)`，没有自己实现保存。
 *
 * 重排必须放在 [Dialog.setOnShowListener] 里，原因见上面第 2 点。
 */
class CopyableEditTextPreferenceDialog : EditTextPreferenceDialogFragmentCompat() {

    private var editText: EditText? = null

    override fun onBindDialogView(view: View) {
        // 先让基类跑完：它会填文本、定位光标，并执行项目注册的
        // OnBindEditTextListener（Port / Number / Hosts 等输入限制都在那里）。
        // 我们在它之后只调整「显示方式」，不会破坏这些既有的输入限制。
        super.onBindDialogView(view)

        val et = view.findViewById<EditText>(android.R.id.edit) ?: return
        editText = et

        // 1) 直接显示内容，不要圆点。
        //    大部分输入项共用 layout_password_dialog，那里写死了
        //    android:inputType="textPassword"，所以 UUID / 密码全是圆点。
        //    只摘掉密码相关的变体、保留原有类别 —— serverPort、version、
        //    kcpCwndMultiplier 也用同一个布局，不能把它们的数字键盘改成文本。
        val passwordVariations = InputType.TYPE_TEXT_VARIATION_PASSWORD or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val wasPassword = et.transformationMethod is PasswordTransformationMethod ||
                (et.inputType and passwordVariations) != 0
        et.transformationMethod = null
        if (wasPassword) {
            et.inputType = (et.inputType and passwordVariations.inv()) or
                    InputType.TYPE_CLASS_TEXT
        }

        // 2) 外层 TextInputLayout 上的两样东西要关掉：
        //    · endIconMode —— 那个密码眼睛是它画的，只改 EditText 不够；
        //    · boxBackgroundMode = NONE —— 圆角外框也是它画的。
        //      注意外框是设在 EditText **自己的背景**上的，而该方法在 NONE 时
        //      会提前 return、不还原背景，所以还要把背景换回主题的下划线。
        var parent = et.parent
        while (parent is ViewGroup) {
            if (parent is TextInputLayout) {
                parent.endIconMode = TextInputLayout.END_ICON_NONE
                parent.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_NONE
                break
            }
            parent = parent.parent
        }
        // 还原成主题默认的输入框背景（下划线）。
        // 这里必须用 dialog 的 context 解析，不能用 et.context：
        // 输入框在 TextInputLayout 内部，OutlinedBox 会通过 materialThemeOverlay
        // 套一层 ThemeOverlay.MaterialComponents.TextInputEditText，
        // 那里把 editTextBackground 设成了 **@null** —— 用 et.context 解析会拿到
        // null，下划线就没了。dialog 的 context 拿到的是 abc_edit_text_material。
        val themeCtx = dialog?.context ?: context ?: et.context
        val ta = themeCtx.obtainStyledAttributes(
            intArrayOf(android.R.attr.editTextBackground)
        )
        et.background = ta.getDrawable(0)
        ta.recycle()

        // 3) 多行、自动换行。
        //    自带是单行横向滚动：UUID / 订阅链接这类长文本在框里左右跳，
        //    长按选择要复制的区间时很难对齐；整段可见之后就好选了。
        //    数字类（Port / Number）的监听会自己设成 TYPE_CLASS_NUMBER 并调
        //    setSingleLine() —— 那是它们要的单行，这里不再强行改回多行。
        val isNumeric = (et.inputType and InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER
        if (!isNumeric) {
            et.inputType = et.inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            et.setSingleLine(false)
            et.maxLines = 8
            et.setHorizontallyScrolling(false)
            et.isVerticalScrollBarEnabled = true
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as AlertDialog

        // 此刻按钮还不存在（见类注释第 2 点），必须等真正显示之后再重排。
        dialog.setOnShowListener { mountButtons(dialog) }

        return dialog
    }

    /**
     * 把按钮栏重排成：`[取消][清空]  ……  [复制][保存]`
     *
     * 三个原生按钮直接复用，风格天然一致；「复制」由「保存」克隆。
     *
     * 克隆时 **`backgroundTintList` 必须一起复制**：
     * 主题里 `materialButtonStyle` 指着一个实心圆角按钮样式
     * （`Widget.MaterialComponents.Button`，其 `android:background` 是
     * `@empty`，真正的填充色靠 `backgroundTint` 画）。只复制 `background`
     * 挡不住它，按钮就会变成一个灰色药丸 —— 之前就是这么来的。
     */
    private fun mountButtons(dialog: AlertDialog) {
        val cancel = dialog.getButton(DialogInterface.BUTTON_NEGATIVE) ?: return
        val save = dialog.getButton(DialogInterface.BUTTON_POSITIVE) ?: return

        // 按钮栏就是保存按钮的父容器。
        // 不按 id 找：buttonPanel 是包着按钮栏的 ScrollView，而且 appcompat 声明的
        // id 未必解析得到本应用的 R。
        val bar = save.parent as? ViewGroup ?: return

        val edit = editText ?: dialog.findViewById<EditText>(android.R.id.edit) ?: return

        // 占位视图：唯一带配重的子视图（原来的顺序是 取消 / 占位 / 确定）
        val spacer = (0 until bar.childCount)
            .map { bar.getChildAt(it) }
            .firstOrNull {
                (it.layoutParams as? LinearLayout.LayoutParams)?.weight?.let { w -> w > 0f } == true
            }

        val clear = cloneButton(save, R.string.clear).apply {
            setOnClickListener { edit.setText("") }
        }
        val copy = cloneButton(save, R.string.action_copy).apply {
            setOnClickListener { copyToClipboard(edit) }
        }

        cancel.setText(android.R.string.cancel)
        // 只改文案：点击仍走原生确定的监听 → onDialogClosed(true) 写回
        save.setText(R.string.save)

        // 子视图只是从父容器摘掉，layoutParams 还在，可以直接加回去
        bar.removeAllViews()
        bar.addView(cancel)
        bar.addView(clear)
        spacer?.let { bar.addView(it) }
        bar.addView(copy)
        bar.addView(save)
    }

    /** 复制成一个和「保存」外观一致的按钮，免得摆在一起风格不一致 */
    private fun cloneButton(src: Button, textRes: Int): Button =
        AppCompatButton(src.context).apply {
            setText(textRes)
            setTextColor(src.textColors)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, src.textSize)
            typeface = src.typeface
            isAllCaps = src.isAllCaps
            // 关键：填充色是 backgroundTint 画的，必须一起复制
            backgroundTintList = src.backgroundTintList
            background = src.background?.constantState?.newDrawable()?.mutate()
            minWidth = src.minWidth
            minimumWidth = src.minimumWidth
            minHeight = src.minHeight
            minimumHeight = src.minimumHeight
            setPadding(
                src.paddingLeft, src.paddingTop, src.paddingRight, src.paddingBottom
            )
            // 沿用「保存」的尺寸参数，保证在同一行里高矮一致
            layoutParams = (src.layoutParams as? LinearLayout.LayoutParams)
                ?.let { LinearLayout.LayoutParams(it.width, it.height) }
                ?: LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
        }

    private fun copyToClipboard(edit: EditText) {
        val value = edit.text?.toString().orEmpty()
        if (value.isEmpty()) return
        val ok = SagerNet.trySetPrimaryClip(value)
        val ctx = context ?: return
        Toast.makeText(
            ctx,
            if (ok) R.string.copy_success else R.string.copy_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        /**
         * show() 用的 tag。
         *
         * 自己定义而不是引用 AndroidX 的常量 —— 那边
         * `PreferenceDialogFragmentCompat` 并没有公开的 tag 常量
         * （它内部是直接传字面量字符串的），引用一个不存在的字段会编译失败。
         */
        const val DIALOG_TAG = "io.nekohasekai.sagernet.CopyableEditTextPreferenceDialog"

        /**
         * 建一个本类的实例。不能直接用基类的 newInstance()：那个返回基类类型，
         * 上面的显示处理与按钮重排就都不会生效。
         *
         * 另外必须 setTargetFragment —— 基类在 onCreate 里会把 target fragment
         * 当作 DialogPreference.TargetFragment 去查偏好，缺了会直接抛
         * IllegalStateException。
         */
        fun newInstance(key: String, target: Fragment): CopyableEditTextPreferenceDialog =
            CopyableEditTextPreferenceDialog().apply {
                arguments = Bundle().apply { putString(ARG_KEY, key) }
                setTargetFragment(target, 0)
            }
    }
}
