package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.unwrapIPV6Host
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.checkEmpty
import moe.matsuri.nb4a.makeSingBoxRule
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun dnsResolver(tag: String, strategy: String?) = DomainResolveOptions().apply {
    server = tag
    this.strategy = strategy?.takeIf { it.isNotEmpty() }
}

// Settings keep their URI representation; only the generated core config changes.
internal fun dnsServer(address: String, tag: String, resolver: String, detour: String? = null): DNSServerOptions {
    val type = if (address.contains("://")) {
        address.substringBefore("://").lowercase()
    } else if (address == "local") {
        "local"
    } else {
        "udp"
    }
    return DNSServerOptions().apply {
        this.type = type
        this.tag = tag
        if (type == "local") return@apply
        require(type in setOf("udp", "tcp", "tls", "quic", "https", "h3")) { "Unsupported DNS transport" }
        var authority = address.substringAfter("://", address)
        if (!authority.startsWith('[') && authority.count { it == ':' } > 1) {
            authority = "[${authority.unwrapIPV6Host()}]"
        }
        val url = ("https://" + authority).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid DNS server address")
        require(url.username.isEmpty() && url.password.isEmpty() && url.fragment == null) { "Invalid DNS server address" }
        val isHttps = type == "https" || type == "h3"
        require(isHttps || (url.encodedPath == "/" && url.encodedQuery == null)) { "Invalid DNS server address" }
        server = url.host
        // HttpUrl's synthetic HTTPS port is not the default for UDP/TCP/TLS/QUIC.
        val explicitPort = authority.substringBefore('/').substringBefore('?').let {
            if (it.startsWith('[')) it.substringAfter(']', "").startsWith(':') else it.contains(':')
        }
        server_port = if (explicitPort) {
            url.port
        } else {
            when (type) {
                "udp", "tcp" -> 53
                "tls", "quic" -> 853
                else -> 443
            }
        }
        if (isHttps) path = (url.encodedPath.takeUnless { it == "/" } ?: "/dns-query") + (url.encodedQuery?.let { "?$it" } ?: "")
        if (!url.host.isIpAddress()) domain_resolver = dnsResolver(resolver, null)
        this.detour = detour
    }
}

internal fun proxyRuleDnsServer(address: String, tag: String, outbound: String): DNSServerOptions {
    val error = app.getString(R.string.route_dns_server_error)
    require(address.isNotBlank() && address.none { it.isWhitespace() || it.isISOControl() }) { error }
    return try {
        dnsServer(address, tag, "dns-direct", outbound).also { require(it.type != "local") { error } }
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException(error)
    }
}

internal fun RuleEntity.validateDnsRouting() {
    if (!dnsThroughOutbound) return
    require(outbound >= 0) { app.getString(R.string.route_dns_proxy_error) }
    val domainRule = DNSRule_DefaultOptions().apply { makeSingBoxRule(domains.listByLineOrComma()) }
    val ruleSets = ruleset.listByLineOrComma()
    require(
        ip.isBlank() && port.isBlank() && sourcePort.isBlank() && network.isBlank() &&
            source.isBlank() && protocol.isBlank() && config.isBlank() &&
            (ruleset.isBlank() || ruleSets.isNotEmpty()) &&
            ruleSets.all { it.startsWith("rssite:") } &&
            (domains.isBlank() || !domainRule.checkEmpty()) &&
            (packages.isNotEmpty() || !domainRule.checkEmpty() || ruleSets.isNotEmpty()),
    ) { app.getString(R.string.route_dns_match_error) }
    if (dnsServer.isNotBlank()) proxyRuleDnsServer(dnsServer.trim(), "dns-check", "proxy")
}

// Rule strategy cannot coexist with query_type in 1.14. Return an empty answer
// for the excluded family without weakening the original rule's match criteria.
internal fun dnsFamilyRules(rule: DNSRule_DefaultOptions, strategy: String?): List<SingBoxOption> {
    val blocked = when (strategy) {
        "ipv4_only" -> "AAAA"
        "ipv6_only" -> "A"
        else -> return listOf(rule)
    }
    if (rule.query_type?.contains(blocked) == false) return listOf(rule)
    val response = SingBoxOptions.toJsonTree(rule).apply {
        remove("server")
        remove("disable_cache")
        addProperty("action", "predefined")
        addProperty("rcode", "NOERROR")
        add("query_type", com.google.gson.JsonArray().apply { add(blocked) })
    }
    return listOf(CustomSingBoxOption(response.toString()), rule)
}
