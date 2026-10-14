package io.nekohasekai.sagernet.fmt

import java.net.URI

internal const val TAG_DIRECT_FALLBACK = "direct-fallback"
internal const val DNS_DIRECT_FALLBACK = "dns-direct-fallback"

internal fun directFallbackConfig(proxyTag: String, url: String, intervalSeconds: Int): Map<String, Any> {
    val uri = URI.create(url)
    require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
        uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535)) {
        "Invalid control site URL"
    }
    require(intervalSeconds in 5..3600) { "Check interval must be 5–3600 seconds" }
    require(proxyTag != "direct" && proxyTag != TAG_DIRECT_FALLBACK) { "Invalid fallback proxy" }
    return linkedMapOf(
        "type" to "urltest", "tag" to TAG_DIRECT_FALLBACK,
        "outbounds" to listOf("direct", proxyTag), "strategy" to "direct-fallback",
        "url" to url, "interval" to "${intervalSeconds}s", "timeout" to "3s",
        "idle_timeout" to "${intervalSeconds}s", "wait_for_initial" to true,
        "interrupt_exist_connections" to true
    )
}
