package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.ktx.Logs

data class TrafficRoutingDiagnostics(
    val defaultMode: TrafficMode,
    val ruleCount: Int,
    val remoteRuleCount: Int,
    val dpiBypassRuleCount: Int,
    val ineffectivePackages: Set<String> = emptySet(),
    val missingPackages: Set<String> = emptySet(),
    val duplicatePackages: Set<String> = emptySet(),
    val invalidPackages: Set<String> = emptySet(),
    val invalidModes: Set<String> = emptySet(),
) {
    val hasIssues: Boolean
        get() = ineffectivePackages.isNotEmpty() ||
            missingPackages.isNotEmpty() ||
            duplicatePackages.isNotEmpty() ||
            invalidPackages.isNotEmpty() ||
            invalidModes.isNotEmpty()
}

object TrafficRoutingDiagnosticsResolver {

    fun snapshot(
        defaultMode: TrafficMode = DataStore.defaultTrafficMode,
        proxyApps: Boolean = DataStore.proxyApps,
        bypass: Boolean = DataStore.bypass,
        individual: String = DataStore.individual,
        ruleReport: AppTrafficRuleValidationReport = DataStore.appTrafficRulesValidationReport,
    ): TrafficRoutingDiagnostics {
        val rules = ruleReport.normalizedRules
        val ineffectivePackages = emptySet<String>()

        return TrafficRoutingDiagnostics(
            defaultMode = defaultMode,
            ruleCount = rules.size,
            remoteRuleCount = rules.count { it.trafficMode == TrafficMode.REMOTE_VPN },
            dpiBypassRuleCount = rules.count { it.trafficMode == TrafficMode.DPI_BYPASS },
            ineffectivePackages = ineffectivePackages,
            missingPackages = ruleReport.missingPackages,
            duplicatePackages = ruleReport.duplicatePackages,
            invalidPackages = ruleReport.invalidPackages,
            invalidModes = ruleReport.invalidModes,
        )
    }

    fun logSnapshot(reason: String, snapshot: TrafficRoutingDiagnostics) {
        Logs.i(
            "Traffic routing[$reason]: defaultMode=${snapshot.defaultMode}, rules=${snapshot.ruleCount}, remoteRules=${snapshot.remoteRuleCount}, dpiRules=${snapshot.dpiBypassRuleCount}, ineffective=${snapshot.ineffectivePackages.size}, missing=${snapshot.missingPackages.size}, duplicates=${snapshot.duplicatePackages.size}, invalidPackages=${snapshot.invalidPackages.size}, invalidModes=${snapshot.invalidModes.size}"
        )
        if (snapshot.ineffectivePackages.isNotEmpty()) {
            Logs.w("Traffic routing[$reason]: ineffectivePackages=${snapshot.ineffectivePackages.sorted()}")
        }
    }
}
