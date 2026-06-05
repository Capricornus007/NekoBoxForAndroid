package moe.matsuri.nb4a.ui

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.preference.Preference
import com.boristul.zybcvpn.Key
import com.boristul.zybcvpn.R
import com.boristul.zybcvpn.database.DataStore
import com.boristul.zybcvpn.ktx.Logs
import com.boristul.zybcvpn.ktx.app
import com.boristul.zybcvpn.ui.profile.ConfigEditActivity

class EditConfigPreference : Preference {

    constructor(
        context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(
        context, attrs, defStyleAttr
    )

    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context) : super(context)

    init {
        intent = Intent(context, ConfigEditActivity::class.java)
    }

    var configKey = Key.SERVER_CONFIG
    var useConfigStore = false

    fun useConfigStore(key: String) {
        try {
            this.configKey = key
            useConfigStore = true
            intent = intent!!.apply {
                putExtra("useConfigStore", "1")
                putExtra("key", key)
            }
        } catch (e: Exception) {
            Logs.w(e)
        }
    }

    override fun getSummary(): CharSequence {
        val config =
            (if (useConfigStore) DataStore.configurationStore.getString(configKey) else DataStore.serverConfig)
                ?: ""
        return if (config.isBlank()) {
            return app.resources.getString(androidx.preference.R.string.not_set)
        } else {
            app.resources.getString(R.string.lines, config.split('\n').size)
        }
    }

    public override fun notifyChanged() {
        super.notifyChanged()
    }

}
