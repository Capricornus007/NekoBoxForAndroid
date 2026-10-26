package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.AdapterView
import android.widget.Spinner
import androidx.preference.PreferenceViewHolder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class OutboundPreference
@JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = R.attr.dropdownPreferenceStyle,
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    companion object {
        const val VALUE_SELECT_PROFILE = "3"
    }

    init {
        setEntries(R.array.outbound_entry)
        setEntryValues(R.array.outbound_value)
        layoutResource = R.layout.preference_dropdown_reselectable
    }

    // getSummary() 由 AndroidX 在主線同步呼叫，而它原本要查 Room（getProfile）。
    // debug 版关了 allowMainThreadQueries → 開路由／分組設定頁即閃退。
    // 名字改在背景取好，主線只讀這個欄位。
    @Volatile
    private var selectedProfileName: String? = null

    private fun refreshSelectedProfileName() {
        if (value != VALUE_SELECT_PROFILE) {
            selectedProfileName = null
            return
        }
        val profileId = DataStore.profileCacheStore.getLong(key + "Long") ?: 0L
        if (profileId <= 0L) {
            selectedProfileName = null
            return
        }
        runOnDefaultDispatcher {
            val name = ProfileManager.getProfile(profileId)?.displayName()
            runOnMainDispatcher {
                if (selectedProfileName != name) {
                    selectedProfileName = name
                    notifyChanged()
                }
            }
        }
    }

    override fun setValue(value: String?) {
        val oldValue = this.value
        super.setValue(value)
        refreshSelectedProfileName()
        if (oldValue == value) {
            notifyChanged()
        }
    }

    private var dropdownOpened = false

    override fun onClick() {
        dropdownOpened = true
        super.onClick()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        dropdownOpened = false
        super.onBindViewHolder(holder)
        refreshSelectedProfileName()

        // findViewById (not ViewBinding): binds into the AndroidX preference-row ViewHolder
        // (holder.itemView), which is not an app layout binding.
        val spinner = holder.itemView.findViewById<Spinner>(R.id.spinner)
        (spinner as? ReselectableSpinner)?.onPopupClosed = { dropdownOpened = false }
        var selectionReady = false
        holder.itemView.post { selectionReady = true }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!selectionReady || position < 0) return
                val newValue = entryValues?.getOrNull(position)?.toString() ?: return
                val reselectedProfile =
                    dropdownOpened && newValue == value && newValue == VALUE_SELECT_PROFILE
                if ((newValue != value || reselectedProfile) && callChangeListener(newValue)) {
                    value = newValue
                }
                dropdownOpened = false
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                dropdownOpened = false
            }
        }
    }

    override fun getSummary(): CharSequence? {
        if (value == VALUE_SELECT_PROFILE) {
            selectedProfileName?.let { return it }
        }
        return super.getSummary()
    }
}
