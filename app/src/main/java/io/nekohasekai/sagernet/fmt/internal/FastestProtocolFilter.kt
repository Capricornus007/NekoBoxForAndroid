package io.nekohasekai.sagernet.fmt.internal

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_ANYTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CHAIN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HTTP
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_HYSTERIA
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_JUICITY
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_MIERU
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_NAIVE
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SHADOWTLS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SOCKS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SSH
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_SSR
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TROJAN_GO
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_TUIC
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_VMESS
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_WG

internal object FastestProtocolFilter {
    // Stable identifiers: never use a node's editable name to identify its protocol.
    val keys = listOf(
        "socks", "http", "shadowsocks", "shadowsocksr", "vmess", "vless",
        "trojan", "trojan-go", "naive", "hysteria", "hysteria2", "tuic", "juicity",
        "ssh", "wireguard", "shadowtls", "mieru", "anytls", "custom",
    )

    fun protocol(profile: ProxyEntity): String = when (profile.type) {
        TYPE_SOCKS -> "socks"
        TYPE_HTTP -> "http"
        TYPE_SS -> "shadowsocks"
        TYPE_SSR -> "shadowsocksr"
        TYPE_VMESS -> if (profile.vmessBean?.isVLESS == true) "vless" else "vmess"
        TYPE_TROJAN -> "trojan"
        TYPE_TROJAN_GO -> "trojan-go"
        TYPE_NAIVE -> "naive"
        TYPE_HYSTERIA -> if (profile.hysteriaBean?.protocolVersion == 2) "hysteria2" else "hysteria"
        TYPE_TUIC -> "tuic"
        TYPE_JUICITY -> "juicity"
        TYPE_SSH -> "ssh"
        TYPE_WG -> "wireguard"
        TYPE_SHADOWTLS -> "shadowtls"
        TYPE_MIERU -> "mieru"
        TYPE_ANYTLS -> "anytls"
        else -> "custom"
    }

    fun filter(
        candidates: List<ProxyEntity>,
        mode: Int,
        protocols: Set<String>,
        lookup: (Long) -> ProxyEntity?,
        extraHops: (ProxyEntity) -> List<ProxyEntity> = { emptyList() },
    ): List<ProxyEntity> {
        if (mode !in 0..1 || protocols.any { it !in keys }) {
            throw FastestCandidateResolutionException(FastestCandidateResolutionError.INVALID_PROTOCOL_FILTER)
        }
        if (mode == 0 && protocols.isEmpty()) return candidates
        fun matches(profile: ProxyEntity, path: Set<Long>): Boolean {
            if (profile.type != TYPE_CHAIN) return (protocol(profile) in protocols) == (mode == 1)
            if (profile.id in path || path.size >= 64) return false
            val hops = profile.chainBean?.proxies.orEmpty()
            return hops.isNotEmpty() && hops.all { id ->
                lookup(id)?.let { matches(it, path + profile.id) } == true
            }
        }
        // Remove an entire candidate when a hop is excluded; never rewrite the user's chain.
        return candidates.filter { candidate ->
            matches(candidate, emptySet()) && extraHops(candidate).all { matches(it, emptySet()) }
        }
    }
}
