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

    object Hosts : EditTextPreference.OnBindEditTextListener {

        override fun onBindEditText(editText: EditText) {
            editText.setHorizontallyScrolling(true)
            editText.setSelection(editText.text.length)
        }
    }

    /** 多行純文字（如 `[Peer]` 區塊）：換行要留得下來，所以關閉單行與橫向捲動。 */
    object Multiline : EditTextPreference.OnBindEditTextListener {

        override fun onBindEditText(editText: EditText) {
            editText.inputType = EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
            editText.typeface = Typeface.MONOSPACE
            editText.setHorizontallyScrolling(false)
            editText.minLines = 4
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
