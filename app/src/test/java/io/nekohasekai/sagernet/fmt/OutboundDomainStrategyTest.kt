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
                """{"type":"$type","outbounds":["direct","proxy"]}""",
            )

            outbound.applyDomainStrategyIfSupported("prefer_ipv4")

            assertFalse(outbound.asMap().containsKey("domain_strategy"))
        }
    }

    @Test
    fun preservesDomainStrategyForServerOutbounds() {
        val outbound = CustomSingBoxOption(
            """{"type":"socks","server":"example.com","server_port":1080}""",
        )

        outbound.applyDomainStrategyIfSupported("prefer_ipv4")

        assertEquals("prefer_ipv4", outbound.asMap()["domain_strategy"])
    }
}
