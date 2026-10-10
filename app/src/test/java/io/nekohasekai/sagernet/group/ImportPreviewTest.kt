package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.group.ImportPreview.Reason
import io.nekohasekai.sagernet.group.ImportPreview.Section
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ImportPreviewTest {
    private lateinit var originalLogSink: (String) -> Unit

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        originalLogSink = Logs.sink
        Logs.sink = {}
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    private suspend fun preview(text: String) = ImportPreview.of(RawUpdater.parseImport(text)!!)

    @Test
    fun singBoxConfigReportsBuiltinsUnsupportedOutboundsAndDroppedSections() = runTest {
        val text = """
            {"log": {}, "dns": {"servers": []}, "inbounds": [{"type": "tun"}],
             "outbounds": [{"type": "socks", "tag": "a", "server": "192.0.2.1", "server_port": 1080},
                           {"type": "socks", "tag": "b", "server": "192.0.2.2", "server_port": 1080},
                           {"type": "direct"}, {"type": "selector", "outbounds": ["a"]}, {"type": "mystery"}, "not an outbound"],
             "route": {"rules": []}}
        """.trimIndent()
        val preview = preview(text)

        assertEquals(2, preview.acceptedCount)
        assertEquals(mapOf(Reason.BUILTIN to 2, Reason.UNPARSED to 2), preview.skipped)
        assertEquals(listOf("mystery", null), preview.failed)
        assertEquals(setOf(Section.RULES, Section.DNS, Section.INBOUNDS), preview.dropped.toSet())
        assertTrue(preview.behaviorDiffers)
    }

    @Test
    fun clashBlockFlowAndJsonLayoutsCountTheSameNodes() = runTest {
        val block = """
            port: 7890
            dns:
              enable: true
            proxies:
              - name: one
                type: socks5
                server: 192.0.2.1
                port: 1080
                alpn:
                  - h2
              - {name: two, type: socks5, server: 192.0.2.2, port: 1080}
              - name: broken
                type: nope
              - just a string
            proxy-groups:
              - name: auto
            rules:
              - MATCH,auto
        """.trimIndent()
        val flow = """
            {proxies: [{name: one, type: socks5, server: 192.0.2.1, port: 1080}, {name: two, type: socks5, server: 192.0.2.2, port: 1080},
              {name: broken, type: nope}, just a string], proxy-groups: [{name: auto}], rules: [MATCH], dns: {enable: true}}
        """.trimIndent()
        val json = """
            {"proxies": [{"name": "one", "type": "socks5", "server": "192.0.2.1", "port": 1080},
                         {"name": "two", "type": "socks5", "server": "192.0.2.2", "port": 1080},
                         {"name": "broken", "type": "nope"}, "just a string"],
             "proxy-groups": [{"name": "auto"}], "rules": ["MATCH"], "dns": {"enable": true}}
        """.trimIndent()
        for (text in listOf(block, flow, json)) {
            val preview = preview(text)
            assertEquals(text, 2, preview.acceptedCount)
            assertEquals(text, mapOf(Reason.UNPARSED to 2), preview.skipped)
            assertEquals(text, listOf("nope", null), preview.failed)
            assertEquals(text, setOf(Section.PROXY_GROUPS, Section.RULES, Section.DNS), preview.dropped.toSet())
        }
    }

    @Test
    fun nestedJsonContainersReportEachEntry() = runTest {
        val text = """
            [{"outbounds": [{"type": "socks", "tag": "a", "server": "192.0.2.1", "server_port": 1080}, {"type": "naive", "tag": "n"}]},
             [{"type": "socks", "tag": "b", "server": "192.0.2.2", "server_port": 1080}, {"type": "tor", "tag": "t"}],
             {"method": "aes-128-gcm", "password": "x"},
             {"protocol": "vless", "settings": {}},
             42]
        """.trimIndent()
        val preview = preview(text)
        assertEquals(2, preview.acceptedCount)
        // naive and tor are not imported; a Shadowsocks object without an endpoint, an Xray outbound and a number are not nodes this app reads.
        assertEquals(5, preview.unparsedCount)
        assertEquals(setOf("naive", "tor", "Shadowsocks", null), preview.failed.toSet())
    }

    @Test
    fun linkListsCountNodeLinksButNotWebTelegramOrRoutingLinks() = runTest {
        val routing = "happ://routing/onadd/" + Base64.getEncoder().encodeToString("""{"Name":"r","DirectSites":["geosite:cn"]}""".toByteArray())
        val text = listOf(
            "socks://192.0.2.1:1080#two words",
            "https://provider.example/renew",
            "https://t.me/provider_channel",
            "tg://resolve?domain=provider",
            routing,
            "://routing/off",
            "sn://routing/abc",
            "http://192.0.2.9:3128#web-proxy",
            // Nodes this app cannot import are reported, whatever their scheme.
            "tg://proxy?server=192.0.2.3&port=443&secret=00",
            "https://t.me/socks?server=192.0.2.4&port=1080",
            "mieru://example",
            "https://reader:pass@proxy.example/path",
            "vless://00000000-0000-4000-8000-000000000001@192.0.2.5:443?type=quantum&security=none#bad",
        ).joinToString("\n")
        val preview = preview(text)
        assertEquals(mapOf("SOCKS5" to 1, "HTTP" to 1), preview.accepted)
        assertEquals(listOf("tg", "telegram", "mieru", "https", "vless"), preview.failed)
        assertFalse(preview.behaviorDiffers)
    }

    @Test
    fun aCleanSubscriptionIsNotPartial() = runTest {
        val preview = preview("socks://192.0.2.1:1080#one\nhttps://provider.example/support\n#announce: hello")
        assertEquals(0, preview.unparsedCount)
        assertTrue(preview.skipped.isEmpty())
    }
}
