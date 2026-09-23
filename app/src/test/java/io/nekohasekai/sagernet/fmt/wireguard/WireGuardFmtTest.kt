package io.nekohasekai.sagernet.fmt.wireguard

import com.esotericsoftware.kryo.io.ByteBufferOutput
import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class WireGuardFmtTest {

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

    private companion object {
        const val PUBLIC_KEY = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        const val SECOND_KEY = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB="
        const val PRESHARED_KEY = "CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC="
    }
}
