package io.nekohasekai.sagernet.fmt.masque

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.blankAsNull
import io.nekohasekai.sagernet.ktx.linkBuilder
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.SingBoxOptions.MASQUEConfig
import moe.matsuri.nb4a.SingBoxOptions.Outbound_MASQUEOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// MASQUE carries no server host: the Cloudflare endpoint lives in the config block. The link
// keeps an opaque placeholder host so okhttp can build a URL and the payload stays in the query.
private const val LINK_SCHEME = "masque"
private const val LINK_HOST = "masque.local"
private val TRANSPORT_VALUES = setOf("auto", "h3", "h2")

/** use_http2 is not stored on the bean; it is derived from transport, exactly as sing-box does. */
fun masqueUseHTTP2(transport: String?): Boolean = transport == "h2"

fun MasqueBean.hasConfig(): Boolean = listOf(
    configPrivateKey,
    configEndpointV4,
    configEndpointV6,
    configEndpointH2V4,
    configEndpointH2V6,
    configEndpointPubKey,
    configLicense,
    configId,
    configAccessToken,
    configIPv4,
    configIPv6,
).any { !it.isNullOrBlank() }

fun MasqueBean.buildMasqueConfig(): MASQUEConfig? {
    if (!hasConfig()) return null
    return MASQUEConfig().apply {
        private_key = configPrivateKey.blankAsNull()
        endpoint_v4 = configEndpointV4.blankAsNull()
        endpoint_v6 = configEndpointV6.blankAsNull()
        endpoint_h2_v4 = configEndpointH2V4.blankAsNull()
        endpoint_h2_v6 = configEndpointH2V6.blankAsNull()
        endpoint_pub_key = configEndpointPubKey.blankAsNull()
        license = configLicense.blankAsNull()
        id = configId.blankAsNull()
        access_token = configAccessToken.blankAsNull()
        ipv4 = configIPv4.blankAsNull()
        ipv6 = configIPv6.blankAsNull()
    }
}

fun buildSingBoxOutboundMasqueBean(bean: MasqueBean, detourTag: String?): Outbound_MASQUEOptions {
    val transport = bean.transport?.takeIf { it in TRANSPORT_VALUES } ?: "auto"
    return Outbound_MASQUEOptions().apply {
        type = "masque"
        system = bean.system
        name = bean.interfaceName.blankAsNull()
        allowed_ips = bean.allowedIPs.blankAsNull()?.listByLineOrComma()
        this.transport = transport
        use_http2 = masqueUseHTTP2(transport)
        use_ipv6 = bean.useIPv6
        udp_timeout = bean.udpTimeout.blankAsNull()
        udp_keepalive_period = bean.udpKeepalivePeriod.blankAsNull()
        if (bean.udpInitialPacketSize != null && bean.udpInitialPacketSize > 0) {
            udp_initial_packet_size = bean.udpInitialPacketSize
        }
        disable_path_mtu_discovery = bean.disablePathMTUDiscovery
        h3_fallback_timeout = bean.h3FallbackTimeout.blankAsNull()
        if (bean.mtu != null && bean.mtu > 0) {
            mtu = bean.mtu
        }
        reconnect_delay = bean.reconnectDelay.blankAsNull()
        profile = SingBoxOptions.CloudflareProfile().apply {
            id = bean.profileId.blankAsNull()
            auth_token = bean.profileAuthToken.blankAsNull()
            private_key = bean.profilePrivateKey.blankAsNull()
            recreate = bean.profileRecreate
            detour = detourTag
        }
        config = if (bean.profileRecreate == true) null else bean.buildMasqueConfig()
        tls = SingBoxOptions.MASQUEOutboundTLSOptions().apply {
            sni = bean.tlsSNI.blankAsNull()
            insecure = bean.tlsInsecure || DataStore.globalAllowInsecure
            cipher_suites = bean.tlsCipherSuites.blankAsNull()?.listByLineOrComma()
            curve_preferences = bean.tlsCurvePreferences.blankAsNull()?.listByLineOrComma()
            fragment = bean.tlsFragment
            fragment_fallback_delay = bean.tlsFragmentFallbackDelay.blankAsNull()
            record_fragment = bean.tlsRecordFragment
            kernel_tx = bean.tlsKernelTx
            kernel_rx = bean.tlsKernelRx
        }
    }
}

fun MasqueBean.toUri(): String {
    val builder = linkBuilder().host(LINK_HOST)

    builder.addQueryParameter("transport", transport ?: "auto")
    builder.addQueryParameter("use_ipv6", if (useIPv6 == true) "1" else "0")
    builder.addQueryParameter("system", if (system == true) "1" else "0")
    if (!interfaceName.isNullOrBlank()) builder.addQueryParameter("interface_name", interfaceName)
    if (!allowedIPs.isNullOrBlank()) builder.addQueryParameter("allowed_ips", allowedIPs)
    if (!profileId.isNullOrBlank()) builder.addQueryParameter("profile_id", profileId)
    if (!profileAuthToken.isNullOrBlank()) builder.addQueryParameter("profile_auth_token", profileAuthToken)
    if (!profilePrivateKey.isNullOrBlank()) builder.addQueryParameter("profile_private_key", profilePrivateKey)
    builder.addQueryParameter("profile_recreate", if (profileRecreate == true) "1" else "0")
    if (!profileDetour.isNullOrBlank()) builder.addQueryParameter("profile_detour", profileDetour)
    if (!configPrivateKey.isNullOrBlank()) builder.addQueryParameter("cfg_private_key", configPrivateKey)
    if (!configEndpointV4.isNullOrBlank()) builder.addQueryParameter("cfg_endpoint_v4", configEndpointV4)
    if (!configEndpointV6.isNullOrBlank()) builder.addQueryParameter("cfg_endpoint_v6", configEndpointV6)
    if (!configEndpointH2V4.isNullOrBlank()) builder.addQueryParameter("cfg_endpoint_h2_v4", configEndpointH2V4)
    if (!configEndpointH2V6.isNullOrBlank()) builder.addQueryParameter("cfg_endpoint_h2_v6", configEndpointH2V6)
    if (!configEndpointPubKey.isNullOrBlank()) builder.addQueryParameter("cfg_endpoint_pub_key", configEndpointPubKey)
    if (!configLicense.isNullOrBlank()) builder.addQueryParameter("cfg_license", configLicense)
    if (!configId.isNullOrBlank()) builder.addQueryParameter("cfg_id", configId)
    if (!configAccessToken.isNullOrBlank()) builder.addQueryParameter("cfg_access_token", configAccessToken)
    if (!configIPv4.isNullOrBlank()) builder.addQueryParameter("cfg_ipv4", configIPv4)
    if (!configIPv6.isNullOrBlank()) builder.addQueryParameter("cfg_ipv6", configIPv6)
    if (!udpTimeout.isNullOrBlank()) builder.addQueryParameter("udp_timeout", udpTimeout)
    if (!udpKeepalivePeriod.isNullOrBlank()) builder.addQueryParameter("udp_keepalive_period", udpKeepalivePeriod)
    if (udpInitialPacketSize != null && udpInitialPacketSize > 0) {
        builder.addQueryParameter("udp_initial_packet_size", udpInitialPacketSize.toString())
    }
    if (disablePathMTUDiscovery == true) builder.addQueryParameter("disable_path_mtu_discovery", "1")
    if (!h3FallbackTimeout.isNullOrBlank()) builder.addQueryParameter("h3_fallback_timeout", h3FallbackTimeout)
    if (mtu != null && mtu > 0) builder.addQueryParameter("mtu", mtu.toString())
    if (!reconnectDelay.isNullOrBlank()) builder.addQueryParameter("reconnect_delay", reconnectDelay)
    if (!tlsSNI.isNullOrBlank()) builder.addQueryParameter("sni", tlsSNI)
    if (tlsInsecure == true) builder.addQueryParameter("insecure", "1")
    if (!tlsCipherSuites.isNullOrBlank()) builder.addQueryParameter("cipher_suites", tlsCipherSuites)
    if (!tlsCurvePreferences.isNullOrBlank()) builder.addQueryParameter("curve_preferences", tlsCurvePreferences)
    if (tlsFragment == true) builder.addQueryParameter("fragment", "1")
    if (!tlsFragmentFallbackDelay.isNullOrBlank()) {
        builder.addQueryParameter(
            "fragment_fallback_delay",
            tlsFragmentFallbackDelay,
        )
    }
    if (tlsRecordFragment == true) builder.addQueryParameter("record_fragment", "1")
    if (tlsKernelTx == true) builder.addQueryParameter("kernel_tx", "1")
    if (tlsKernelRx == true) builder.addQueryParameter("kernel_rx", "1")
    if (!name.isNullOrBlank()) {
        builder.encodedFragment(name.urlSafe())
    }

    return builder.toLink(LINK_SCHEME)
}

fun parseMasque(link: String): MasqueBean {
    val url = link.replace("$LINK_SCHEME://", "https://").toHttpUrlOrNull()
        ?: error("invalid masque link $link")
    return MasqueBean().apply {
        initializeDefaultValues()
        name = url.fragment

        fun flag(key: String): Boolean = url.queryParameter(key) == "1" || url.queryParameter(key) == "true"
        fun text(key: String): String? = url.queryParameter(key)?.takeIf { it.isNotEmpty() }

        text("transport")?.let { transport = if (it in TRANSPORT_VALUES) it else "auto" }
        url.queryParameter("use_ipv6")?.let { useIPv6 = flag("use_ipv6") }
        url.queryParameter("system")?.let { system = flag("system") }
        text("interface_name")?.let { interfaceName = it }
        text("allowed_ips")?.let { allowedIPs = it }
        text("profile_id")?.let { profileId = it }
        text("profile_auth_token")?.let { profileAuthToken = it }
        text("profile_private_key")?.let { profilePrivateKey = it }
        url.queryParameter("profile_recreate")?.let { profileRecreate = flag("profile_recreate") }
        text("profile_detour")?.let { profileDetour = it }
        text("cfg_private_key")?.let { configPrivateKey = it }
        text("cfg_endpoint_v4")?.let { configEndpointV4 = it }
        text("cfg_endpoint_v6")?.let { configEndpointV6 = it }
        text("cfg_endpoint_h2_v4")?.let { configEndpointH2V4 = it }
        text("cfg_endpoint_h2_v6")?.let { configEndpointH2V6 = it }
        text("cfg_endpoint_pub_key")?.let { configEndpointPubKey = it }
        text("cfg_license")?.let { configLicense = it }
        text("cfg_id")?.let { configId = it }
        text("cfg_access_token")?.let { configAccessToken = it }
        text("cfg_ipv4")?.let { configIPv4 = it }
        text("cfg_ipv6")?.let { configIPv6 = it }
        text("udp_timeout")?.let { udpTimeout = it }
        text("udp_keepalive_period")?.let { udpKeepalivePeriod = it }
        text("udp_initial_packet_size")?.toIntOrNull()?.let { udpInitialPacketSize = it }
        url.queryParameter(
            "disable_path_mtu_discovery",
        )?.let { disablePathMTUDiscovery = flag("disable_path_mtu_discovery") }
        text("h3_fallback_timeout")?.let { h3FallbackTimeout = it }
        text("mtu")?.toIntOrNull()?.let { mtu = it }
        text("reconnect_delay")?.let { reconnectDelay = it }
        text("sni")?.let { tlsSNI = it }
        url.queryParameter("insecure")?.let { tlsInsecure = flag("insecure") }
        text("cipher_suites")?.let { tlsCipherSuites = it }
        text("curve_preferences")?.let { tlsCurvePreferences = it }
        url.queryParameter("fragment")?.let { tlsFragment = flag("fragment") }
        text("fragment_fallback_delay")?.let { tlsFragmentFallbackDelay = it }
        url.queryParameter("record_fragment")?.let { tlsRecordFragment = flag("record_fragment") }
        url.queryParameter("kernel_tx")?.let { tlsKernelTx = flag("kernel_tx") }
        url.queryParameter("kernel_rx")?.let { tlsKernelRx = flag("kernel_rx") }
    }
}
