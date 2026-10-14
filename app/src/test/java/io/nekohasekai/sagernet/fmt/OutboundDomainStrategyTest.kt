package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutboundDomainStrategyTest {

    @Test
    fun skipsDomainStrategyForGroupOutbounds() {
        listOf("urltest", "selector").forEach { type ->
            val outbound = CustomSingBoxOption(
                """{"type":"$type","outbounds":["direct","proxy"]}"""
            )

            outbound.applyDomainStrategyIfSupported("prefer_ipv4")

            assertFalse(outbound.asMap().containsKey("domain_strategy"))
            assertFalse(outbound.asMap().containsKey("domain_resolver"))
        }
    }

    @Test
    fun preservesDomainStrategyForServerOutbounds() {
        val outbound = CustomSingBoxOption(
            """{"type":"socks","server":"example.com","server_port":1080}"""
        )

        outbound.applyDomainStrategyIfSupported("prefer_ipv4")

        assertEquals(mapOf("server" to "dns-direct", "strategy" to "prefer_ipv4"), outbound.asMap()["domain_resolver"])
        assertFalse(outbound.asMap().containsKey("domain_strategy"))
    }

    @Test fun preservesUserResolverAndLegacyStrategyOverrides() {
        val custom = CustomSingBoxOption("""{"type":"socks","domain_resolver":{"server":"user-dns","strategy":"ipv6_only"}}""")
        custom.applyDomainStrategyIfSupported("prefer_ipv4")
        assertEquals(mapOf("server" to "user-dns", "strategy" to "ipv6_only"), custom.asMap()["domain_resolver"])
        val legacy = CustomSingBoxOption("""{"type":"socks","domain_strategy":"ipv6_only"}""")
        legacy.applyDomainStrategyIfSupported("prefer_ipv4")
        assertEquals(mapOf("server" to "dns-direct", "strategy" to "ipv6_only"), legacy.asMap()["domain_resolver"])
    }
}
