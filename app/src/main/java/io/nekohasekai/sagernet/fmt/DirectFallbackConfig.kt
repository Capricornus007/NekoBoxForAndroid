package io.nekohasekai.sagernet.fmt

import java.net.URI

internal const val TAG_DIRECT_FALLBACK = "direct-fallback"
internal const val DNS_DIRECT_FALLBACK = "dns-direct-fallback"

internal fun directFallbackConfig(proxyTag: String, url: String, intervalSeconds: Int, required: Int = 1, failures: Int = 1, recoveries: Int = 1, tag: String = TAG_DIRECT_FALLBACK): Map<String, Any> {
    val urls = url.lineSequence().map(String::trim).filter(String::isNotBlank).distinct().toList()
    require(urls.size in 1..8 && required in 1..urls.size && failures in 1..10 && recoveries in 1..10) { "Invalid fallback thresholds" }
    for (link in urls) {
        val uri = URI.create(link)
        require(
            uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() &&
                uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port in 1..65535),
        ) {
            "Invalid control site URL"
        }
    }
    require(intervalSeconds in 5..3600) { "Check interval must be 5–3600 seconds" }
    require(proxyTag != "direct" && proxyTag != tag) { "Invalid fallback proxy" }
    return linkedMapOf(
        "type" to "urltest", "tag" to tag,
        "outbounds" to listOf("direct", proxyTag), "strategy" to "direct-fallback",
        "url" to urls.first(), "control_urls" to urls, "required_successes" to required,
        "failure_threshold" to failures, "recovery_threshold" to recoveries, "interval" to "${intervalSeconds}s", "timeout" to "3s",
        "idle_timeout" to "${intervalSeconds}s", "wait_for_initial" to true,
        "interrupt_exist_connections" to true,
    )
}
