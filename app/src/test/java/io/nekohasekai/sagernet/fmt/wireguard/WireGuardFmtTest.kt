package io.nekohasekai.sagernet.fmt.wireguard

import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream

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

        val endpoint = buildSingBoxEndpointWireGuardBean(bean)

        assertEquals(listOf("172.16.0.2/32", "fd00::2/128"), endpoint.address)
        assertEquals("172.16.0.2\nfd00::2", bean.localAddress)
    }

    @Test
    fun endpointRoundTripPreservesModernFields() {
        val parsed = parseWireGuardEndpoint(
            JsonParser.parseString(
                """{"type":"wireguard","tag":"wg","address":["10.0.0.2/32"],"private_key":"private","listen_port":51821,"peers":[{"address":"vpn.example.com","port":51820,"public_key":"public","allowed_ips":["0.0.0.0/0"],"persistent_keepalive_interval":25,"reserved":[1,2,3]}]}""",
            ).asJsonObject,
        )!!

        val endpoint = buildSingBoxEndpointWireGuardBean(parsed)
        assertEquals("wg", parsed.name)
        assertEquals(51821, endpoint.listen_port)
        assertEquals(25, endpoint.peers.single().persistent_keepalive_interval)
        assertEquals(listOf(1, 2, 3), endpoint.peers.single().reserved)
    }

    @Test
    fun extraPeersFromJsonSurviveRoundTrip() {
        val parsed = parseWireGuardEndpoint(
            JsonParser.parseString(
                """{"type":"wireguard","tag":"wg","address":"10.0.0.2/32","private_key":"private","peers":[{"address":"vpn.example.com","port":51820,"public_key":"$PUBLIC_KEY","allowed_ips":["10.0.0.0/24"]},{"address":"[2001:db8::1]","port":51821,"public_key":"$SECOND_KEY","pre_shared_key":"$PRESHARED_KEY","allowed_ips":"198.51.100.7","persistent_keepalive_interval":25}]}""",
            ).asJsonObject,
        )!!

        assertEquals("10.0.0.0/24", parsed.peerAllowedIps)
        val peers = buildSingBoxEndpointWireGuardBean(parsed).peers
        assertEquals(2, peers.size)
        assertEquals(listOf("10.0.0.0/24"), peers[0].allowed_ips)
        assertEquals("2001:db8::1", peers[1].address)
        assertEquals(51821, peers[1].port)
        assertEquals(SECOND_KEY, peers[1].public_key)
        assertEquals(PRESHARED_KEY, peers[1].pre_shared_key)
        assertEquals(listOf("198.51.100.7/32"), peers[1].allowed_ips)
        assertEquals(25, peers[1].persistent_keepalive_interval)
    }

    @Test
    fun peerBlocksRoundTripWithoutLoss() {
        val blocks = formatWireGuardPeerBlocks(
            listOf(
                WireGuardPeerSpec(
                    host = "203.0.113.9",
                    port = 51820,
                    publicKey = "public",
                    allowedIPs = "192.0.2.0/24",
                ),
            ),
        )

        val parsed = parseWireGuardPeerBlocks(blocks).single()
        assertEquals("203.0.113.9", parsed.host)
        assertEquals(51820, parsed.port)
        assertEquals("public", parsed.publicKey)
        assertEquals("192.0.2.0/24", parsed.allowedIPs)
    }

    @Test
    fun emptyAllowedIpsKeepsFullTunnelDefault() {
        assertEquals(listOf("0.0.0.0/0", "::/0"), parseWireGuardAllowedIPs(""))
        assertEquals(listOf("10.0.0.0/24", "fd00::/64"), parseWireGuardAllowedIPs("10.0.0.0/24, fd00::/64"))
    }

    @Test
    fun legacyVersion3SerializationDefaultsNewFields() {
        val legacy = ByteArrayOutputStream().use { bytes ->
            ByteBufferOutput(bytes).use { output ->
                output.writeInt(3)
                output.writeString("legacy.example.com")
                output.writeInt(51820)
                output.writeString("10.0.0.2/32")
                output.writeString("private")
                output.writeString("public")
                output.writeString("")
                output.writeInt(1420)
                output.writeString("")
                repeat(7) { output.writeInt(0) }
                repeat(9) { output.writeString("") }
                output.writeInt(1)
                output.writeString("legacy")
                output.writeString("")
                output.writeString("")
                output.flush()
            }
            bytes.toByteArray()
        }

        val parsed = KryoConverters.wireguardDeserialize(legacy)!!
        assertEquals("legacy.example.com", parsed.serverAddress)
        assertEquals("legacy", parsed.name)
        assertEquals(0, parsed.listenPort)
        assertEquals(0, parsed.persistentKeepaliveInterval)
        assertEquals("", parsed.peerAllowedIps)
        assertEquals("", parsed.extraPeers)
    }

    @Test
    fun standardLinkExportRoundTrip() {
        val bean = WireGuardBean().applyDefaultValues().apply {
            name = "Home WG"
            serverAddress = "vpn.example.com"
            serverPort = 51820
            localAddress = "10.0.0.2/32\nfd00::2/128"
            privateKey = "A".repeat(43) + "="
            peerPublicKey = "B".repeat(43) + "="
            peerPreSharedKey = "C".repeat(43) + "="
            peerAllowedIps = "0.0.0.0/0, ::/0"
            mtu = 1420
            persistentKeepaliveInterval = 25
        }

        val link = bean.toUri()
        assertTrue(link.startsWith("wireguard://"))

        val parsed = parseWireGuardLink(link)
        assertEquals(bean.name, parsed.name)
        assertEquals(bean.serverAddress, parsed.serverAddress)
        assertEquals(bean.serverPort, parsed.serverPort)
        assertEquals(bean.localAddress, parsed.localAddress)
        assertEquals(bean.privateKey, parsed.privateKey)
        assertEquals(bean.peerPublicKey, parsed.peerPublicKey)
        assertEquals(bean.peerPreSharedKey, parsed.peerPreSharedKey)
        assertEquals(listOf("0.0.0.0/0", "::/0"), parseWireGuardAllowedIPs(parsed.peerAllowedIps))
        assertEquals(1420, parsed.mtu)
        assertEquals(25, parsed.persistentKeepaliveInterval)
    }

    @Test
    fun awgLinkCarriesObfuscationParameters() {
        val bean = parseWireGuardLink(
            "awg://" + "A".repeat(43) + "%3D@vpn.example.com:51820" +
                "?public_key=" + "B".repeat(43) + "%3D" +
                "&address=10.0.0.2%2F32" +
                "&jc=10&jmin=4&s1=1212&h1=1234567890123456789&i1=100",
        )

        assertTrue(bean.isAmneziaWG)
        assertEquals(10, bean.jc)
        assertEquals(4, bean.jmin)
        assertEquals(1212, bean.s1)
        assertEquals("1234567890123456789", bean.h1)
        assertEquals("100", bean.i1)
        assertEquals("vpn.example.com", bean.serverAddress)
        assertEquals("10.0.0.2/32", bean.localAddress)
        // 遮罩參數要能在回貼時活下來：匯出仍是 wireguard://，參數原樣帶出。
        val exported = bean.toUri()
        assertTrue(exported.startsWith("wireguard://"))
        assertTrue(exported.contains("jc=10"))
        assertTrue(exported.contains("h1=1234567890123456789"))
    }

    private companion object {
        const val PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val SECOND_KEY = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        const val PRESHARED_KEY = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC="
    }
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

    @Test fun readsVersion6ProfileWithoutChangingKeepaliveOrIdentity() {
        // 合併後 v4/v5 是 fork 的欄位（listenPort/persistentKeepaliveInterval/peerAllowedIps/extraPeers），
        // isai 的 WG 選項與 Amnezia 參數自 v6 起追加；本測試按合併後 v6 佈局驗證 keepalive／身分不變。
        val output=com.esotericsoftware.kryo.io.ByteBufferOutput(4096)
        output.writeInt(6)
        output.writeString("legacy.example");output.writeInt(51820)
        output.writeString("10.0.0.2/32");output.writeString("private-fixture");output.writeString("public-fixture");output.writeString("psk-fixture")
        output.writeInt(1280);output.writeString("")
        for (number in listOf(2,32,64,15,18,20,25)) output.writeInt(number)
        for (text in listOf("100-200","201-300","301-400","401-500","<t>","","","","")) output.writeString(text)
        output.writeInt(0)              // listenPort（v4）
        output.writeInt(0)              // persistentKeepaliveInterval（v4）
        output.writeString("");output.writeString("")   // peerAllowedIps / extraPeers（v5）
        output.writeString("1.1.1.1");output.writeString("0.0.0.0/0");output.writeString("25");output.writeBoolean(true)
        output.writeString("");output.writeString("");output.writeString("");output.writeString("")
        output.writeString("");output.writeString("");output.writeString("")
        output.writeBoolean(false);output.writeBoolean(false)
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
