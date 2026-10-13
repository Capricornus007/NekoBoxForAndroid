package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.RulePackages

class AppListPreference : Preference {

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
        val packages = DataStore.routePackages.split("\n").filter { it.isNotBlank() }.map {
            if (RulePackages.isRegexEntry(it)) {
                // 「re:」條目不是真實套件名，查不到 App 標題；直接標示成正則，
                // 免得清單看起來像壞掉的套件名。
                context.getString(R.string.rule_packages_regex_item, RulePackages.patternOf(it))
            } else {
                PackageCache.installedPackages[it]?.applicationInfo?.loadLabel(app.packageManager)
                    ?: PackageCache.installedPluginPackages[it]?.applicationInfo?.loadLabel(app.packageManager)
                    ?: it
            }
        }
        if (packages.isEmpty()) {
            return context.getString(androidx.preference.R.string.not_set)
        }
        val count = packages.size
        if (count <= 5) return packages.joinToString("\n")
        return context.getString(R.string.apps_message, count)
    }

    fun postUpdate() {
        notifyChanged()
    }
}
