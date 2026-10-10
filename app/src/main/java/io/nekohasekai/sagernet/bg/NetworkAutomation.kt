package io.nekohasekai.sagernet.bg

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import org.json.JSONArray
import org.json.JSONObject

/**
 * Network-based automation: connect, switch profile or disconnect when the underlying network
 * changes. A Wi-Fi name (SSID) rule beats a Wi-Fi rule; Ethernet and mobile data rules match their
 * own networks.
 *
 * Only the service process evaluates rules. [NetworkAutomationService] watches whenever a connect
 * rule could act, and the running VPN or proxy service watches too. Both feed one [Tracker], so a
 * network is acted on once however many callbacks report it.
 *
 * User actions win over rules: a manual stop, or Android revoking the VPN, pauses connect rules until
 * the next manual start, and a manual start holds until the underlying network changes. Rules hold
 * back while Android reports another VPN.
 */
object NetworkAutomation {

    enum class Kind { MOBILE, WIFI, SSID, ETHERNET }
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

    /** The underlying network as rules see it: [ssid] is null for Wi-Fi when Android hides the name. */
    data class Snapshot(val kind: Kind, val ssid: String?)

    sealed interface Command {
        /** Start the stopped service; a [profileId] of 0 or less means the selected profile. */
        data class Start(val profileId: Long) : Command

        data class Switch(val profileId: Long) : Command

        data object Stop : Command

        /**
         * Leave the stopped service stopped: Android hides the Wi-Fi name, and the network might be
         * one a Wi-Fi name rule disconnects on. The device stays unprotected on this network.
         */
        data object StayOff : Command
    }

    /**
     * Why automation could not act. A refusal is shown until the step Android or the app refused
     * works, or the user switches automation. [serviceStart] marks refusals of a service start.
     */
    enum class Blocked(val message: Int, val serviceStart: Boolean = true) {
        /** Android refused to start the watcher in the background. */
        WATCHER_START(R.string.network_automation_blocked_watcher, serviceStart = false),
        BACKGROUND_START(R.string.network_automation_blocked_background),
        VPN_PERMISSION(R.string.network_automation_blocked_vpn),
        OTHER_VPN(R.string.network_automation_blocked_other_vpn),
    }

    /**
     * What automation already did about the current network. Callbacks repeat, both watchers report
     * the same change, and an automated stop or a failed start returns the service to Stopped on the
     * same network; none of these may act twice. Confined to the main thread.
     */
    class Tracker {
        private var handled: Triple<Long, Snapshot?, Rule?>? = null
        private var held: Long? = null
        private var holdNext = false

        /** Bumped whenever a decision is made or dropped, so a start decided earlier can tell it is outdated. */
        var generation = 0L
            private set

        /** The last new report left a stopped service off because the Wi-Fi name was hidden. */
        var unprotected = false
            private set

        /** A report came while the service was between states; it is decided once the service settles. */
        var deferred = false
            private set

        /** A manual start: the network it runs on is left alone until that network changes. */
        fun userStarted(network: Long? = null) {
            holdNext = network == null
            held = network
            unprotected = false
        }

        /**
         * A manual stop, or Android revoking the VPN: the pause it sets replaces the hold of the manual
         * session, and a start decided earlier must not happen any more.
         */
        fun userStopped() {
            holdNext = false
            held = null
            invalidate()
        }

        /** A start decided earlier must not happen any more. */
        fun invalidate() {
            generation++
        }

        /** The current network is decided anew on its next report; the hold of a running manual session stays. */
        fun reevaluate() {
            handled = null
            generation++
        }

        /**
         * One report of the underlying network: [network] is its handle, or null when there is none.
         * Returns what the matched rule asks for, or null when there is nothing new to do. Nothing is
         * decided while the service is not [settled], during a teardown or before a restart's new
         * start: a decision there would see a stopped service that is about to run again.
         */
        fun next(
            network: Long?,
            snapshot: Snapshot?,
            rules: List<Rule>,
            state: BaseService.State,
            settled: Boolean,
            runningProfile: Long,
            alwaysOn: () -> Boolean,
        ): Command? {
            if (network == null) {
                handled = null
                held = null
                unprotected = false
                deferred = false
                invalidate()
                return null
            }
            if (holdNext) {
                holdNext = false
                held = network
            }
            if (!settled) {
                deferred = true
                return null
            }
            deferred = false
            val rule = snapshot?.let { match(rules, it) }
            val key = Triple(network, snapshot, rule)
            // Metadata updates of a held network, such as its Wi-Fi name becoming visible, are not
            // a change of network.
            if (held == network) {
                handled = key
                return null
            }
            held = null
            if (key == handled) return null
            handled = key
            generation++
            val command = if (snapshot != null && rule != null) {
                decide(rules, snapshot, rule, state.started, runningProfile, rule.action == Action.DISCONNECT && alwaysOn())
            } else {
                null
            }
            unprotected = command == Command.StayOff
            return command
        }
    }

    fun parseRules(raw: String?): List<Rule> {
        val array = raw?.let { runCatching { JSONArray(it) }.getOrNull() } ?: return emptyList()
        // A rule this version does not know (or cannot read) is skipped rather than failing the rest.
        return (0 until array.length()).mapNotNull { runCatching { Rule.fromJson(array.getJSONObject(it)) }.getOrNull() }
    }

    fun rules(): List<Rule> = parseRules(DataStore.configurationStore.getString(Key.NETWORK_AUTOMATION_RULES))

    fun saveRules(rules: List<Rule>) {
        DataStore.configurationStore.putString(
            Key.NETWORK_AUTOMATION_RULES,
            JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString(),
        )
    }

    /** SSID rules win over generic Wi-Fi rules; Ethernet and mobile rules match their own networks. */
    fun match(rules: List<Rule>, snapshot: Snapshot): Rule? = when (snapshot.kind) {
        Kind.SSID, Kind.WIFI -> {
            val ssid = snapshot.ssid
            rules.firstOrNull { it.kind == Kind.SSID && ssid != null && it.ssid == ssid }
                ?: rules.firstOrNull { it.kind == Kind.WIFI }
        }

        Kind.MOBILE, Kind.ETHERNET -> rules.firstOrNull { it.kind == snapshot.kind }
    }

    /**
     * What [rule] asks of the service. When Android hides the Wi-Fi name while SSID rules exist, the
     * network may be one of theirs. A Wi-Fi rule then never stops or switches a running service, and
     * starts a stopped one only when no SSID rule disconnects. An always-on VPN is never stopped: the
     * system would restart it, or with lockdown block all traffic.
     */
    fun decide(rules: List<Rule>, snapshot: Snapshot, rule: Rule, running: Boolean, runningProfile: Long, alwaysOn: Boolean): Command? {
        val nameHidden = rule.kind == Kind.WIFI && snapshot.ssid == null && rules.any { it.kind == Kind.SSID }
        return when {
            rule.action == Action.DISCONNECT -> Command.Stop.takeIf { running && !nameHidden && !alwaysOn }
            running -> Command.Switch(rule.profileId).takeIf { !nameHidden && rule.profileId > 0 && rule.profileId != runningProfile }
            nameHidden && rules.any { it.kind == Kind.SSID && it.action == Action.DISCONNECT } -> Command.StayOff
            else -> Command.Start(rule.profileId)
        }
    }

    /** The watcher runs while a connect rule could act: automation is on and no manual stop paused it. */
    fun monitorWanted(
        enabled: Boolean = DataStore.networkAutomation,
        paused: Boolean = DataStore.automationPaused,
        rules: List<Rule> = rules(),
    ) = enabled && !paused && rules.any { it.action == Action.CONNECT }

    /** A wired link is what the device uses when it reports one, whatever else the network claims. */
    fun kindOf(capabilities: NetworkCapabilities): Kind? = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Kind.ETHERNET
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Kind.WIFI
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Kind.MOBILE
        else -> null
    }

    fun hasLocationPermission(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    /**
     * Current SSID, or null when unavailable: no Wi-Fi, no location permission, location turned off,
     * or Android 10 and later with no app screen open, since background location is not requested.
     */
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
        return when (val kind = kindOf(capabilities)) {
            null -> null
            Kind.WIFI -> Snapshot(kind, currentSsid(SagerNet.application))
            else -> Snapshot(kind, null)
        }
    }

    var blocked: Blocked?
        get() = Blocked.entries.firstOrNull { it.name == DataStore.networkAutomationBlocked }
        set(value) {
            val name = value?.name.orEmpty()
            if (DataStore.networkAutomationBlocked == name) return
            DataStore.networkAutomationBlocked = name
            NetworkAutomationService.refresh()
        }

    /**
     * Why a start that is not manual, by a rule or at boot, must not happen: the VPN permission is
     * missing, and it is never asked for from the background, or Android reports another VPN, which
     * the start could take over. That VPN can also belong to another user or a work profile; then it
     * only holds the start back.
     */
    fun unattendedStartBlocked(): Blocked? = when {
        DataStore.serviceMode != Key.MODE_VPN -> null
        !vpnPermitted(SagerNet.application) -> Blocked.VPN_PERMISSION
        otherVpnActive() -> Blocked.OTHER_VPN
        else -> null
    }

    /**
     * Android reports a VPN that is not this app's own. The list of networks can include VPNs of other
     * users and work profiles, and only from Android 11 does Android tell an app which VPN is its own;
     * a VPN whose owner it does not reveal counts. Before Android 11 any VPN counts, this app's own one
     * included while it shuts down. When the networks cannot be read, a VPN is assumed.
     */
    fun otherVpnActive(): Boolean {
        val connectivity = SagerNet.connectivity

        @Suppress("DEPRECATION") // the only call that lists every network at once
        val networks = runCatching { connectivity.allNetworks }.getOrNull() ?: return true
        return networks.any { network ->
            val capabilities = runCatching { connectivity.getNetworkCapabilities(network) }.getOrNull()
            if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                false
            } else if (Build.VERSION.SDK_INT >= 30) {
                capabilities.ownerUid != Process.myUid()
            } else {
                true
            }
        }
    }

    /**
     * Another app's VPN carries this app's traffic: the default network of this app is a VPN it does not
     * own. VPNs of other users and work profiles never carry it. Only Android 11 and later tell whose a
     * VPN is, so before that this is never known; nor does a VPN of the same user show that leaves this
     * app out.
     */
    fun otherVpnCarriesThisApp(): Boolean {
        if (Build.VERSION.SDK_INT < 30) return false
        val connectivity = SagerNet.connectivity
        val capabilities = runCatching { connectivity.getNetworkCapabilities(connectivity.activeNetwork) }.getOrNull() ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) && capabilities.ownerUid != Process.myUid()
    }

    // ---- service process, main thread ----

    private val tracker = Tracker()
    private var lastNetwork: Network? = null
    private var serviceSettled = true
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    val unprotected get() = tracker.unprotected

    /** A watcher saw the underlying network change or refresh, on whichever thread delivers callbacks. */
    fun onNetwork(network: Network?) = runOnMainDispatcher {
        lastNetwork = network
        evaluate()
    }

    /**
     * The service changed state. It is not [settled] during a teardown, nor once stopped while a
     * restart is on its way; a report that came meanwhile is decided when it settles again.
     */
    fun onServiceState(settled: Boolean) {
        serviceSettled = settled
        if (DataStore.serviceState == BaseService.State.Connected &&
            (blocked == Blocked.VPN_PERMISSION || blocked == Blocked.OTHER_VPN)
        ) {
            blocked = null
        }
        // Posted: entering Connecting, the service registers its stop receiver right afterwards.
        if (settled && tracker.deferred) mainHandler.post { if (serviceSettled) evaluate() }
    }

    /** A service start; a manual one holds the current network. */
    fun onServiceStart(byUser: Boolean) {
        if (!byUser) return
        val unprotected = tracker.unprotected
        tracker.userStarted(lastNetwork?.networkHandle)
        if (unprotected) NetworkAutomationService.refresh()
    }

    /**
     * A manual stop, or Android revoking the VPN because the user disconnected it or another VPN app
     * took over: connect rules pause until the next manual start, the hold of the manual session ends,
     * and a start already decided is dropped.
     */
    fun onUserStop(context: Context) {
        DataStore.automationPaused = true
        tracker.userStopped()
        NetworkAutomationService.sync(context)
    }

    /** The current network is decided anew on its next report. */
    fun reevaluate() = tracker.reevaluate()

    /** Another process changed the automation settings: read them, then decide the current network. */
    fun onRecheck(reevaluate: Boolean) = runOnDefaultDispatcher {
        runCatching { DataStore.configurationStore.refreshSuspend() }.onFailure { Logs.w("network automation: settings unreadable", it) }
        onMainDispatcher {
            if (reevaluate) tracker.reevaluate()
            evaluate()
        }
    }

    private fun evaluate() {
        if (!DataStore.networkAutomation) return
        val network = lastNetwork
        val snapshot = snapshot(network)
        val unprotected = tracker.unprotected
        val command = tracker.next(
            network?.networkHandle,
            snapshot,
            rules(),
            DataStore.serviceState,
            serviceSettled,
            DataStore.selectedProxy,
            ::alwaysOnVpn,
        )
        if (tracker.unprotected != unprotected) NetworkAutomationService.refresh()
        command ?: return
        Logs.d("network automation: $snapshot -> $command")
        when (command) {
            Command.Stop -> SagerNet.stopService(byUser = false)

            // A service started at boot while a manual stop paused connect rules runs as it is.
            is Command.Switch -> if (!DataStore.automationPaused) SagerNet.reloadService(command.profileId)

            is Command.Start -> {
                val generation = tracker.generation
                runOnDefaultDispatcher { start(command.profileId, generation) }
            }

            Command.StayOff -> Unit
        }
    }

    // Settings written by the app's other process may not have reached this one yet, so the
    // database is read before starting anything: a pause recorded by a manual stop must win.
    private suspend fun start(profileId: Long, generation: Long) {
        try {
            DataStore.configurationStore.refreshSuspend()
        } catch (e: Exception) {
            Logs.w("network automation: settings unreadable", e)
            return
        }
        val id = profileId.takeIf { it > 0 } ?: DataStore.selectedProxy
        // Checked on the main thread, where stops and state changes happen, so none slips in between.
        onMainDispatcher {
            if (generation != tracker.generation || !DataStore.networkAutomation || DataStore.automationPaused) return@onMainDispatcher
            if (DataStore.serviceState.started || !serviceSettled || id <= 0) return@onMainDispatcher
            unattendedStartBlocked()?.let {
                blocked = it
                return@onMainDispatcher
            }
            try {
                SagerNet.startService(id, byUser = false)
                // Android accepted the start; the VPN service reports a refusal of its own again.
                if (blocked?.serviceStart == true) blocked = null
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: Android allows background starts only
                // with exemptions such as unrestricted battery use.
                Logs.w("network automation: start refused", e)
                blocked = Blocked.BACKGROUND_START
            }
        }
    }

    // The VPN permission as granted by the user, read without VpnService.prepare(), which would take
    // over another app's running VPN.
    private fun vpnPermitted(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                appOps.unsafeCheckOpNoThrow(OPSTR_ACTIVATE_VPN, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(OPSTR_ACTIVATE_VPN, Process.myUid(), context.packageName)
            }
        }.getOrNull() == AppOpsManager.MODE_ALLOWED
    }

    // AppOpsManager.OPSTR_ACTIVATE_VPN, which the SDK does not expose.
    private const val OPSTR_ACTIVATE_VPN = "android:activate_vpn"

    // A bare VpnService answers for this app through a system binder, as the protection settings do.
    // Android 9 and older have no such query, so an always-on VPN goes unnoticed there.
    private fun alwaysOnVpn() = Build.VERSION.SDK_INT >= 29 && runCatching { android.net.VpnService().isAlwaysOn }.getOrDefault(false)

    // ---- any process ----

    /** Settings just written here reach the service process: wait until they land, then sync the watcher. */
    fun syncWatcher(context: Context, reevaluate: Boolean = false) = runOnDefaultDispatcher {
        runCatching { DataStore.configurationStore.awaitWrites() }.onFailure { Logs.w("network automation: settings not saved", it) }
        NetworkAutomationService.sync(context, reevaluate)
    }

    /** The rules changed. */
    fun onRulesChanged(context: Context) = syncWatcher(context)

    /**
     * The user switched automation on or off: an earlier pause or refusal no longer applies. Switched
     * on, the current network is decided anew, unless a manual session still runs on it.
     */
    fun onSwitched(context: Context, enabled: Boolean) {
        DataStore.networkAutomation = enabled
        DataStore.automationPaused = false
        blocked = null
        BootReceiver.enabled = BootReceiver.wanted
        syncWatcher(context, reevaluate = enabled)
    }

    /** What the settings show under the automation switch: a refusal, a pause, or what it does. */
    fun status(): Int = blocked?.message
        ?: if (DataStore.networkAutomation && DataStore.automationPaused) R.string.network_automation_paused else R.string.network_automation_summary

    /**
     * A manual start lifts the pause a manual stop set. It also grants a missing VPN permission, or
     * takes over another VPN on purpose.
     */
    fun onUserStart(context: Context) {
        DataStore.automationPaused = false
        if (blocked == Blocked.VPN_PERMISSION || blocked == Blocked.OTHER_VPN) blocked = null
        syncWatcher(context)
    }
}
