package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class SubscriptionDnsConfigTest {
    @Test fun customDnsReplacementKeepsAnIndependentBootstrap() {
        val config = linkedMapOf<String, Any>(
            "outbounds" to listOf(mapOf("type" to "http", "domain_resolver" to mapOf("server" to "dns-sub-1"))),
            "dns" to linkedMapOf("servers" to listOf(mapOf("type" to "local", "tag" to "user-system"))),
        )
        applySubscriptionDns(config, mapOf("dns-sub-1" to "https://resolver.example/dns-query?account=fixture"))
        val dns = config["dns"] as Map<*, *>
        val servers = dns["servers"] as List<Map<String, Any>>
        assertEquals(3, servers.size)
        val resolver = servers.single { it["tag"] == "dns-sub-1" }
        assertEquals("dns-subscription-bootstrap", resolver["domain_resolver"])
        assertEquals("/dns-query?account=fixture", resolver["path"])
        assertFalse(resolver.containsKey("detour"))
    }

    @Test fun explicitUserResolversAndRemovedOutboundsArePreserved() {
        val config = linkedMapOf<String, Any>("outbounds" to listOf(mapOf("domain_resolver" to "user-resolver")))
        applySubscriptionDns(config, mapOf("dns-sub-1" to "1.1.1.1"))
        assertFalse(config.containsKey("dns"))
    }

    @Test fun aUserOverrideOfTheSameTagIsNotReplaced() {
        val original = mapOf("tag" to "dns-sub-1", "type" to "local")
        val config = linkedMapOf<String, Any>("outbounds" to listOf(mapOf("domain_resolver" to "dns-sub-1")), "dns" to linkedMapOf("servers" to listOf(original)))
        applySubscriptionDns(config, mapOf("dns-sub-1" to "9.9.9.9"))
        assertEquals(original, ((config["dns"] as Map<*, *>)["servers"] as List<Map<String, Any>>).single { it["tag"] == "dns-sub-1" })
    }
}
