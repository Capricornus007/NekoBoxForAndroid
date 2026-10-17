package io.nekohasekai.sagernet.fmt

// Add only resolvers still referenced after the user's final JSON merge.
// A dedicated Android bootstrap avoids depending on a replaced dns-direct tag.
@Suppress("UNCHECKED_CAST")
internal fun applySubscriptionDns(config: MutableMap<String, Any>, resolvers: Map<String, String>) {
    val outbounds = config["outbounds"] as? List<Map<String, Any>> ?: return
    val used = outbounds.mapNotNull {
        val resolver = it["domain_resolver"]
        if (resolver is String) resolver else (resolver as? Map<*, *>)?.get("server") as? String
    }.toSet()
    val required = resolvers.filterKeys { it in used }
    if (required.isEmpty()) return
    val dns = (config["dns"] as? MutableMap<String, Any>) ?: linkedMapOf<String, Any>().also { config["dns"] = it }
    val servers = ((dns["servers"] as? List<Map<String, Any>>).orEmpty()).toMutableList()
    val bootstrap = "dns-subscription-bootstrap"
    if (servers.none { it["tag"] == bootstrap }) servers.add(dnsServerConfig("local", bootstrap, "", "direct"))
    for ((tag, address) in required) {
        if (servers.none { it["tag"] == tag }) servers.add(dnsServerConfig(address, tag, bootstrap, "direct"))
    }
    dns["servers"] = servers
}
