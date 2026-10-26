package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.*

internal fun perAppTunGuard(
    selectedUids: Set<Int>,
    installedUids: Set<Int>,
    appUid: Int,
    bypass: Boolean,
    resolverUids: List<Int>,
    rootDnsPorts: List<Int> = emptyList(),
): Rule {
    // Full UIDs identify both the package and Android profile. Never reduce to appId.
    val denied = if (bypass) selectedUids + 1000 else emptySet()
    val allowed = ((if (bypass) installedUids else selectedUids) + appUid)
        .filter { it >= 0 && it / 100000 == appUid / 100000 && it !in denied }
    val exceptions = resolverUids.distinct().filter { it >= 0 && it !in denied }
    // Android 的 per-app VPN 会把 uid 0-999（系统保留段，含 root）一并吸进 tun0。
    // 若这里不显式放行，以 root 运行的 DNS 服务（如 AdGuardHome）其上游 DoH 会被
    // 下面的 reject 整条挡掉；而 remoteDns/directDns 都指向它，等于全机 DNS 陪葬。
    // 只放行 DNS 相关出站端口，其余端口仍按原规则拒绝。
    val rootDnsAllowed = rootDnsPorts.isNotEmpty() && 0 !in denied
    if (allowed.isEmpty() && exceptions.isEmpty() && !rootDnsAllowed) {
        return Rule_DefaultOptions().apply {
            inbound = listOf("tun-in")
            action = "reject"
        }
    }
    return Rule_LogicalOptions().apply {
        type = "logical"
        mode = "and"
        action = "reject"
        rules = listOf(
            Rule_DefaultOptions().apply { inbound = listOf("tun-in") },
            Rule_LogicalOptions().apply {
                type = "logical"
                mode = "or"
                invert = true
                rules = buildList {
                    if (allowed.isNotEmpty()) add(Rule_DefaultOptions().apply { user_id = allowed })
                    if (exceptions.isNotEmpty()) {
                        add(
                            Rule_DefaultOptions().apply {
                                user_id = exceptions
                                port = listOf(53, 853)
                            },
                        )
                    }
                    if (rootDnsAllowed) {
                        add(
                            Rule_DefaultOptions().apply {
                                user_id = listOf(0)
                                port = rootDnsPorts
                            },
                        )
                    }
                }
            },
        )
    }
}
