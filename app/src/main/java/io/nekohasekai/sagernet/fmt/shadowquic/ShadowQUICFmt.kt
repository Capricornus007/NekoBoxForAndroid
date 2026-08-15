package io.nekohasekai.sagernet.fmt.shadowquic

import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions.Outbound_ShadowQUICOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseShadowQUIC(url: String): ShadowQUICBean {
    val link = url.replace("shadowquic://", "https://").toHttpUrlOrNull() ?: error(
        "invalid shadowquic link $url",
    )
    return ShadowQUICBean().apply {
        name = link.fragment
        username = link.username
        password = link.password
        serverAddress = link.host
        serverPort = link.port

        link.queryParameter("sni")?.let { sni = it }
        link.queryParameter("alpn")?.let { alpn = it }
        link.queryParameter("congestion_control")?.let { congestionControl = it }
        link.queryParameter("udp_over_stream")?.let {
            if (it == "1" || it == "true") udpOverStream = true
        }
        link.queryParameter("zero_rtt_handshake")?.let {
            if (it == "1" || it == "true") zeroRTT = true
        }
        link.queryParameter("sunny_quic")?.let {
            if (it == "1" || it == "true") sunnyQUIC = true
        }
    }
}

fun ShadowQUICBean.toUri(): String {
    val builder = linkBuilder().username(username).password(password).host(serverAddress).port(serverPort)

    if (sni.isNotBlank()) builder.addQueryParameter("sni", sni)
    if (alpn.isNotBlank()) builder.addQueryParameter("alpn", alpn)
    if (congestionControl.isNotBlank()) builder.addQueryParameter("congestion_control", congestionControl)
    if (udpOverStream) builder.addQueryParameter("udp_over_stream", "1")
    if (zeroRTT) builder.addQueryParameter("zero_rtt_handshake", "1")
    if (sunnyQUIC) builder.addQueryParameter("sunny_quic", "1")
    if (name.isNotBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink("shadowquic")
}

fun buildSingBoxOutboundShadowQUICBean(bean: ShadowQUICBean): Outbound_ShadowQUICOptions {
    return Outbound_ShadowQUICOptions().apply {
        type = "shadowquic"
        server = bean.serverAddress
        server_port = bean.serverPort
        username = bean.username
        password = bean.password

        if (bean.sni.isNotBlank()) {
            server_name = bean.sni
        }
        if (bean.alpn.isNotBlank()) {
            alpn = bean.alpn.split(",").map { it.trim() }
        }
        if (bean.congestionControl.isNotBlank()) {
            congestion_control = bean.congestionControl
        }
        udp_over_stream = bean.udpOverStream
        zero_rtt_handshake = bean.zeroRTT
        sunny_quic = bean.sunnyQUIC
    }
}
