package io.nekohasekai.sagernet.database.preference

import android.graphics.Typeface
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.preference.EditTextPreference

object EditTextPreferenceModifiers {
    object Monospace : EditTextPreference.OnBindEditTextListener {
        override fun onBindEditText(editText: EditText) {
            editText.typeface = Typeface.MONOSPACE
        }
    }

    object PasswordPlain : EditTextPreference.OnBindEditTextListener {
        override fun onBindEditText(editText: EditText) {
            // 明文 + 等宽 (ProfileSettingsActivity 的密码类偏好会被拦截走
            // PasswordDialogFragment 自定义弹窗; 此 modifier 服务于其余场景如 WebDAV)
            editText.inputType = EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            editText.typeface = Typeface.MONOSPACE
        }
    }

    object Hosts : EditTextPreference.OnBindEditTextListener {

        override fun onBindEditText(editText: EditText) {
            editText.setHorizontallyScrolling(true)
            editText.setSelection(editText.text.length)
        }
    }

    object Port : EditTextPreference.OnBindEditTextListener {
        private val portLengthFilter = arrayOf(InputFilter.LengthFilter(5))

        override fun onBindEditText(editText: EditText) {
            editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
            editText.filters = portLengthFilter
            editText.setSingleLine()
            editText.setSelection(editText.text.length)
        }
    }

    object Number : EditTextPreference.OnBindEditTextListener {

        override fun onBindEditText(editText: EditText) {
            editText.inputType = EditorInfo.TYPE_CLASS_NUMBER
            editText.setSingleLine()
            editText.setSelection(editText.text.length)
        }
    }
}
