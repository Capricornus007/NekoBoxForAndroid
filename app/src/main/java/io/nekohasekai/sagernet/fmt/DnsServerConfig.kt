package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSRule_DefaultOptions
import java.net.URI
import java.util.Locale

internal fun String.isLocalDnsAddress(): Boolean = trim().let {
    it.equals("local", ignoreCase = true) || it.equals("localhost", ignoreCase = true)
}

internal fun directFallbackDnsAddress(directAddress: String?, remoteAddress: String?): String =
    directAddress?.takeUnless { it.isLocalDnsAddress() }
        ?: remoteAddress?.takeUnless { it.isLocalDnsAddress() }
        ?: "https://dns.google/dns-query"

internal fun DNSRule_DefaultOptions.useBlockedDNSResponse() {
    server = null
    disable_cache = null
    _hack_config_map.putAll(mapOf("action" to "predefined", "rcode" to "NOERROR"))
}

// Typed DNS transports dial directly by default, so remote detours stay explicit.
internal fun dnsServerConfig(address: String, tag: String, bootstrap: String, detour: String): Map<String, Any> {
    val input = address.trim()
    require(input.isNotEmpty()) { "Empty DNS server" }
    val server = linkedMapOf<String, Any>("tag" to tag)
    // Bare localhost selects Android's resolver; explicit udp://localhost or
    // a loopback IP still denotes a real DNS server listening on that address.
    if (input.isLocalDnsAddress()) return server.apply { put("type", "local") }
    val raw = if (input.contains("://")) input else {
        val host = if (!input.startsWith("[") && input.count { it == ':' } > 1) "[$input]" else input
        "udp://$host"
    }
    val uri = URI(raw)
    val type = uri.scheme.lowercase(Locale.ROOT)
    require(type in setOf("udp", "tcp", "tls", "https", "quic", "h3")) { "Unsupported DNS transport: $type" }
    val host = uri.host?.removePrefix("[")?.removeSuffix("]")
    require(!host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Invalid DNS server URL" }
    require(uri.port == -1 || uri.port in 1..65535) { "Invalid DNS server port" }
    server["type"] = type
    server["server"] = host
    if (uri.port != -1) server["server_port"] = uri.port
    if (type == "https" || type == "h3") {
        val path = uri.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/dns-query"
        server["path"] = path + (uri.rawQuery?.let { "?$it" } ?: "")
    } else {
        require(uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") { "DNS transport does not support a path" }
        require(uri.rawQuery == null) { "DNS transport does not support query parameters" }
    }
    server["domain_resolver"] = bootstrap
    // The core rejects detours to an empty direct outbound.
    if (detour.isNotEmpty() && detour != "direct") server["detour"] = detour
    return server
}
