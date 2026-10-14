package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import java.security.MessageDigest
import java.util.Locale

internal fun parseSubscriptionHeaders(text: String): Map<String, String> {
    require(text.toByteArray(Charsets.UTF_8).size <= 8192) { "HTTP headers exceed 8 KiB" }
    val headers = linkedMapOf<String, String>()
    for (line in text.lineSequence()) {
        if (line.isBlank()) continue
        val separator = line.indexOf(':')
        require(separator > 0) { "Expected Header-Name: value" }
        val name = line.substring(0, separator).trim().lowercase(Locale.ROOT)
        val value = line.substring(separator + 1).trim()
        require(name.isNotEmpty() && name.all { it in 'a'..'z' || it in '0'..'9' || it in "!#$%&'*+-.^_`|~" }) { "Invalid HTTP header name" }
        require(value.none { it < ' ' || it == '\u007f' }) { "Invalid HTTP header value" }
        require(name !in setOf("host", "content-length", "connection", "transfer-encoding", "proxy-authorization", "upgrade", "trailer", "te")) { "Reserved HTTP header: $name" }
        require(!headers.containsKey(name)) { "Duplicate HTTP header: $name" }
        headers[name] = value
        require(headers.size <= 64) { "Too many HTTP headers" }
    }
    return headers
}

internal fun subscriptionFingerprint(bean: AbstractBean): String {
    val copy = bean.clone().apply {
        name = ""
        customOutboundJson = ""
        customConfigJson = ""
        if (this is WireGuardBean) useConfigRoutes = false
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(KryoConverters.serialize(copy))
    return bean.javaClass.name + ":" + digest.joinToString("") { "%02x".format(it) }
}

// Match unique unchanged connections before names, so renames and name swaps
// preserve ID references. Ambiguous duplicates only match their existing name.
internal fun matchSubscriptionProfiles(
    old: Map<Long, Pair<String, String>>,
    incoming: Map<String, String>,
): Map<String, Long> {
    val matches = linkedMapOf<String, Long>()
    val oldByFingerprint = old.entries.groupBy { it.value.second }
    val newByFingerprint = incoming.entries.groupBy { it.value }
    for ((fingerprint, candidates) in newByFingerprint) {
        val previous = oldByFingerprint[fingerprint].orEmpty()
        if (candidates.size == 1 && previous.size == 1) {
            matches[candidates.single().key] = previous.single().key
        }
    }
    val used = matches.values.toHashSet()
    val remainingByName = old.entries.filter { it.key !in used }.groupBy { it.value.first }
    for (name in incoming.keys) {
        if (name in matches) continue
        val previous = remainingByName[name].orEmpty()
        if (previous.size == 1) matches[name] = previous.single().key
    }
    return matches
}
