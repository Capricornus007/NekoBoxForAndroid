package io.nekohasekai.sagernet.fmt.v2ray

import io.nekohasekai.sagernet.ktx.Logs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parser-compatibility tests for vless/vmess links (ported from Throne 5d47579e9, task 6.4/6.5):
 * a hostile query string, a `net=` transport alias and an unknown kcp header type must all
 * still produce a usable node instead of throwing the link away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class V2RayParseCompatTest {

    private lateinit var originalLogSink: (String) -> Unit

    @Before
    fun setUp() {
        originalLogSink = Logs.sink
        Logs.sink = {}
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    @Test
    fun queryWithSpecialCharactersParses() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                "?type=ws&host=a{b|c}#node",
        )

        assertEquals("srv.example.com", bean.serverAddress)
        assertEquals(443, bean.serverPort)
        assertEquals("a{b|c}", bean.host)
        assertEquals("ws", bean.type)
    }

    @Test
    fun queryWithNonAsciiValueParses() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                "?type=ws&path=/节点#香港 01",
        )

        assertEquals("ws", bean.type)
        assertEquals("/节点", bean.path)
        assertEquals("香港 01", bean.name)
    }

    @Test
    fun invalidKcpHeaderTypeFallsBackToNone() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                "?type=kcp&headerType=banana&seed=seed1#node",
        )

        assertEquals("kcp", bean.type)
        assertEquals("none", bean.headerType)
    }

    @Test
    fun validKcpHeaderTypeKept() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                "?type=kcp&headerType=srtp&seed=seed1#node",
        )

        assertEquals("srtp", bean.headerType)
    }

    @Test
    fun netParameterRecognizedAsTransport() {
        val bean = parseV2Ray(
            "vless://00000000-0000-0000-0000-000000000001@srv.example.com:443" +
                "?net=ws&path=%2Fp&host=h1#node",
        )

        assertEquals("ws", bean.type)
        assertEquals("/p", bean.path)
        assertEquals("h1", bean.host)
    }
}
