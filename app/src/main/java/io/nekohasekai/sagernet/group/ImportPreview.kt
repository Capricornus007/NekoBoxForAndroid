package io.nekohasekai.sagernet.group

import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean

/** What a parse saw besides the nodes it returned. The parsers fill it entry by entry. */
class ImportReport {
    /** One item per source entry that describes a node but did not import: its protocol, or null when unknown. */
    val failed = mutableListOf<String?>()

    /** Source entries that are not nodes, such as sing-box direct or selector outbounds. */
    var builtin = 0

    /** Source sections that this app never imports. */
    val dropped = linkedSetOf<ImportPreview.Section>()
}

/** Profiles parsed from a source, with what the parse could not use. */
class ParsedContent(val proxies: List<AbstractBean>, val report: ImportReport)

/**
 * What an import will and will not take from its source: accepted nodes, entries that are not
 * nodes or did not import, and source sections the app never uses. Counts come from the parser's
 * own walk over the source, so every format and layout is counted the same way.
 */
data class ImportPreview(
    /** Accepted nodes by display type, e.g. "VMess" to 8. */
    val accepted: Map<String, Int>,
    /** Source entries that produced no node, by reason. */
    val skipped: Map<Reason, Int>,
    /** Source sections that this app never imports. */
    val dropped: List<Section>,
    /** Protocol of each entry that did not import, or null when unknown. */
    val failed: List<String?> = emptyList(),
) {
    enum class Reason { BUILTIN, UNPARSED }

    enum class Section(@param:StringRes val title: Int) {
        PROXY_GROUPS(R.string.import_section_proxy_groups),
        RULES(R.string.import_section_rules),
        RULE_PROVIDERS(R.string.import_section_rule_providers),
        DNS(R.string.import_section_dns),
        INBOUNDS(R.string.import_section_inbounds),
    }

    val acceptedCount get() = accepted.values.sum()
    val skippedCount get() = skipped.values.sum()

    /** Source entries that describe nodes but did not import. */
    val unparsedCount get() = skipped[Reason.UNPARSED] ?: 0

    /** True when applying only the nodes cannot reproduce what the source configures. */
    val behaviorDiffers get() = dropped.isNotEmpty()

    companion object {
        fun of(parsed: ParsedContent) = ImportPreview(
            parsed.proxies.groupingBy { ProxyEntity().apply { putBean(it) }.displayType() }.eachCount(),
            mapOf(Reason.BUILTIN to parsed.report.builtin, Reason.UNPARSED to parsed.report.failed.size).filterValues { it > 0 },
            parsed.report.dropped.toList(),
            parsed.report.failed.toList(),
        )
    }
}
