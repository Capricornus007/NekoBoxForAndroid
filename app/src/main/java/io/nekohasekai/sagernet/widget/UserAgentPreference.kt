package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.resolveStyleAttr

class UserAgentPreference
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = context.resolveStyleAttr(R.attr.editTextPreferenceStyle, android.R.attr.editTextPreferenceStyle),
) : EditTextPreference(context, attrs, defStyle) {

    public override fun notifyChanged() {
        super.notifyChanged()
    }

    override fun getSummary(): CharSequence? {
        if (text.isNullOrBlank()) {
            return USER_AGENT
        }
        return super.getSummary()
    }
}
