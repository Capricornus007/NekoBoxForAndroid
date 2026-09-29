package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.linesNoComments
import org.json.JSONObject
import org.json.JSONTokener

/**
 * What an interactive import will and will not take from its source. The parser keeps its
 * silent-skip behavior; this looks at the source text next to the parsed result so the user
 * sees accepted, skipped and unsupported nodes and which source sections are dropped.
 */
data class ImportPreview(
    /** Accepted nodes by display type, e.g. "VMess" to 8. */
    val accepted: Map<String, Int>,
    /** Source entries that produced no node, by reason. */
    val skipped: Map<Reason, Int>,
    /** Source sections that this app never imports. */
    val dropped: List<Section>,
) {
    enum class Reason { BUILTIN, UNPARSED }
    enum class Section { PROXY_GROUPS, RULES, RULE_PROVIDERS, DNS, INBOUNDS }

    val acceptedCount get() = accepted.values.sum()
    val skippedCount get() = skipped.values.sum()

    /** True when applying only the nodes cannot reproduce what the source configures. */
    val behaviorDiffers get() = dropped.isNotEmpty()

    companion object {
        private val CLASH_SECTIONS = mapOf(
            "proxy-groups" to Section.PROXY_GROUPS,
            "rules" to Section.RULES,
            "rule-providers" to Section.RULE_PROVIDERS,
            "dns" to Section.DNS,
        )
        private val SINGBOX_SECTIONS = mapOf(
            "route" to Section.RULES,
            "dns" to Section.DNS,
            "inbounds" to Section.INBOUNDS,
        )
        private val SINGBOX_BUILTIN_TYPES = setOf("dns", "block", "direct", "selector", "urltest")

        fun of(text: String, proxies: List<AbstractBean>): ImportPreview {
            val accepted = proxies.groupingBy { ProxyEntity().apply { putBean(it) }.displayType() }.eachCount()
            val trimmed = text.trimStart()
            return when {
                trimmed.startsWith('{') -> singBox(trimmed, accepted)
                text.contains("proxies:") -> clash(text, accepted)
                text.contains("[Interface]") -> ImportPreview(accepted, emptyMap(), emptyList())
                else -> links(text, accepted)
            }
        }

        private fun singBox(text: String, accepted: Map<String, Int>): ImportPreview {
            val json = runCatching { JSONTokener(text).nextValue() as? JSONObject }.getOrNull()
                ?: return ImportPreview(accepted, emptyMap(), emptyList())
            val outbounds = json.optJSONArray("outbounds")
            val builtin = outbounds?.let { array ->
                (0 until array.length()).count { array.optJSONObject(it)?.optString("type") in SINGBOX_BUILTIN_TYPES }
            } ?: 0
            val total = outbounds?.length() ?: 0
            val unparsed = (total - builtin - accepted.values.sum()).coerceAtLeast(0)
            return ImportPreview(
                accepted,
                mapOf(Reason.BUILTIN to builtin, Reason.UNPARSED to unparsed).filterValues { it > 0 },
                SINGBOX_SECTIONS.filterKeys(json::has).values.toList(),
            )
        }

        private fun clash(text: String, accepted: Map<String, Int>): ImportPreview {
            // Top-level keys and node entries are counted from the text so a malformed document
            // still produces a preview; the parser owns real YAML handling.
            val lines = text.lines()
            val topLevelKeys = lines.mapNotNull { line ->
                Regex("^([A-Za-z0-9-]+):").find(line)?.groupValues?.get(1)
            }.toSet()
            val proxiesStart = lines.indexOfFirst { it.startsWith("proxies:") }
            val block = if (proxiesStart < 0) {
                emptyList()
            } else {
                lines.drop(proxiesStart + 1).takeWhile { it.isBlank() || it.first().isWhitespace() || it.startsWith("-") }
            }
            // Only items at the list's own indentation are nodes; deeper "- " lines are nested values.
            val itemIndent = block.firstOrNull { it.trimStart().startsWith("- ") }?.let { it.length - it.trimStart().length }
            val entries = block.count { it.trimStart().startsWith("- ") && it.length - it.trimStart().length == itemIndent }
            val unparsed = (entries - accepted.values.sum()).coerceAtLeast(0)
            return ImportPreview(
                accepted,
                mapOf(Reason.UNPARSED to unparsed).filterValues { it > 0 },
                CLASH_SECTIONS.filterKeys(topLevelKeys::contains).values.toList(),
            )
        }

        private fun links(text: String, accepted: Map<String, Int>): ImportPreview {
            val entries = text.linesNoComments().flatMap { it.split(' ') }.count { it.isNotBlank() }
            val unparsed = (entries - accepted.values.sum()).coerceAtLeast(0)
            return ImportPreview(accepted, mapOf(Reason.UNPARSED to unparsed).filterValues { it > 0 }, emptyList())
        }
    }
}
