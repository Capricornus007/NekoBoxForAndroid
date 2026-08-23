package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import androidx.preference.PreferenceViewHolder
import androidx.preference.R
import androidx.preference.SwitchPreferenceCompat
import io.nekohasekai.sagernet.ktx.resolveStyleAttr

class LongClickSwitchPreference
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = context.resolveStyleAttr(R.attr.switchPreferenceCompatStyle, R.attr.switchPreferenceStyle),
    defStyleRes: Int = 0,
) : SwitchPreferenceCompat(
    context,
    attrs,
    defStyleAttr,
    defStyleRes,
) {
    private var mLongClickListener: View.OnLongClickListener? = null

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        val itemView: View = holder.itemView
        itemView.setOnLongClickListener {
            mLongClickListener?.onLongClick(it) ?: true
        }
    }

    fun setOnLongClickListener(longClickListener: View.OnLongClickListener) {
        this.mLongClickListener = longClickListener
    }
}
