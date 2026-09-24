package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.snell.parseSnell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SnellFmtTest {

    @Test
    fun unencodedSlashInPskKeepsRealAddress() {
        val bean = parseSnell("snell://YWJjL2RlZg/def@198.51.100.7:8443?version=4#slash")

        assertEquals("198.51.100.7", bean.serverAddress)
        assertEquals(8443, bean.serverPort)
        assertEquals("YWJjL2RlZg/def", bean.psk)
        assertEquals("slash", bean.name)
    }

    @Test
    fun lenientPathAcceptsObfsParameterAliases() {
        val bean = parseSnell(
            "snell://YWJjL2RlZg/def@198.51.100.7:8443?version=3&obfsmode=http&obfshost=bing.com&reuse=1",
        )

        assertEquals("http", bean.obfsMode)
        assertEquals("bing.com", bean.obfsHost)
        assertTrue(bean.reuse)
    }

    @Test
    fun lenientPathParsesBracketedIpv6() {
        val bean = parseSnell("snell://YWJjL2RlZg/def@[2001:db8::1]:8443?version=6")

        assertEquals("2001:db8::1", bean.serverAddress)
        assertEquals(8443, bean.serverPort)
    }

    @Test
    fun standardLinkStillUsesStrictPath() {
        val bean = parseSnell("snell://cHNr@example.com:8443?version=6&mode=unshaped#ok")

        assertEquals("example.com", bean.serverAddress)
        assertEquals(8443, bean.serverPort)
        assertEquals("cHNr", bean.psk)
        assertEquals("unshaped", bean.mode)
        assertEquals("ok", bean.name)
    }
}
