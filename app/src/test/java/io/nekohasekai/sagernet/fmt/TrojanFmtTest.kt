package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrojanFmtTest {

    @Test
    fun plainLinkStillParses() {
        val bean = parseTrojan("trojan://secretpw@example.com:443?allowInsecure=1&sni=cdn.example.com#node")

        assertEquals("example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("secretpw", bean.password)
        assertEquals("cdn.example.com", bean.sni)
        assertEquals("node", bean.name)
    }

    @Test
    fun cjkAndSpaceInRemarkSurvive() {
        val bean = parseTrojan("trojan://secretpw@example.com:443?allowInsecure=1#東京 節點")

        assertEquals("example.com", bean.serverAddress)
        assertEquals("東京 節點", bean.name)
    }

    @Test
    fun unencodedBracesInQueryAreRecovered() {
        val bean = parseTrojan(
            "trojan://secretpw@example.com:443?headers={\"Host\":\"cdn.example.com\"}",
        )

        assertEquals("example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
    }

    @Test
    fun spaceInQueryValueIsRecovered() {
        val bean = parseTrojan("trojan://secretpw@example.com:443?sni=cdn example.com#x")

        assertEquals("example.com", bean.serverAddress)
        assertEquals("x", bean.name)
    }
}
