package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.database.AppTrafficRuleValidator
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.TrafficMode
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.PackageCache

enum class TrafficModeDecisionSource {
    PACKAGE_RULE,
    UID_RULE,
    DEFAULT,
}

data class TrafficModeDecision(
    val mode: TrafficMode,
    val source: TrafficModeDecisionSource,
    val packageName: String? = null,
    val uid: Int? = null,
    val matchedPackageName: String? = null,
)

data class EffectiveTrafficRule(
    val packageName: String,
    val uid: Int,
    val decision: TrafficModeDecision,
)

object TrafficModePolicyResolver {

    internal fun resolveWithInputs(
        defaultMode: TrafficMode,
        rules: Map<String, TrafficMode>,
        packageName: String? = null,
        uid: Int? = null,
        packagesForUid: (Int) -> Set<String> = { emptySet() },
    ): TrafficModeDecision {
        val normalizedPackage = packageName?.trim().orEmpty().ifBlank { null }

        if (normalizedPackage != null) {
            rules[normalizedPackage]?.let { mode ->
                return TrafficModeDecision(
                    mode = mode,
                    source = TrafficModeDecisionSource.PACKAGE_RULE,
                    packageName = normalizedPackage,
                    uid = uid,
                    matchedPackageName = normalizedPackage,
                )
            }
        }

        if (uid != null && uid > 1000) {
            val packages = packagesForUid(uid).sorted()
            if (packages.isNotEmpty()) {
                val matchedRules = packages.mapNotNull { candidate ->
                    rules[candidate]?.let { candidate to it }
                }
                if (matchedRules.isNotEmpty()) {
                    val distinctModes = matchedRules.map { it.second }.distinct()
                    if (distinctModes.size == 1) {
                        val matchedPackage = matchedRules.first().first
                        return TrafficModeDecision(
                            mode = distinctModes.first(),
                            source = TrafficModeDecisionSource.UID_RULE,
                            packageName = normalizedPackage ?: matchedPackage,
                            uid = uid,
                            matchedPackageName = matchedPackage,
                        )
                    }
                    Logs.w(
                        "TrafficModePolicyResolver: conflicting rules for uid=$uid packages=$packages modes=$distinctModes, falling back to default"
                    )
                }
            }
        }

        return TrafficModeDecision(
            mode = defaultMode,
            source = TrafficModeDecisionSource.DEFAULT,
            packageName = normalizedPackage,
            uid = uid,
            matchedPackageName = normalizedPackage,
        )
    }

    fun resolve(packageName: String? = null, uid: Int? = null): TrafficModeDecision {
        PackageCache.awaitLoadSync()

        val rules = DataStore.appTrafficRulesValidationReport.normalizedRules.associate {
            it.packageName to it.trafficMode
        }
        return resolveWithInputs(
            defaultMode = DataStore.defaultTrafficMode,
            rules = rules,
            packageName = packageName,
            uid = uid,
            packagesForUid = { PackageCache[it].orEmpty() }
        )
    }

    fun logDecision(reason: String, decision: TrafficModeDecision) {
        Logs.d(
            "Traffic policy[$reason]: mode=${decision.mode}, source=${decision.source}, package=${decision.packageName}, uid=${decision.uid}, matched=${decision.matchedPackageName}"
        )
    }

    fun effectiveRules(): List<EffectiveTrafficRule> {
        PackageCache.awaitLoadSync()
        val validation = DataStore.appTrafficRulesValidationReport
        AppTrafficRuleValidator.logReport("effectiveRules", validation)

        return validation.normalizedRules.mapNotNull { rule ->
            val packageName = rule.packageName.trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val uid = PackageCache[packageName]?.takeIf { it >= 1000 } ?: run {
                Logs.w("Traffic policy[effectiveRules]: ignoring missing app package=$packageName mode=${rule.trafficMode}")
                return@mapNotNull null
            }
            EffectiveTrafficRule(
                packageName = packageName,
                uid = uid,
                decision = resolve(packageName = packageName, uid = uid)
            )
        }.distinctBy { it.packageName }
    }
}
