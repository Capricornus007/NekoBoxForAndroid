package io.nekohasekai.sagernet.group

import com.google.gson.Gson
import io.nekohasekai.sagernet.fmt.AbstractBean

/** Field categories only: callers must never render serialized values. */
internal fun subscriptionChanges(before: AbstractBean, after: AbstractBean): Set<String> {
    if (before.javaClass != after.javaClass) return setOf("protocol")
    val gson = Gson()
    val old = gson.toJsonTree(before).asJsonObject
    val new = gson.toJsonTree(after).asJsonObject
    return (old.keySet() + new.keySet()).filter { old[it] != new[it] }
        .filterNot { it in setOf("customOutboundJson", "customConfigJson", "useConfigRoutes") }
        .map { field ->
            when {
                field == "name" -> "name"
                field in setOf("serverAddress", "serverPort") -> "address"
                field.contains("dns", true) -> "dns"
                field.contains("allowed", true) -> "allowed_ips"
                field.contains("tls", true) || field.contains("sni", true) || field.contains("certificate", true) || field.contains("alpn", true) || field.contains("security", true) -> "tls"
                field.contains("key", true) || field.contains("password", true) || field.contains("uuid", true) || field.contains("token", true) -> "credentials"
                else -> "parameters"
            }
        }.toSet()
}
