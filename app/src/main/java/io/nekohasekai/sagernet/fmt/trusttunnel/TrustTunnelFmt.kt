package io.nekohasekai.sagernet.fmt.trusttunnel

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptions.Outbound_TrustTunnelOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Base64

fun parseTrustTunnel(url: String): TrustTunnelBean {
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