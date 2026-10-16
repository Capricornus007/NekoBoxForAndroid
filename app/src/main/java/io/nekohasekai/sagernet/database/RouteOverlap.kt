package io.nekohasekai.sagernet.database

/** Conservative: custom JSON and address/rule-set combinations need the running core. */
internal object RouteOverlap {
    data class Finding(val earlier: RuleEntity, val later: RuleEntity, val duplicate: Boolean)

    fun find(rules: List<RuleEntity>, limit: Int = 200): List<Finding> {
        val enabled = rules.filter { it.enabled && it.config.isBlank() }
        val result = mutableListOf<Finding>()
        for ((index, later) in enabled.withIndex()) {
            for (earlier in enabled.take(index)) {
                if (covers(earlier, later)) {
                    result += Finding(earlier, later, sameMatch(earlier, later) &&
                        earlier.outbound == later.outbound && earlier.fallbackMode == later.fallbackMode &&
                        earlier.fallbackProfile == later.fallbackProfile)
                    break // The first matching rule is the useful explanation.
                }
            }
            if (result.size >= limit) break
        }
        return result
    }

    private fun tokens(value: String) = value.split(',', '\n').map(String::trim).filter(String::isNotEmpty).toSet()
    private fun fields(rule: RuleEntity) = listOf(rule.ip, rule.port, rule.sourcePort, rule.network,
        rule.source, rule.protocol, rule.ruleset).map(::tokens) + listOf(rule.packages)
    private fun sameMatch(a: RuleEntity, b: RuleEntity) = fields(a) == fields(b) &&
        domains(a.domains) == domains(b.domains)

    private fun domains(value: String) = tokens(value).map { token ->
        val lower = token.lowercase(java.util.Locale.ROOT)
        if (':' !in lower) "domain:$lower" else lower
    }.toSet()

    private fun covers(a: RuleEntity, b: RuleEntity): Boolean {
        val af = fields(a)
        val bf = fields(b)
        val ad = domains(a.domains)
        val bd = domains(b.domains)
        // Empty rules are omitted by ConfigBuilder.
        if (ad.isEmpty() && af.all { it.isEmpty() }) return false
        if (bd.isEmpty() && bf.all { it.isEmpty() }) return false
        if (ad == bd && af == bf) return true
        // Mixed address/geodata conditions have special OR semantics; only exact duplicates above.
        if (a.ip.isNotBlank() || b.ip.isNotBlank() || a.ruleset.isNotBlank() || b.ruleset.isNotBlank()) return false
        if (af.zip(bf).any { (upper, lower) -> upper.isNotEmpty() &&
                (lower.isEmpty() || !upper.containsAll(lower)) }) return false
        if (ad.isEmpty()) return true
        if (bd.isEmpty()) return false
        return bd.all { lower -> ad.any { upper -> domainCovers(upper, lower) } }
    }

    private fun domainCovers(upper: String, lower: String): Boolean {
        if (upper == lower) return true
        if (!upper.startsWith("domain:")) return false
        val suffix = upper.removePrefix("domain:")
        // Leading dots and regexp/keyword/geosite are intentionally compared only exactly.
        if (suffix.isEmpty() || suffix.startsWith('.')) return false
        val host = when {
            lower.startsWith("full:") -> lower.removePrefix("full:")
            lower.startsWith("domain:") -> lower.removePrefix("domain:")
            else -> return false
        }
        return host == suffix || host.endsWith(".$suffix")
    }
}
