package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.RulePackages

/**
 * 規則編輯頁上「用正則表達式比對套件名」那一列。
 *
 * 它不另存欄位：條目就住在 [DataStore.routePackages] 這份清單裡，以 `re:` 開頭，跟一般套件名
 * 混在同一串。這裡只負責顯示有幾條正則，點擊後由 [io.nekohasekai.sagernet.ui.RouteSettingsActivity]
 * 彈出編輯對話框。
 */
class PackageRegexPreference : Preference {

    constructor(context: Context) : super(context)
    constructor(context: Context, attrs: AttributeSet?) : super(context, attrs)
    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(
        context,
        attrs,
        defStyle,
    )

    constructor(
        context: Context,
        attrs: AttributeSet?,
        defStyleAttr: Int,
        defStyleRes: Int,
    ) : super(context, attrs, defStyleAttr, defStyleRes)

    override fun getSummary(): CharSequence {
        val regex = RulePackages.regexLines(DataStore.routePackages)
        if (regex.isEmpty()) {
            return context.getString(androidx.preference.R.string.not_set)
        }
        return context.getString(R.string.route_rule_packages_regex_count, regex.size)
    }

    /** 正則條目有增有減時叫一聲，讓這一列的摘要重新算（notifyChanged 是 protected，外面叫不到）。 */
    fun postUpdate() {
        notifyChanged()
    }
}
