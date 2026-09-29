package io.nekohasekai.sagernet.bg

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Network-based automation: connect, switch profile or disconnect when the underlying network
 * changes. Rules are matched most specific first (SSID, then Wi-Fi, then mobile data). A stop
 * requested by the user pauses connect rules until the user starts the service again; automation
 * never overrides an explicit disconnect.
 *
 * Evaluation runs in whichever process observes the default network: the UI process while the
 * app is alive and the service process while the service runs. Both act on the same persisted
 * state, and the actions are idempotent, so a double evaluation of one change is harmless.
 */
object NetworkAutomation {

    enum class Kind { MOBILE, WIFI, SSID }
    enum class Action { CONNECT, DISCONNECT }

    data class Rule(val kind: Kind, val ssid: String = "", val action: Action, val profileId: Long = -1L) {
        fun toJson(): JSONObject = JSONObject()
            .put("kind", kind.name)
            .put("ssid", ssid)
            .put("action", action.name)
            .put("profileId", profileId)

        companion object {
            fun fromJson(json: JSONObject) = Rule(
                Kind.valueOf(json.getString("kind")),
                json.optString("ssid"),
                Action.valueOf(json.getString("action")),
                json.optLong("profileId", -1L),
            )
        }
    }

    data class Snapshot(val kind: Kind, val ssid: String?)

    fun rules(): List<Rule> {
        val raw = DataStore.configurationStore.getString(Key.NETWORK_AUTOMATION_RULES) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { runCatching { Rule.fromJson(array.getJSONObject(it)) }.getOrNull() }
    }

    fun saveRules(rules: List<Rule>) {
        DataStore.configurationStore.putString(
            Key.NETWORK_AUTOMATION_RULES,
            JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString(),
        )
        lastEvaluated = null
    }

    /** SSID rules win over generic Wi-Fi rules, which win over mobile rules. */
    fun match(rules: List<Rule>, snapshot: Snapshot): Rule? = when (snapshot.kind) {
        Kind.SSID, Kind.WIFI -> {
            val ssid = snapshot.ssid
            rules.firstOrNull { it.kind == Kind.SSID && ssid != null && it.ssid == ssid }
                ?: rules.firstOrNull { it.kind == Kind.WIFI }
        }

        Kind.MOBILE -> rules.firstOrNull { it.kind == Kind.MOBILE }
    }

    fun hasLocationPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /** Current SSID, or null when unavailable (no Wi-Fi, no location permission or hidden by Android). */
    fun currentSsid(context: Context): String? {
        if (!hasLocationPermission(context)) return null
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return null

        @Suppress("DEPRECATION")
        val raw = runCatching { wifi.connectionInfo?.ssid }.getOrNull() ?: return null
        if (raw.isBlank() || raw == WifiManager.UNKNOWN_SSID) return null
        return raw.removeSurrounding("\"")
    }

    fun snapshot(network: Network?): Snapshot? {
        network ?: return null
        val capabilities = runCatching { SagerNet.connectivity.getNetworkCapabilities(network) }.getOrNull() ?: return null
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Snapshot(Kind.WIFI, currentSsid(SagerNet.application))
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Snapshot(Kind.MOBILE, null)
            else -> null
        }
    }

    @Volatile
    private var lastEvaluated: Snapshot? = null

    /** Entry point for the default-network listeners. [serviceRunning] is the caller's process view. */
    fun onNetwork(network: Network?, serviceRunning: Boolean) {
        if (!DataStore.networkAutomation) return
        val snapshot = snapshot(network) ?: return
        if (snapshot == lastEvaluated) return
        lastEvaluated = snapshot
        val rule = match(rules(), snapshot) ?: return
        Logs.d("network automation: $snapshot -> $rule (running=$serviceRunning)")
        when (rule.action) {
            Action.DISCONNECT -> if (serviceRunning) SagerNet.stopService(byUser = false)

            Action.CONNECT -> if (serviceRunning) {
                if (rule.profileId > 0 && rule.profileId != DataStore.currentProfile) SagerNet.reloadService(rule.profileId)
            } else {
                runOnDefaultDispatcher { connectUnlessPaused(rule, snapshot) }
            }
        }
    }

    // A user stop records the pause in the service process, and this process's settings snapshot
    // learns about it asynchronously. Read the database before starting anything, so a network
    // event that lands right after the stop cannot revive the service.
    private suspend fun connectUnlessPaused(rule: Rule, snapshot: Snapshot) {
        DataStore.configurationStore.refreshSuspend()
        // A newer network event was evaluated meanwhile; its own task decides.
        if (lastEvaluated != snapshot) return
        if (DataStore.automationPaused) {
            // Not acted on: forget the snapshot so the same network is re-evaluated once the user
            // starts the service again and the pause lifts.
            lastEvaluated = null
            return
        }
        if (DataStore.serviceState.started) return
        SagerNet.startService(if (rule.profileId > 0) rule.profileId else DataStore.selectedProxy)
    }
}
