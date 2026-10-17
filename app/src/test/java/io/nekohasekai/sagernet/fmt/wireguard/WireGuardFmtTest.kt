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
            persistentKeepalive = "25"
        }
        fun peer(): Map<*, *> = (buildSingBoxOutboundWireguardBean(bean)._hack_config_map["peers"] as List<*>).single() as Map<*, *>
        assertEquals(listOf("0.0.0.0/0", "::/0"), peer()["allowed_ips"])
        assertEquals("25", peer()["persistent_keepalive_interval"])
        bean.useConfigRoutes = true
        assertEquals(listOf("10.0.0.0/24", "fd00::/64"), peer()["allowed_ips"])
        bean.persistentKeepalive = "4294967296"
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
    @Test fun mapsAwg31OptionsAndRangedKeepaliveWithoutChangingImportedRoutes() {
        val bean = WireGuardBean().applyDefaultValues().apply {
            localAddress = "10.0.0.2/32"
            persistentKeepalive = "22-30"
            s1 = 12; s2 = 12; s3 = 12; s4 = 12
            headerProtectionKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
            contentPaddingAddition = "0-96"; rekeyAfterTime = "100-120"; rekeyTimeout = "5-8"
            rejectAfterTime = "180-200"; keepaliveTimeout = "10-15"; maxHandshakeAttempts = "10-18"
            randomTrailers = true; disableCookies = true
        }
        val values = buildSingBoxOutboundWireguardBean(bean)._hack_config_map
        mapOf("header_protection_key" to bean.headerProtectionKey, "content_padding_addition" to "0-96",
            "rekey_after_time" to "100-120", "rekey_timeout" to "5-8", "reject_after_time" to "180-200",
            "keepalive_timeout" to "10-15", "max_handshake_attempts" to "10-18", "random_trailers" to true,
            "disable_cookies" to true).forEach { (key,value) -> assertEquals(key,value,values[key]) }
        val peer = (values["peers"] as List<*>).single() as Map<*,*>
        assertEquals("22-30", peer["persistent_keepalive_interval"])
        assertEquals(listOf("0.0.0.0/0","::/0"),peer["allowed_ips"])
        assertEquals(true,bean.hasAmneziaV3Options())
        val restored = bean.clone()
        assertEquals(bean.headerProtectionKey,restored.headerProtectionKey)
        assertEquals("22-30",restored.persistentKeepalive)
        assertEquals(moe.matsuri.nb4a.utils.JavaUtil.gson.toJson(bean), moe.matsuri.nb4a.utils.JavaUtil.gson.toJson(restored))
        assertEquals("10-18",restored.maxHandshakeAttempts)
        assertEquals(true,restored.disableCookies)
    }

    @Test fun rejectsInvalidAwg31OptionsWithoutRevealingKeys() {
        for (change in listOf<WireGuardBean.() -> Unit>(
            { rekeyAfterTime = "30-20" }, { contentPaddingAddition = "0-4294967295" },
            { maxHandshakeAttempts = "-1" }, { persistentKeepalive = "25\nprivate_key=fixture" },
            { headerProtectionKey = "secret-invalid-key" },
            { headerProtectionKey = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 }) }
        )) {
            val bean=WireGuardBean().applyDefaultValues().apply { localAddress="10.0.0.2/32";change() }
            val error=assertThrows(IllegalArgumentException::class.java){buildSingBoxOutboundWireguardBean(bean)}
            org.junit.Assert.assertFalse(error.message.orEmpty().contains("secret-invalid-key"))
        }
    }

    @Test fun readsVersion4ProfileWithoutChangingKeepaliveOrIdentity() {
        val output=com.esotericsoftware.kryo.io.ByteBufferOutput(4096)
        output.writeInt(4)
        output.writeString("legacy.example");output.writeInt(51820)
        output.writeString("10.0.0.2/32");output.writeString("private-fixture");output.writeString("public-fixture");output.writeString("psk-fixture")
        output.writeInt(1280);output.writeString("")
        for (number in listOf(2,32,64,15,18,20,25)) output.writeInt(number)
        for (text in listOf("100-200","201-300","301-400","401-500","<t>","","","","")) output.writeString(text)
        output.writeString("1.1.1.1");output.writeString("0.0.0.0/0");output.writeInt(25);output.writeBoolean(true)
        output.writeInt(1);output.writeString("Legacy profile");output.writeString("{}");output.writeString("{}")
        val bean=io.nekohasekai.sagernet.fmt.KryoConverters.deserialize(WireGuardBean(),output.toBytes())
        assertEquals("25",bean.persistentKeepalive)
        assertEquals("Legacy profile",bean.name)
        assertEquals("legacy.example",bean.serverAddress)
        assertEquals("private-fixture",bean.privateKey)
        assertEquals("",bean.headerProtectionKey)
        assertEquals(false,bean.randomTrailers)
        assertEquals(false,bean.hasAmneziaV3Options())
        assertEquals("25",bean.clone().persistentKeepalive)
    }

}
