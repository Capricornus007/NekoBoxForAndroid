package io.nekohasekai.sagernet.api

import android.content.Intent
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.*
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.lang.ref.WeakReference

internal object ApiUi {
    private var current = WeakReference<MainActivity>(null)
    fun attach(activity: MainActivity) {
        current = WeakReference(activity)
    }

    private val pages = mapOf(
        "profiles" to R.id.nav_configuration,
        "groups" to R.id.nav_group,
        "routes" to R.id.nav_route,
        "settings" to R.id.nav_settings,
        "connections" to R.id.nav_connections,
        "tools" to R.id.nav_tools,
        "logs" to R.id.nav_logcat,
    )
    private val activities = mapOf(
        "assets" to AssetsActivity::class.java,
        "routing" to RoutingProfilesActivity::class.java,
        "automation" to NetworkAutomationActivity::class.java,
        "sharing" to ShareConnectionActivity::class.java,
        "apps" to AppManagerActivity::class.java,
    )

    suspend fun status() = withContext(Dispatchers.Main) {
        val activity = current.get()?.takeUnless { it.isFinishing || it.isDestroyed }
        JSONObject().put("available", activity != null).put("focused", activity?.hasWindowFocus() == true)
            .put("screens", JSONArray((pages.keys + activities.keys).toList()))
    }

    suspend fun navigate(screen: String) = withContext(Dispatchers.Main) {
        val activity = current.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: reject("ui_unavailable", "Launch MainActivity before navigating")
        val page = pages[screen]
        val target = activities[screen]
        requireApi(page != null || target != null, "Unknown screen")
        if (page != null) {
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            activity.displayFragmentWithId(page)
        } else {
            activity.startActivity(Intent(activity, target))
        }
        JSONObject().put("screen", screen)
    }

    suspend fun recreate() = withContext(Dispatchers.Main) {
        val activity = current.get()?.takeUnless { it.isFinishing || it.isDestroyed }
            ?: reject("ui_unavailable", "Launch MainActivity before recreating the screen")
        Theme.currentNightMode = DataStore.nightTheme
        Theme.apply(SagerNet.application)
        Theme.applyNightTheme()
        AppLocale.apply()
        activity.recreate()
        JSONObject().put("accepted", true)
    }
}
