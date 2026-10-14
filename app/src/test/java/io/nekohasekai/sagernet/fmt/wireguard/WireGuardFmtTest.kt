package io.nekohasekai.sagernet.fmt.wireguard

import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WireGuardFmtTest {
    @Test fun keepaliveRetainsFullTunnelUnlessImportedRoutesAreEnabled() {
        val bean = WireGuardBean().applyDefaultValues().apply {
            localAddress = "10.0.0.2/32"
            allowedIPs = "10.0.0.0/24, fd00::/64"
            persistentKeepalive = 25
        }
        fun peer(): Map<*, *> = (buildSingBoxOutboundWireguardBean(bean)._hack_config_map["peers"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("0.0.0.0/0", "::/0"), peer()["allowed_ips"])
        assertEquals(25, peer()["persistent_keepalive_interval"])
        bean.useConfigRoutes = true
        assertEquals(listOf("10.0.0.0/24", "fd00::/64"), peer()["allowed_ips"])
        bean.persistentKeepalive = 65536
        assertThrows(IllegalArgumentException::class.java) { peer() }
    }

    @Test
    fun outboundNormalizesBareAddressesFromExistingProfiles() {
        val bean = WireGuardBean().applyDefaultValues().apply {
            localAddress = "172.16.0.2\nfd00::2"
        }

        val outbound = buildSingBoxOutboundWireguardBean(bean)

        assertEquals(listOf("172.16.0.2/32", "fd00::2/128"), outbound.local_address)
        assertEquals("172.16.0.2\nfd00::2", bean.localAddress)
    }

    @Test
    fun mapsEveryAwg2ParameterToCoreOptions() {
        val bean = WireGuardBean().applyDefaultValues().apply {
            localAddress = "10.0.0.2/32"
            jc = 2; jmin = 32; jmax = 64
            s1 = 15; s2 = 18; s3 = 20; s4 = 25
            h1 = "123456-123500"; h2 = "67543-67550"; h3 = "123123-123200"; h4 = "32345-32350"
            i1 = "<b 0x01020304>"; i2 = "<r 16>"; i3 = "<rc 8>"; i4 = "<rd 8>"; i5 = "<t>"
        }
        val options = buildSingBoxOutboundWireguardBean(bean)._hack_config_map
        mapOf("jc" to 2, "jmin" to 32, "jmax" to 64, "s1" to 15, "s2" to 18, "s3" to 20, "s4" to 25,
            "h1" to bean.h1, "h2" to bean.h2, "h3" to bean.h3, "h4" to bean.h4,
            "i1" to bean.i1, "i2" to bean.i2, "i3" to bean.i3, "i4" to bean.i4, "i5" to bean.i5).forEach { (key, value) ->
            assertEquals(key, value, options[key])
        }
    }

    @Test
    fun rejectsInvalidParametersFromExistingProfilesBeforeStartingCore() {
        for (change in listOf<WireGuardBean.() -> Unit>({ s4 = -1 }, { jc = 1; jmin = 100; jmax = 50 }, { i1 = "<t>\nprivate_key=wrong" })) {
            val bean = WireGuardBean().applyDefaultValues().apply {
                localAddress = "10.0.0.2/32"
                change()
            }
            assertThrows(IllegalArgumentException::class.java) { buildSingBoxOutboundWireguardBean(bean) }
        }
    }
}
