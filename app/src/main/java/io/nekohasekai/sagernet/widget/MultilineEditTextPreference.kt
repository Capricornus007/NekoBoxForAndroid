package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.core.content.res.TypedArrayUtils
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.R

class MultilineEditTextPreference @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context,
        R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle,
    ),
    defStyleRes: Int = 0,
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {
    init {
        dialogLayoutResource = R.layout.preference_dialog_multiline_m3
    }
}
