package io.nekohasekai.sagernet.bg

import android.util.Log
import io.nekohasekai.sagernet.bg.byedpi.ByeDpiConfig
import io.nekohasekai.sagernet.bg.byedpi.EmbeddedBackend
import io.nekohasekai.sagernet.bg.byedpi.ByeDpiManager
import io.nekohasekai.sagernet.bg.byedpi.ByeDpiStatus
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.AppTrafficRuleValidationReport
import io.nekohasekai.sagernet.database.AppTrafficRuleValidator
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.TrafficMode
import io.nekohasekai.sagernet.database.TrafficRoutingDiagnostics
import io.nekohasekai.sagernet.database.TrafficRoutingDiagnosticsResolver
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UnifiedTunnelStatus(
    val activeMode: TrafficMode? = null,
    val remoteRunning: Boolean = false,
    val splitActive: Boolean = false,
    val byeDpiStatus: ByeDpiStatus = ByeDpiStatus.IDLE,
)

class UnifiedTunnelController(
    private val byeDpiBackend: EmbeddedBackend<ByeDpiConfig, ByeDpiStatus> = ByeDpiManager,
    private val byeDpiErrorProvider: () -> String? = { ByeDpiManager.lastError() },
    private val ruleReportProvider: () -> AppTrafficRuleValidationReport = { DataStore.appTrafficRulesValidationReport },
    private val routingDiagnosticsProvider: (TrafficMode, AppTrafficRuleValidationReport) -> TrafficRoutingDiagnostics =
        { mode, report -> TrafficRoutingDiagnosticsResolver.snapshot(defaultMode = mode, ruleReport = report) },
) {

    companion object {
        private const val TAG = "UnifiedTunnelCtrl"
        private val globalStatus = MutableStateFlow(UnifiedTunnelStatus())

        val status: StateFlow<UnifiedTunnelStatus> = globalStatus.asStateFlow()
        fun statusSnapshot(): UnifiedTunnelStatus = globalStatus.value
    }

    private val mutableStatus = MutableStateFlow(UnifiedTunnelStatus())
    val localStatus: StateFlow<UnifiedTunnelStatus> = mutableStatus.asStateFlow()

    private var activeMode: TrafficMode? = null
    private var remoteRunning = false
    private var splitActive = false
    private var lastError: String? = null

    fun lastErrorMessage(): String? = lastError

    fun start(mode: TrafficMode, remote: ProxyInstance?): Boolean {
        lastError = null
        val ruleReport = ruleReportProvider()
        AppTrafficRuleValidator.logReport("startup", ruleReport)
        TrafficRoutingDiagnosticsResolver.logSnapshot(
            "startup",
            routingDiagnosticsProvider(mode, ruleReport)
        )
        val explicitModes = ruleReport.normalizedRules.map { it.trafficMode }.toSet()
        val usesExplicitRules = explicitModes.isNotEmpty()
        val needRemote = TrafficMode.REMOTE_VPN in explicitModes
        val needByeDpi = TrafficMode.DPI_BYPASS in explicitModes
        val needsSplit = if (usesExplicitRules) needRemote && needByeDpi else ruleReport.normalizedRules.any { it.trafficMode != mode }
        Logs.i("Unified runtime[start]: defaultMode=$mode, needsSplit=$needsSplit, remoteAvailable=${remote != null}, rules=${ruleReport.normalizedRules.size}, explicitModes=$explicitModes")
        Log.i(TAG, "start defaultMode=$mode needsSplit=$needsSplit remoteAvailable=${remote != null} rules=${ruleReport.normalizedRules.size} explicitModes=$explicitModes")

        if (usesExplicitRules) {
            splitActive = needRemote && needByeDpi
            Logs.i("Unified runtime[start]: backendSelection explicit remote=$needRemote byedpi=$needByeDpi")
            Log.i(TAG, "backendSelection explicit remote=$needRemote byedpi=$needByeDpi")

            if (needRemote && !startRemote(remote)) {
                Logs.e("Unified runtime[start]: remote backend failed during explicit-rule startup")
                Log.e(TAG, "remote backend failed during explicit-rule startup error=${lastError ?: "unknown"}")
                return false
            }

            if (needByeDpi && !startByeDpi()) {
                Logs.e("Unified runtime[start]: ByeDPI backend failed during explicit-rule startup")
                Log.e(TAG, "ByeDPI backend failed during explicit-rule startup error=${lastError ?: "unknown"}")
                if (remoteRunning) {
                    remote?.close()
                    remoteRunning = false
                }
                splitActive = false
                activeMode = null
                updateStatus(null, false, false, byeDpiBackend.status.value)
                return false
            }

            activeMode = when {
                needRemote && needByeDpi -> mode
                needRemote -> TrafficMode.REMOTE_VPN
                needByeDpi -> TrafficMode.DPI_BYPASS
                else -> null
            }
            updateStatus(activeMode, remoteRunning, splitActive, byeDpiBackend.status.value)
            return true
        }

        splitActive = false
        return when (mode) {
            TrafficMode.REMOTE_VPN -> startRemote(remote)
            TrafficMode.DPI_BYPASS -> {
                Logs.i("Unified runtime[start]: backendSelection=DPI_BYPASS")
                Log.i(TAG, "backendSelection=DPI_BYPASS")
                if (startByeDpi()) {
                    true
                } else {
                    Logs.e("Unified runtime[start]: ByeDPI start failed while default mode is DPI_BYPASS")
                    Log.e(TAG, "ByeDPI start failed in default DPI_BYPASS mode error=${lastError ?: "unknown"}")
                    false
                }
            }
        }
    }

    fun stop() {
        Logs.i("Unified runtime[stop]: activeMode=$activeMode, remoteRunning=$remoteRunning, splitActive=$splitActive, byeDpiStatus=${byeDpiBackend.status.value}")
        Log.i(TAG, "stop activeMode=$activeMode remoteRunning=$remoteRunning splitActive=$splitActive byeDpiStatus=${byeDpiBackend.status.value}")
        updateStatus(activeMode, remoteRunning, splitActive, byeDpiBackend.status.value)

        // Safe shutdown ordering for future mixed-mode work: ByeDPI first, remote second.
        if (byeDpiBackend.status.value == ByeDpiStatus.RUNNING ||
            byeDpiBackend.status.value == ByeDpiStatus.STARTING ||
            byeDpiBackend.status.value == ByeDpiStatus.FAILED
        ) {
            byeDpiBackend.stop(force = byeDpiBackend.status.value == ByeDpiStatus.FAILED)
        }

        remoteRunning = false
        splitActive = false
        activeMode = null
        updateStatus(null, false, false, byeDpiBackend.status.value)
        Logs.i("Unified runtime[stop]: complete")
        Log.i(TAG, "stop complete")
    }

    private fun startRemote(remote: ProxyInstance?): Boolean {
        if (remote == null) {
            lastError = "Remote runtime is unavailable"
            Logs.e("Unified runtime[startRemote]: error=$lastError")
            Log.e(TAG, "startRemote failed: $lastError")
            return false
        }

        if (!remote.isInitialized()) {
            lastError = "Remote runtime is not initialized"
            Logs.e("Unified runtime[startRemote]: error=$lastError")
            Log.e(TAG, "startRemote failed: $lastError")
            return false
        }

        return runCatching {
            remote.launch()
            remoteRunning = true
            Logs.i("Unified runtime[startRemote]: state=started")
            Log.i(TAG, "REMOTE_VPN backend started")
            if (!splitActive) activeMode = TrafficMode.REMOTE_VPN
            updateStatus(activeMode, true, splitActive, byeDpiBackend.status.value)
            true
        }.getOrElse { error ->
            remoteRunning = false
            lastError = "Remote runtime failed to start: ${error.readableMessage}"
            Logs.e("Unified runtime[startRemote]: error=$lastError", error)
            Log.e(TAG, "startRemote exception: $lastError", error)
            false
        }
    }

    private fun startByeDpi(): Boolean {
        if (DataStore.byeDpiPort == DataStore.mixedPort) {
            lastError = "ByeDPI port ${DataStore.byeDpiPort} conflicts with proxy port ${DataStore.mixedPort}"
            Logs.e("Unified runtime[startByeDpi]: error=$lastError")
            Log.e(TAG, "startByeDpi failed: $lastError")
            return false
        }

        val config = ByeDpiConfig(listenPort = DataStore.byeDpiPort)
        val started = byeDpiBackend.start(config)
        if (!started) {
            lastError = byeDpiErrorProvider()
                ?: "ByeDPI backend failed to start on ${config.listenAddress}:${config.listenPort}"
            Logs.e("Unified runtime[startByeDpi]: error=$lastError")
            Log.e(TAG, "startByeDpi failed: $lastError")
            return false
        }

        Logs.i("Unified runtime[startByeDpi]: state=started, listen=${config.listenAddress}:${config.listenPort}")
        Log.i(TAG, "DPI_BYPASS backend started listen=${config.listenAddress}:${config.listenPort}")
        if (!splitActive) {
            activeMode = TrafficMode.DPI_BYPASS
            remoteRunning = false
        }
        updateStatus(activeMode, remoteRunning, splitActive, byeDpiBackend.status.value)
        return true
    }

    private fun updateStatus(
        activeMode: TrafficMode?,
        remoteRunning: Boolean,
        splitActive: Boolean,
        byeDpiStatus: ByeDpiStatus,
    ) {
        val status = UnifiedTunnelStatus(activeMode, remoteRunning, splitActive, byeDpiStatus)
        mutableStatus.value = status
        globalStatus.value = status
    }
}
