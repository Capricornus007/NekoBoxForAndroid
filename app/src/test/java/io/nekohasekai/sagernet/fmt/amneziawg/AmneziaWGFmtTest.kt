package io.nekohasekai.sagernet.fmt.amneziawg

import com.esotericsoftware.kryo.io.ByteBufferOutput
import io.nekohasekai.sagernet.fmt.KryoConverters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream

class AmneziaWGFmtTest {

    @Test
    fun version1RoundTripPreservesModernFields() {
        val bean = AmneziaWGBean().apply {
            initializeDefaultValues()
            serverAddress = "vpn.example.com"
            serverPort = 51820
            localAddress = "10.0.0.2/32"
            privateKey = "private"
            peerPublicKey = "public"
            listenPort = 51821
            persistentKeepaliveInterval = 25
            jc = 4
            h1 = "123"
        }

        val encoded = KryoConverters.serialize(bean)
        val decoded = KryoConverters.amneziaWGDeserialize(encoded)!!

        assertEquals(51821, decoded.listenPort)
        assertEquals(25, decoded.persistentKeepaliveInterval)
        assertEquals(4, decoded.jc)
        assertEquals("123", decoded.h1)
        assertArrayEquals(encoded, KryoConverters.serialize(decoded))
    }

    @Test
    fun legacyVersion0DefaultsModernFields() {
        val legacy = ByteArrayOutputStream().use { bytes ->
            ByteBufferOutput(bytes).use { output ->
                output.writeInt(0)
                output.writeString("legacy.example.com")
                output.writeInt(51820)
                output.writeString("10.0.0.2/32")
                output.writeString("private")
                output.writeString("public")
                output.writeString("")
                output.writeInt(1420)
                output.writeString("1, 2, 3")
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

        val decoded = KryoConverters.amneziaWGDeserialize(legacy)!!
        assertEquals("legacy.example.com", decoded.serverAddress)
        assertEquals("legacy", decoded.name)
        assertEquals(0, decoded.listenPort)
        assertEquals(0, decoded.persistentKeepaliveInterval)
    }
}
