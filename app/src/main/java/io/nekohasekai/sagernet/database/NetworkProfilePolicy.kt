package io.nekohasekai.sagernet.database

/** Empty DNS fields inherit global settings. SSID matching is exact and case-sensitive. */
data class NetworkProfilePolicy(
    val id: String = java.util.UUID.randomUUID().toString(),
    val transport: String = "wifi",
    val ssid: String = "",
    val profileId: Long = 0L,
    val remoteDns: String = "",
    val directDns: String = "",
)

internal fun matchNetworkProfile(policies: List<NetworkProfilePolicy>, transport: String, ssid: String?): NetworkProfilePolicy? {
    val candidates = policies.filter { it.transport == transport }
    return ssid?.takeIf { it.isNotBlank() }?.let { name -> candidates.firstOrNull { it.ssid == name } }
        ?: candidates.firstOrNull { it.ssid.isBlank() }
}
