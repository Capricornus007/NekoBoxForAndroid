package io.nekohasekai.sagernet.fmt.trusttunnel

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound_TrustTunnelOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.nio.charset.StandardCharsets
import java.util.Base64

private const val OFFICIAL_PREFIX = "tt://?"

private enum class TrustTunnelTag(val code: Long) {
    VERSION(0x00),
    HOSTNAME(0x01),
    ADDRESSES(0x02),
    CUSTOM_SNI(0x03),
    USERNAME(0x05),
    PASSWORD(0x06),
    SKIP_VERIFICATION(0x07),
    UPSTREAM_PROTOCOL(0x09),
    NAME(0x0C),
}

/**
 * Parses both NB4A's legacy query-style link and TrustTunnel's current TLV deep-link format.
 *
 * The official format may carry multiple server addresses, so this returns one profile per
 * address. Keeping the legacy parser means old exported links continue to work unchanged.
 */
fun parseTrustTunnel(url: String): List<TrustTunnelBean> {
    return if (url.startsWith(OFFICIAL_PREFIX, ignoreCase = true)) {
        parseOfficialTrustTunnel(url)
    } else {
        listOf(parseLegacyTrustTunnel(url))
    }
}

private fun parseLegacyTrustTunnel(url: String): TrustTunnelBean {
    val link = url.replace("tt://", "https://").toHttpUrlOrNull() ?: error(
        "invalid trusttunnel link $url",
    )
    return TrustTunnelBean().apply {
        name = link.fragment
        username = link.username
        password = link.password
        serverAddress = link.host
        serverPort = link.port

        link.queryParameter("sni")?.let { sni = it }
        link.queryParameter("pinned_certchain_sha256")?.let {
            normalizePinnedCertChainHash(it)?.let { hash -> pinnedCertchainSha256 = hash }
        }
        link.queryParameter("quic_congestion_control")?.let { quicCongestionControl = it }
        link.queryParameter("quic")?.let {
            if (it == "1" || it == "true") quic = true
        }
        link.queryParameter("health_check")?.let {
            if (it == "1" || it == "true") healthCheck = true
        }
        link.queryParameter("allow_insecure")?.let {
            if (it == "1" || it == "true") allowInsecure = true
        }
    }
}

private fun parseOfficialTrustTunnel(url: String): List<TrustTunnelBean> {
    val payload = url.substring(OFFICIAL_PREFIX.length)
    require(payload.isNotEmpty()) { "empty trusttunnel payload" }
    val data = runCatching { Base64.getUrlDecoder().decode(payload) }
        .getOrElse { error("invalid trusttunnel payload") }

    val addresses = mutableListOf<String>()
    var hostname: String? = null
    var customSni: String? = null
    var username: String? = null
    var password: String? = null
    var name = ""
    var allowInsecure = false
    var quic = false
    var offset = 0

    while (offset < data.size) {
        val tag = data.readTrustTunnelVarInt(offset)
        offset += tag.encodedSize
        val lengthValue = data.readTrustTunnelVarInt(offset)
        offset += lengthValue.encodedSize
        require(lengthValue.value <= Int.MAX_VALUE.toLong()) { "trusttunnel field is too large" }
        val length = lengthValue.value.toInt()
        require(offset <= data.size - length) { "truncated trusttunnel field" }
        val value = data.copyOfRange(offset, offset + length)
        offset += length

        when (tag.value) {
            TrustTunnelTag.VERSION.code -> {
                require(value.size == 1 && (value[0].toInt() == 0 || value[0].toInt() == 1)) {
                    "invalid trusttunnel version"
                }
            }
            TrustTunnelTag.HOSTNAME.code -> hostname = value.requireUtf8("hostname")
            TrustTunnelTag.ADDRESSES.code -> addresses += value.requireUtf8("address")
            TrustTunnelTag.CUSTOM_SNI.code -> customSni = value.requireUtf8("custom SNI")
            TrustTunnelTag.USERNAME.code -> username = value.requireUtf8("username")
            TrustTunnelTag.PASSWORD.code -> password = value.requireUtf8("password")
            TrustTunnelTag.SKIP_VERIFICATION.code -> {
                require(value.size == 1 && (value[0].toInt() == 0 || value[0].toInt() == 1)) {
                    "invalid trusttunnel verification flag"
                }
                allowInsecure = value[0].toInt() == 1
            }
            TrustTunnelTag.UPSTREAM_PROTOCOL.code -> {
                require(value.size == 1 && (value[0].toInt() == 1 || value[0].toInt() == 2)) {
                    "invalid trusttunnel upstream protocol"
                }
                quic = value[0].toInt() == 2
            }
            TrustTunnelTag.NAME.code -> name = String(value, StandardCharsets.UTF_8)
            // Forward compatibility: unknown tags are intentionally ignored by the spec.
        }
    }

    val verifiedHostname = requireNotNull(hostname) { "missing trusttunnel hostname" }
    val parsedUsername = requireNotNull(username) { "missing trusttunnel username" }
    val parsedPassword = requireNotNull(password) { "missing trusttunnel password" }
    require(addresses.isNotEmpty()) { "missing trusttunnel address" }

    // NB4A's current TrustTunnel core has one TLS server-name field. A link whose CustomSNI
    // differs from the verified hostname needs two distinct fields and cannot be represented
    // safely; reject it instead of silently weakening verification or changing the handshake.
    val tlsServerName = customSni?.also {
        require(allowInsecure || it == verifiedHostname) {
            "trusttunnel custom SNI with separate verification hostname is unsupported"
        }
    } ?: verifiedHostname

    return addresses.map { address ->
        val (host, port) = splitTrustTunnelAddress(address)
        TrustTunnelBean().apply {
            serverAddress = host
            serverPort = port
            this.username = parsedUsername
            this.password = parsedPassword
            sni = tlsServerName
            this.name = name
            this.allowInsecure = allowInsecure
            this.quic = quic
        }
    }
}

private data class TrustTunnelVarInt(val value: Long, val encodedSize: Int)

private fun ByteArray.readTrustTunnelVarInt(offset: Int): TrustTunnelVarInt {
    require(offset in indices) { "truncated trusttunnel varint" }
    val first = this[offset].toInt() and 0xFF
    val encodedSize = 1 shl (first ushr 6)
    require(offset <= size - encodedSize) { "truncated trusttunnel varint" }
    var value = (first and 0x3F).toLong()
    for (index in 1 until encodedSize) {
        value = (value shl 8) or (this[offset + index].toInt() and 0xFF).toLong()
    }
    return TrustTunnelVarInt(value, encodedSize)
}

private fun ByteArray.requireUtf8(field: String): String {
    require(isNotEmpty()) { "empty trusttunnel $field" }
    return String(this, StandardCharsets.UTF_8)
}

private fun splitTrustTunnelAddress(address: String): Pair<String, Int> {
    if (address.startsWith("[")) {
        val closingBracket = address.indexOf(']')
        require(closingBracket > 1 && closingBracket + 1 < address.length && address[closingBracket + 1] == ':') {
            "invalid trusttunnel IPv6 address"
        }
        val host = address.substring(1, closingBracket)
        val port = address.substring(closingBracket + 2).toIntOrNull()
        require(port != null && port in 1..65535) { "invalid trusttunnel port" }
        return host to port
    }
    if (address.count { it == ':' } > 1) {
        // Version 0 deep links allowed a bare IPv6 literal and implied port 443.
        return address to 443
    }
    val separator = address.lastIndexOf(':')
    if (separator < 0) {
        // Version 0 deep links allowed bare IP addresses and implied port 443.
        require(address.isNotBlank()) { "empty trusttunnel address" }
        return address to 443
    }
    val host = address.substring(0, separator)
    val port = address.substring(separator + 1).toIntOrNull()
    require(host.isNotBlank() && port != null && port in 1..65535) { "invalid trusttunnel address" }
    return host to port
}

fun TrustTunnelBean.toUri(): String {
    val builder = linkBuilder().username(username).password(password).host(serverAddress).port(serverPort)

    if (sni.isNotBlank()) {
        builder.addQueryParameter("sni", sni)
    }
    if (pinnedCertchainSha256.isNotBlank()) {
        normalizePinnedCertChainHash(pinnedCertchainSha256.listByLineOrComma().firstOrNull())?.let {
            builder.addQueryParameter("pinned_certchain_sha256", it)
        }
    }
    if (quicCongestionControl.isNotBlank()) {
        builder.addQueryParameter("quic_congestion_control", quicCongestionControl)
    }
    if (quic) {
        builder.addQueryParameter("quic", "1")
    }
    if (healthCheck) {
        builder.addQueryParameter("health_check", "1")
    }
    if (allowInsecure) {
        builder.addQueryParameter("allow_insecure", "1")
    }
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink("tt")
}

fun buildSingBoxOutboundTrustTunnelBean(bean: TrustTunnelBean): Outbound_TrustTunnelOptions {
    return Outbound_TrustTunnelOptions().apply {
        type = "trusttunnel"
        server = bean.serverAddress
        server_port = bean.serverPort
        username = bean.username
        password = bean.password
        quic = bean.quic
        quic_congestion_control = bean.quicCongestionControl
        health_check = bean.healthCheck

        tls = SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            if (bean.sni.isNotBlank()) {
                server_name = bean.sni
            }
            insecure = bean.allowInsecure || DataStore.globalAllowInsecure
        }
    }
}

private fun normalizePinnedCertChainHash(rawHash: String?): String? {
    val certChainHash = rawHash?.replace(":", "")?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        certChainHash.length == 64 -> Base64.getUrlEncoder()
            .encodeToString(certChainHash.chunked(2).map { chunk -> chunk.toInt(16).toByte() }.toByteArray())
        else -> certChainHash.replace('/', '_').replace('+', '-')
    }
}
