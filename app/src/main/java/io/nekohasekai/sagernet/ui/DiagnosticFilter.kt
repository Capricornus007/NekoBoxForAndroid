package io.nekohasekai.sagernet.ui

internal data class DiagnosticEvent(val id: String, val app: String, val domain: String, val outbound: String)

/** Join only full correlation IDs, including creation time; uncorrelated DNS is independent. */
internal fun diagnosticMatches(events: List<DiagnosticEvent>, app: String, domain: String, outbound: String): Set<Int> {
    val groups = events.indices.groupBy { events[it].id.takeIf(String::isNotBlank) ?: "unrelated:$it" }
    fun contains(value: String, query: String) = value.contains(query.trim(), ignoreCase = true)
    return groups.values.filter { indices ->
        (app.isBlank() || indices.any { contains(events[it].app, app) }) &&
            (domain.isBlank() || indices.any { contains(events[it].domain, domain) }) &&
            (outbound.isBlank() || indices.any { contains(events[it].outbound, outbound) })
    }.flatten().toSet()
}
