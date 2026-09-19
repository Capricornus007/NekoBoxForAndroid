package io.nekohasekai.sagernet.fmt

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class HysteriaBeanSerializationTest {

    @Test
    fun defaults_keepEchDisabled() {
        val bean = HysteriaBean().apply { initializeDefaultValues() }

        assertEquals(false, bean.enableECH)
        assertEquals("", bean.echConfig)
    }

    @Test
    fun version9RoundTrip_preservesEch() {
        val bean = HysteriaBean().apply {
            protocolVersion = 2
            serverAddress = "example.com"
            serverPort = 443
            serverPorts = "443"
            authPayload = "password"
            enableECH = true
            echConfig = "AEb+DQBCAAAgACAHo3y8FCCTyLdV3BsQ6Gy0JjdK0WqoU+0L38CyuG0cfAAMAAEAAQABAAIAAQADAAtleGFtcGxlLmNvbQAA"
            name = "ECH profile"
            initializeDefaultValues()
        }

        val encoded = KryoConverters.serialize(bean)
        val decoded = KryoConverters.hysteriaDeserialize(encoded)!!

        assertEquals(true, decoded.enableECH)
        assertEquals(
            "AEb+DQBCAAAgACAHo3y8FCCTyLdV3BsQ6Gy0JjdK0WqoU+0L38CyuG0cfAAMAAEAAQABAAIAAQADAAtleGFtcGxlLmNvbQAA",
            decoded.echConfig,
        )
        assertEquals("ECH profile", decoded.name)
        assertArrayEquals(encoded, KryoConverters.serialize(decoded))
    }

    @Test
    fun version8Profile_defaultsEchDisabledWithoutLosingExistingFields() {
        val decoded = KryoConverters.hysteriaDeserialize(legacyVersion8Profile())!!

        assertEquals(2, decoded.protocolVersion)
        assertEquals("legacy.example.com", decoded.serverAddress)
        assertEquals("443", decoded.serverPorts)
        assertEquals("legacy-password", decoded.authPayload)
        assertEquals(HysteriaBean.OBFS_SALAMANDER, decoded.hysteria2ObfsType)
        assertEquals(512, decoded.geckoMinPacketSize)
        assertEquals(1200, decoded.geckoMaxPacketSize)
        assertEquals(false, decoded.enableECH)
        assertEquals("", decoded.echConfig)
        assertEquals("Legacy Hysteria 2", decoded.name)
    }

    @Test
    fun udpFragment_roundTripsEveryState() {
        listOf<Boolean?>(null, true, false).forEach { state ->
            val bean = HysteriaBean().apply {
                protocolVersion = 2
                serverAddress = "example.com"
                serverPort = 443
                serverPorts = "443"
                authPayload = "password"
                udpFragment = state
                initializeDefaultValues()
            }

            val encoded = KryoConverters.serialize(bean)
            val decoded = KryoConverters.hysteriaDeserialize(encoded)!!

            assertEquals(state, decoded.udpFragment)
            assertArrayEquals(encoded, KryoConverters.serialize(decoded))
        }
    }

    @Test
    fun version9Profile_leavesUdpFragmentUnset() {
        // Profiles stored before the udp_fragment field existed must keep the "not written"
        // state so the core default (fragmentation on) still applies to them.
        val decoded = KryoConverters.hysteriaDeserialize(legacyProfile(9))!!

        assertNull(decoded.udpFragment)
        assertEquals(false, decoded.enableECH)
        assertEquals("legacy-password", decoded.authPayload)
    }

    /** Writes the exact field order used by HysteriaBean schema versions 8 and 9. */
    private fun legacyProfile(version: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        ByteBufferOutput(bytes).use { output ->
            output.writeInt(version)

            // AbstractBean fields.
            output.writeString("legacy.example.com")
            output.writeInt(443)

            output.writeInt(2)
            output.writeInt(HysteriaBean.TYPE_STRING)
            output.writeString("legacy-password")
            output.writeInt(HysteriaBean.PROTOCOL_UDP)
            output.writeString("legacy-obfs")
            output.writeString("real.example.com")
            output.writeString("")
            output.writeInt(0)
            output.writeInt(0)
            output.writeBoolean(false)
            output.writeString("")
            output.writeInt(0)
            output.writeInt(0)
            output.writeBoolean(false)
            output.writeInt(10)
            output.writeString("443")
            output.writeInt(HysteriaBean.OBFS_SALAMANDER)
            output.writeInt(512)
            output.writeInt(1200)

            if (version >= 9) {
                output.writeBoolean(false)
                output.writeString("")
            }

            // AbstractBean trailing fields written by serializeToBuffer().
            output.writeInt(1)
            output.writeString("Legacy Hysteria 2")
            output.writeString("")
            output.writeString("")
            output.flush()
        }
        return bytes.toByteArray()
    }

    private fun legacyVersion8Profile(): ByteArray = legacyProfile(8)
}
