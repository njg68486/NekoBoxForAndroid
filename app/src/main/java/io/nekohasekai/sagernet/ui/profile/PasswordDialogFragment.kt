package io.nekohasekai.sagernet.ui.profile

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.R

/**
 * 密码/uuid 明文编辑弹窗:
 *  - 无输入框边框 (borderless), 等宽字体, 全内容直显, 长内容自动换行 (仅显示)
 *  - 按钮行: 取消(左) | 复制(中, 不关闭) | 确认(右)
 *  - 复制/写回内容均去除显示换行, 保持单行语义
 *
 * 修复闪退: 不再使用 setTargetFragment (target 必须属于同一 FragmentManager,
 * childFragmentManager 与宿主 Fragment 不匹配导致 IllegalStateException)。
 * 数据改为 arguments 直传: key/title/text, 确认后由宿主 Fragment 写回 DataStore。
 */
class PasswordDialogFragment : DialogFragment() {

    companion object {
        private const val ARG_KEY = "key"
        private const val ARG_TITLE = "title"
        private const val ARG_TEXT = "text"

        fun newInstance(key: String, title: CharSequence?, text: String): PasswordDialogFragment {
            val fragment = PasswordDialogFragment()
            fragment.arguments = Bundle().apply {
                putString(ARG_KEY, key)
                putCharSequence(ARG_TITLE, title)
                putString(ARG_TEXT, text)
            }
            return fragment
        }
    }

    private fun rawText(editText: EditText): String =
        (editText.text?.toString() ?: "").replace("\r", "").replace("\n", "")

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val args = requireArguments()
        val key = args.getString(ARG_KEY)!!
        val title = args.getCharSequence(ARG_TITLE)
        val text = args.getString(ARG_TEXT) ?: ""

        val view = LayoutInflater.from(requireContext())
            .inflate(R.layout.layout_password_dialog, null, false)
        val editText = view.findViewById<EditText>(android.R.id.edit)
        editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        editText.typeface = android.graphics.Typeface.MONOSPACE
        // 多行显示 (换行仅显示效果), 复制/保存时去除
        editText.setSingleLine(false)
        editText.maxLines = 8
        editText.setHorizontallyScrolling(false)
        editText.setText(text)
        editText.setSelection(text.length)

        fun copyToClipboard() {
            val cm = requireContext()
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("password", rawText(editText)))
            Toast.makeText(requireContext(), "已复制", Toast.LENGTH_SHORT).show()
        }

        return AlertDialog.Builder(requireActivity())
            .setTitle(title)
            .setView(view)
            // 按钮行 (同一行): 取消(左/neutral) | 复制(中/negative, 不关闭) | 保存(右/positive)
            .setNeutralButton(android.R.string.cancel, null)
            .setNegativeButton(R.string.action_copy) { _, _ -> }
            .setPositiveButton(R.string.save) { _, _ ->
                // 通过 arguments 回传结果, 由宿主 PreferenceFragment 写回 preference
                val raw = rawText(editText)
                parentFragmentManager.setFragmentResult(
                    "password_edit", Bundle().apply {
                        putString(ARG_KEY, key)
                        putString(ARG_TEXT, raw)
                    }
                )
            }
            .create()
            .apply {
                setOnShowListener {
                    // 复制(中)不关闭弹窗
                    getButton(AlertDialog.BUTTON_NEGATIVE)?.setOnClickListener {
                        copyToClipboard()
                    }
                }
            }
    }
}
