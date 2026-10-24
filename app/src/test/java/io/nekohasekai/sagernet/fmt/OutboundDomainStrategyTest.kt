package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.MyOptions
import moe.matsuri.nb4a.SingBoxOptions.RouteOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OutboundDomainStrategyTest {

    @Test
    fun skipsDomainStrategyForGroupOutbounds() {
        listOf("urltest", "selector").forEach { type ->
            val outbound = CustomSingBoxOption(
                """{"type":"$type","outbounds":["direct","proxy"]}""",
            )

            outbound.applyDomainStrategyIfSupported("prefer_ipv4")

            assertFalse(outbound.asMap().containsKey("domain_strategy"))
            assertFalse(outbound.asMap().containsKey("domain_resolver"))
        }
    }

    @Test
    fun preservesDomainStrategyForServerOutbounds() {
        val outbound = CustomSingBoxOption(
            """{"type":"socks","server":"example.com","server_port":1080}""",
        )

        outbound.applyDomainStrategyIfSupported("prefer_ipv4")

        assertEquals("prefer_ipv4", outbound.asMap()["domain_strategy"])
        assertFalse(outbound.asMap().containsKey("domain_resolver"))
    }

    @Test fun preservesUserResolverAndLegacyStrategyOverrides() {
        val custom = CustomSingBoxOption("""{"type":"socks","domain_resolver":{"server":"user-dns","strategy":"ipv6_only"}}""")
        custom.applyDomainStrategyIfSupported("prefer_ipv4")
        assertEquals(mapOf("server" to "user-dns", "strategy" to "ipv6_only"), custom.asMap()["domain_resolver"])
        val legacy = CustomSingBoxOption("""{"type":"socks","domain_strategy":"ipv6_only"}""")
        legacy.applyDomainStrategyIfSupported("prefer_ipv4")
        assertEquals("ipv6_only", legacy.asMap()["domain_strategy"])
        assertFalse(legacy.asMap().containsKey("domain_resolver"))
    }

    @Test fun globalDnsOverrideDoesNotLeaveAnOutboundBoundToTheRemovedServer() {
        val outbound = CustomSingBoxOption("""{"type":"http","server":"proxy.example.test","server_port":8080}""")
        outbound.applyDomainStrategyIfSupported("ipv4_only")
        val config = MyOptions().apply {
            outbounds = listOf(outbound)
            route = RouteOptions()
            _hack_custom_config = """{"dns":{"servers":[{"type":"local","tag":"user-dns"}],"final":"user-dns"},"route":{"default_domain_resolver":"user-dns"}}"""
        }.asMap()
        val generatedOutbound = (config["outbounds"] as List<*>).single() as Map<*, *>
        assertFalse(generatedOutbound.containsKey("domain_resolver"))
        assertEquals("ipv4_only", generatedOutbound["domain_strategy"])
        assertEquals("user-dns", (config["route"] as Map<*, *>)["default_domain_resolver"])
    }
}
