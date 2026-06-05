package com.boristul.zybcvpn.database

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import com.boristul.zybcvpn.ktx.Logs
import com.boristul.zybcvpn.utils.PackageCache
import moe.matsuri.nb4a.utils.JavaUtil

enum class TrafficMode {
    REMOTE_VPN,
    DPI_BYPASS,
}

data class AppTrafficRule(
    val packageName: String,
    val trafficMode: TrafficMode,
)

data class AppTrafficRuleValidationReport(
    val normalizedRules: List<AppTrafficRule> = emptyList(),
    val duplicatePackages: Set<String> = emptySet(),
    val invalidPackages: Set<String> = emptySet(),
    val invalidModes: Set<String> = emptySet(),
    val missingPackages: Set<String> = emptySet(),
    val parseError: String? = null,
) {
    val hasIssues: Boolean
        get() = duplicatePackages.isNotEmpty() ||
            invalidPackages.isNotEmpty() ||
            invalidModes.isNotEmpty() ||
            missingPackages.isNotEmpty() ||
            parseError != null
}

object AppTrafficRuleValidator {

    private val packageNamePattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun validateSerialized(raw: String?, checkInstalledApps: Boolean = true): AppTrafficRuleValidationReport {
        if (raw.isNullOrBlank()) return AppTrafficRuleValidationReport()

        return runCatching {
            val decoded = JavaUtil.gson.fromJson(raw, JsonArray::class.java) ?: JsonArray()
            val normalized = LinkedHashMap<String, TrafficMode>()
            val duplicates = linkedSetOf<String>()
            val invalidPackages = linkedSetOf<String>()
            val invalidModes = linkedSetOf<String>()

            decoded.forEach { element ->
                val rule = element as? JsonObject ?: return@forEach
                val packageName = rule.get("packageName")?.asString?.trim().orEmpty()
                val modeName = rule.get("trafficMode")?.asString?.trim().orEmpty()
                val mode = runCatching { TrafficMode.valueOf(modeName) }.getOrNull()

                if (!isValidPackageName(packageName)) {
                    if (packageName.isNotEmpty()) invalidPackages += packageName
                    return@forEach
                }
                if (mode == null) {
                    if (modeName.isNotEmpty()) invalidModes += modeName
                    return@forEach
                }

                if (normalized.containsKey(packageName)) duplicates += packageName
                normalized[packageName] = mode
            }

            val missingPackages = if (checkInstalledApps) {
                PackageCache.awaitLoadSync()
                normalized.keys.filterNot { PackageCache.packageMap.containsKey(it) }.toSet()
            } else {
                emptySet()
            }

            AppTrafficRuleValidationReport(
                normalizedRules = normalized.entries.map { (packageName, trafficMode) ->
                    AppTrafficRule(packageName, trafficMode)
                },
                duplicatePackages = duplicates,
                invalidPackages = invalidPackages,
                invalidModes = invalidModes,
                missingPackages = missingPackages,
            )
        }.getOrElse { error ->
            AppTrafficRuleValidationReport(parseError = error.message ?: error.javaClass.simpleName)
        }
    }

    fun validateRules(rules: List<AppTrafficRule>, checkInstalledApps: Boolean = true): AppTrafficRuleValidationReport {
        val normalized = LinkedHashMap<String, TrafficMode>()
        val duplicates = linkedSetOf<String>()
        val invalidPackages = linkedSetOf<String>()

        rules.forEach { rule ->
            val packageName = rule.packageName.trim()
            if (!isValidPackageName(packageName)) {
                if (packageName.isNotEmpty()) invalidPackages += packageName
                return@forEach
            }
            if (normalized.containsKey(packageName)) duplicates += packageName
            normalized[packageName] = rule.trafficMode
        }

        val missingPackages = if (checkInstalledApps) {
            PackageCache.awaitLoadSync()
            normalized.keys.filterNot { PackageCache.packageMap.containsKey(it) }.toSet()
        } else {
            emptySet()
        }

        return AppTrafficRuleValidationReport(
            normalizedRules = normalized.entries.map { (packageName, trafficMode) ->
                AppTrafficRule(packageName, trafficMode)
            },
            duplicatePackages = duplicates,
            invalidPackages = invalidPackages,
            missingPackages = missingPackages,
        )
    }

    fun logReport(reason: String, report: AppTrafficRuleValidationReport) {
        Logs.i(
            "Traffic rules[$reason]: valid=${report.normalizedRules.size}, duplicates=${report.duplicatePackages.size}, invalidPackages=${report.invalidPackages.size}, invalidModes=${report.invalidModes.size}, missingApps=${report.missingPackages.size}, parseError=${report.parseError ?: "none"}"
        )
        if (report.duplicatePackages.isNotEmpty()) {
            Logs.w("Traffic rules[$reason]: duplicatePackages=${report.duplicatePackages.sorted()}")
        }
        if (report.invalidPackages.isNotEmpty()) {
            Logs.w("Traffic rules[$reason]: invalidPackages=${report.invalidPackages.sorted()}")
        }
        if (report.invalidModes.isNotEmpty()) {
            Logs.w("Traffic rules[$reason]: invalidModes=${report.invalidModes.sorted()}")
        }
        if (report.missingPackages.isNotEmpty()) {
            Logs.w("Traffic rules[$reason]: missingApps=${report.missingPackages.sorted()}")
        }
        if (report.parseError != null) {
            Logs.e("Traffic rules[$reason]: parseError=${report.parseError}")
        }
    }

    private fun isValidPackageName(packageName: String): Boolean {
        return packageName.isNotBlank() && packageNamePattern.matches(packageName)
    }
}

object AppTrafficRuleSerializer {

    private val listType = object : TypeToken<List<AppTrafficRule>>() {}.type

    fun serialize(rules: List<AppTrafficRule>): String {
        if (rules.isEmpty()) return ""

        val serializedRules = AppTrafficRuleValidator.validateRules(rules, checkInstalledApps = false).normalizedRules
        if (serializedRules.isEmpty()) return ""
        return JavaUtil.gson.toJson(serializedRules, listType)
    }

    fun deserialize(raw: String?): List<AppTrafficRule> {
        return AppTrafficRuleValidator.validateSerialized(raw, checkInstalledApps = false).normalizedRules
    }
}
