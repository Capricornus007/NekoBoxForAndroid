package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.*

internal fun perAppTunGuard(
    selectedUids: Set<Int>,
    installedUids: Set<Int>,
    appUid: Int,
    bypass: Boolean,
    resolverUids: List<Int>,
): Rule {
    // Full UIDs identify both the package and Android profile. Never reduce to appId.
    val denied = if (bypass) selectedUids + 1000 else emptySet()
    val allowed = ((if (bypass) installedUids else selectedUids) + appUid)
        .filter { it >= 0 && it / 100000 == appUid / 100000 && it !in denied }
    val exceptions = resolverUids.distinct().filter { it >= 0 && it !in denied }
    if (allowed.isEmpty() && exceptions.isEmpty()) {
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
                }
            },
        )
    }
}
