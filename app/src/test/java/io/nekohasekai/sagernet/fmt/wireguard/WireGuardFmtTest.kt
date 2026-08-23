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
    }
}
