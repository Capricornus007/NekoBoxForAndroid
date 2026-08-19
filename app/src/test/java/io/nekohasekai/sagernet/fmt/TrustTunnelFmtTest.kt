package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.trusttunnel.parseTrustTunnel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

class TrustTunnelFmtTest {

    @Test
    fun legacyLinkRemainsSupported() {
        val bean = parseTrustTunnel(
            "tt://user:pass@example.com:8443?sni=cdn.example.com&quic=1#legacy",
        ).single()

        assertEquals("example.com", bean.serverAddress)
        assertEquals(8443, bean.serverPort)
        assertEquals("user", bean.username)
        assertEquals("pass", bean.password)
        assertEquals("cdn.example.com", bean.sni)
        assertTrue(bean.quic)
        assertEquals("legacy", bean.name)
    }

    @Test
    fun officialLinkCreatesOneProfilePerAddress() {
        val link = officialLink(
            field(0x01, "verify.example"),
            field(0x02, "edge.example:443"),
            field(0x02, "[2001:db8::7]:8443"),
            field(0x05, "alice"),
            field(0x06, "secret"),
            field(0x07, byteArrayOf(1)),
            field(0x09, byteArrayOf(2)),
            field(0x0C, "office"),
            field(0x00, byteArrayOf(1)),
        )

        val beans = parseTrustTunnel(link)
        assertEquals(2, beans.size)
        assertEquals("edge.example", beans[0].serverAddress)
        assertEquals(443, beans[0].serverPort)
        assertEquals("2001:db8::7", beans[1].serverAddress)
        assertEquals(8443, beans[1].serverPort)
        beans.forEach {
            assertEquals("alice", it.username)
            assertEquals("secret", it.password)
            assertEquals("verify.example", it.sni)
            assertEquals("office", it.name)
            assertTrue(it.allowInsecure)
            assertTrue(it.quic)
        }
    }

    @Test
    fun officialHttp2AndBareAddressUseCompatibleDefaults() {
        val beans = parseTrustTunnel(
            officialLink(
                field(0x01, "example.com"),
                field(0x02, "192.0.2.10"),
                field(0x02, "2001:db8::10"),
                field(0x05, "user"),
                field(0x06, "pass"),
                field(0x09, byteArrayOf(1)),
            ),
        )

        assertEquals(2, beans.size)
        assertEquals("192.0.2.10", beans[0].serverAddress)
        assertEquals("2001:db8::10", beans[1].serverAddress)
        beans.forEach {
            assertEquals(443, it.serverPort)
            assertFalse(it.quic)
            assertFalse(it.allowInsecure)
        }
    }

    @Test
    fun separateCustomSniAndVerificationNameFailsClosed() {
        val link = officialLink(
            field(0x01, "verify.example"),
            field(0x02, "edge.example:443"),
            field(0x03, "front.example"),
            field(0x05, "user"),
            field(0x06, "pass"),
        )

        assertThrows(IllegalArgumentException::class.java) { parseTrustTunnel(link) }
    }

    @Test
    fun truncatedOfficialPayloadIsRejected() {
        val truncated = officialLink(byteArrayOf(0x02, 0x05, 'a'.code.toByte()))
        assertThrows(IllegalArgumentException::class.java) { parseTrustTunnel(truncated) }
    }

    private fun officialLink(vararg fields: ByteArray): String {
        val payload = fields.fold(ByteArrayOutputStream()) { stream, bytes ->
            stream.apply { write(bytes) }
        }.toByteArray()
        return "tt://?" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    private fun field(tag: Int, value: String) = field(tag, value.toByteArray())

    private fun field(tag: Int, value: ByteArray): ByteArray {
        require(tag in 0..63 && value.size in 0..63)
        return byteArrayOf(tag.toByte(), value.size.toByte()) + value
    }
}
