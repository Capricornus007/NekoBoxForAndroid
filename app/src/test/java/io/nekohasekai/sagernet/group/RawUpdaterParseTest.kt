package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.ImportTooLargeException
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.MAX_IMPORT_BYTES
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.test.runTest
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RawUpdaterParseTest {

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
    fun clashBasic_parsesShadowsocksAndVmess() = runTest {
        val beans = RawUpdater.parseRaw(fixture("clash-basic.yaml"))!!

        assertEquals(2, beans.size)
        val shadowsocks = beans.filterIsInstance<ShadowsocksBean>().single()
        assertEquals("192.0.2.1", shadowsocks.serverAddress)
        assertEquals(443, shadowsocks.serverPort)
        assertEquals("aes-128-gcm", shadowsocks.method)
        assertEquals("alpha", shadowsocks.password)
        val vmess = beans.filterIsInstance<VMessBean>().single()
        assertEquals("example.com", vmess.serverAddress)
        assertEquals(8443, vmess.serverPort)
        assertEquals("00000000-0000-4000-8000-000000000001", vmess.uuid)
    }

    @Test
    fun clashMalformedNode_skipsOnlyMalformedEntry() = runTest {
        val beans = RawUpdater.parseRaw(fixture("clash-malformed.yaml"))!!

        assertEquals(2, beans.size)
        assertEquals(listOf("ss-first", "vm-last"), beans.map { it.displayName() })
    }

    @Test
    fun clashUnknownGlobalTag_isRejected() = runTest {
        assertNull(RawUpdater.parseRaw(fixture("clash-unknown-tag.yaml")))
    }

    @Test
    fun clashCollectionAliases_overLimitAreRejected() = runTest {
        val input = buildString {
            appendLine("node: &node")
            appendLine("  name: repeated")
            appendLine("  type: ss")
            appendLine("  server: 192.0.2.3")
            appendLine("  port: 443")
            appendLine("  cipher: aes-128-gcm")
            appendLine("  password: alpha")
            appendLine("proxies:")
            repeat(201) { appendLine("  - *node") }
        }

        assertNull(RawUpdater.parseRaw(input))
    }

    @Test
    fun base64UriList_parsesShadowsocksAndSocks() = runTest {
        val links = listOf(
            "ss://aes-128-gcm:alpha@example.com:443#ss-one",
            "socks://reader:beta@192.0.2.8:1080#socks-one",
        ).joinToString("\n")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(links.toByteArray())

        val beans = RawUpdater.parseRaw(encoded)!!

        assertEquals(2, beans.size)
        assertTrue(beans.any { it is ShadowsocksBean && it.password == "alpha" })
        assertTrue(
            beans.any {
                it is SOCKSBean && it.serverAddress == "192.0.2.8" &&
                    it.serverPort == 1080 && it.username == "reader" && it.password == "beta"
            },
        )
    }

    @Test
    fun singboxOutbounds_wrapsOutboundAsConfigBean() = runTest {
        val bean = RawUpdater.parseRaw(fixture("singbox-outbounds.json"))!!.single() as ConfigBean
        val config = JSONObject(bean.config)

        assertEquals(1, bean.type)
        assertEquals("edge-one", bean.name)
        assertEquals("socks", config.getString("type"))
        assertEquals("192.0.2.9", config.getString("server"))
        assertEquals(1080, config.getInt("server_port"))
    }

    @Test
    fun wireguardConfig_parsesPeerAndFileName() = runTest {
        val bean = RawUpdater.parseRaw(fixture("wireguard.conf"), "office.conf")!!.single() as WireGuardBean

        assertEquals("office", bean.name)
        assertEquals("192.0.2.2/32", bean.localAddress)
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", bean.privateKey)
        assertEquals("example.com", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals("BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=", bean.peerPublicKey)
    }

    @Test
    fun emptyInput_returnsNull() = runTest {
        assertNull(RawUpdater.parseRaw(""))
    }

    @Test
    fun commentHeaders_workOutsideAndInsideBase64WithBothAlphabets() = runTest {
        val title = "Example подписка"
        val links = "ss://aes-128-gcm:alpha@192.0.2.1:443#first\nsocks://192.0.2.2:1080#second"
        val header = "\uFEFF # Profile-Title: base64:${encode(title)}\r\n" +
            "# SUBSCRIPTION-USERINFO: upload=1; download=2; total=100\r\n" +
            "#support-url: https://support.example/help\r\n" +
            "#announce: clash://install-config?url=https://announcement.example/\r\n\r\n"
        val inputs = listOf(
            header + links,
            header + encode(links).chunked(30).joinToString("\n# comment\n"),
            encode(header + links),
            Base64.getUrlEncoder().withoutPadding().encodeToString((header + links).toByteArray()),
        )
        for (input in inputs) {
            val content = RawUpdater.readSubscriptionContent(input)
            assertEquals(title, content.title)
            assertEquals("upload=1; download=2; total=100", content.userinfo)
            assertEquals(listOf("first", "second"), RawUpdater.parseRaw(input)!!.map { it.name })
        }
    }

    @Test
    fun comments_neverBecomeProfilesOrSubscriptionCandidates() = runTest {
        val comments = """
            # support-url: https://support.example/help
              # announce: https://announcement.example/
            # hidden-proxy: https://reader:password@192.0.2.3:443
            # sn://subscription?url=https://ignored.example/
            # clash://install-config?url=https://ignored.example/
        """.trimIndent()
        for (input in listOf(comments, encode(comments))) {
            assertNull(RawUpdater.parseRaw(input))
        }
        assertTrue(parseProxies(comments).isEmpty())
        assertEquals("kept", RawUpdater.parseRaw("$comments\nsocks://192.0.2.4:1080#kept")!!.single().name)
    }

    @Test
    fun bodyMetadata_isPreambleOnlyAndOuterFirst() {
        val inner = "#profile-title: Inner\n#subscription-userinfo: download=2\nsocks://192.0.2.4:1080#kept"
        val content = RawUpdater.readSubscriptionContent("#profile-title: Outer\n#profile-title: Duplicate\n${encode(inner)}")
        assertEquals("Outer", content.title)
        assertEquals("download=2", content.userinfo)
        assertNull(RawUpdater.readSubscriptionContent("socks://192.0.2.4:1080\n#profile-title: Late").title)
        assertNull(RawUpdater.readSubscriptionContent("#profile-title:\n#support-url: https://support.example").title)
    }

    @Test
    fun providerMetadata_isPreambleOnlyOuterFirstAndKnownKeysOnly() {
        val inner = "#support-url: https://inner.example\n#announce: base64:${encode("Inner")}\nsocks://192.0.2.4:1080#kept"
        val content = RawUpdater.readSubscriptionContent(
            "#Support-URL: https://outer.example\n#support-url: https://duplicate.example\n#routing: sn://routing/abc\n" +
                "#profile-update-interval: 12\n#hidden-proxy: socks://192.0.2.3:1080\n#announce:\n${encode(inner)}",
        )
        assertEquals(
            mapOf(
                "support-url" to "https://outer.example",
                "routing" to "sn://routing/abc",
                "profile-update-interval" to "12",
                "announce" to "base64:${encode("Inner")}",
            ),
            content.meta,
        )
        assertTrue(RawUpdater.readSubscriptionContent("socks://192.0.2.4:1080\n#support-url: https://late.example").meta.isEmpty())
    }

    @Test
    fun malformedOrEmptyBase64Titles_areIgnored() {
        for (title in listOf("", "base64:", "base64:!!!", "base64:/w==", "base64:${encode("\n")}")) {
            assertNull(RawUpdater.decodeProfileTitle(title))
        }
        assertEquals("Example", RawUpdater.decodeProfileTitle("  Example  "))
        assertEquals("Example", RawUpdater.decodeProfileTitle("BASE64:${encode("Example")}"))
        assertEquals("Fallback", RawUpdater.readSubscriptionContent("#profile-title: base64:!!!\n#profile-title: Fallback").title)
    }

    @Test
    fun commentPrefixedStructuredFormats_preserveExistingParsers() = runTest {
        for (fixtureName in listOf("clash-basic.yaml", "singbox-outbounds.json", "wireguard.conf")) {
            val input = "#profile-title: Example\n#support-url: https://support.example\n${fixture(fixtureName)}"
            val expected = RawUpdater.parseRaw(fixture(fixtureName))!!.map { it.javaClass }
            assertEquals(expected, RawUpdater.parseRaw(input)!!.map { it.javaClass })
            assertEquals(expected, RawUpdater.parseRaw(encode(input))!!.map { it.javaClass })
        }
    }

    @Test
    fun emptyAndWhollyMalformedStructuredInput_returnsNull() = runTest {
        for (input in listOf("{}", "[]", "{\"outbounds\":[]}", "proxies: []", "proxies: [{type: unsupported}]", "proxies: [{type: socks5, port: invalid}]")) {
            assertNull(input, RawUpdater.parseRaw(input))
            assertNull(RawUpdater.parseRaw(encode(input)))
        }
        assertNull(RawUpdater.parseRaw("{\"outbounds\":[]} trailing"))
    }

    @Test
    fun jsonArray_skipsMalformedEntriesAndKeepsValidOutbounds() = runTest {
        val input = """[{"method":[]}, {"server":"192.0.2.9","server_port":1080,"type":"socks"}, false]"""
        val bean = RawUpdater.parseRaw(input)!!.single() as ConfigBean
        assertEquals("192.0.2.9", JSONObject(bean.config).getString("server"))
    }

    @Test
    fun jsonEndpointValidation_preservesHysteriaPortStorage() = runTest {
        for (input in listOf(
            """{"server":"192.0.2.3:8443","up_mbps":10,"auth_str":"example"}""",
            """{"server":"192.0.2.3:8443","auth":"example"}""",
        )) {
            val bean = RawUpdater.parseRaw(input)!!.single() as HysteriaBean
            assertEquals("192.0.2.3", bean.serverAddress)
            assertEquals("8443", bean.serverPorts)
            assertEquals("", bean.customConfigJson)
        }
    }

    @Test
    fun parserFailures_doNotLogHeadersCredentialsOrYamlSnippets() = runTest {
        val logs = mutableListOf<String>()
        Logs.sink = { logs.add(it) }
        val marker = "private-parser-marker"
        val malformed = "proxies:\n  - type: socks5\n    server: $marker.example\n    port: $marker\n"
        assertNull(RawUpdater.parseRaw("#profile-title: $marker\n$malformed"))
        assertNull(RawUpdater.parseRaw("proxies: !!invalid.$marker {}"))
        assertNull(RawUpdater.parseRaw("#profile-title: $marker\n${encode("{invalid $marker")}"))
        assertFalse(logs.joinToString("\n").contains(marker))
    }

    @Test
    fun parsingSingleSubscriptionUrl_doesNotFetchBeforeConfirmation() = runTest {
        val failure = runCatching { RawUpdater.parseRaw("https://subscription.example/list") }.exceptionOrNull()
        assertTrue(failure is SubscriptionFoundException)
        val encodedFailure = runCatching { RawUpdater.parseRaw(encode("https://subscription.example/list")) }.exceptionOrNull()
        assertTrue(encodedFailure is SubscriptionFoundException)
    }

    @Test
    fun contentNormalization_preservesByteLimit() {
        assertThrows(ImportTooLargeException::class.java) {
            RawUpdater.readSubscriptionContent("#".repeat(MAX_IMPORT_BYTES.toInt() + 1))
        }
        assertThrows(ImportTooLargeException::class.java) {
            RawUpdater.readSubscriptionContent("€".repeat(MAX_IMPORT_BYTES.toInt() / 3 + 1))
        }
    }

    @Test
    fun clashPluginTlsUsesTlsFlagNotTransportMode() = runTest {
        for (tls in listOf(false, true)) {
            val bean = RawUpdater.parseRaw(
                """
                proxies:
                  - type: ss
                    server: node.example
                    port: 443
                    cipher: aes-128-gcm
                    password: example
                    plugin: v2ray-plugin
                    plugin-opts:
                      mode: websocket
                      tls: $tls
                """.trimIndent(),
            )!!.single() as ShadowsocksBean
            assertEquals(tls, "tls" in bean.plugin!!.split(';'))
            assertTrue(bean.plugin!!.contains("mode=websocket"))
        }
    }

    @Test
    fun clashXhttpOptionsDoNotDependOnNetworkKeyOrder() = runTest {
        val options = """
                    xhttp-opts:
                      host: front.example
                      path: /tunnel
                      mode: packet-up
                      no-grpc-header: true
        """.trimIndent().prependIndent("    ")
        for (transport in listOf("    network: xhttp\n$options", "$options\n    network: xhttp")) {
            val bean = RawUpdater.parseRaw(
                "proxies:\n  - type: vless\n    server: node.example\n    port: 443\n" +
                    "    uuid: 00000000-0000-4000-8000-000000000001\n$transport",
            )!!.single() as VMessBean
            assertEquals("xhttp", bean.type)
            assertEquals("front.example", bean.host)
            assertEquals("/tunnel", bean.path)
            assertEquals("packet-up", bean.xhttpMode)
            assertTrue(JSONObject(bean.xhttpExtra).getBoolean("no_grpc_header"))
        }
    }

    @Test
    fun uniqueNamesPreserveLegacySuffixesAndHandleLargeDuplicateLists() {
        val inputs = listOf(
            List(1000) { "same" },
            listOf("same", "same (1)", "same", "same (1)", "same (1)", "same", "same (0)", "same (0)"),
        )
        for (names in inputs) {
            val used = linkedSetOf<String>()
            val expected = names.map { original ->
                var name = original
                var index = 0
                while (!used.add(name)) {
                    index++
                    name = name.replace(" (${index - 1})", "") + " ($index)"
                }
                name
            }
            val beans = names.map { name ->
                SOCKSBean().apply {
                    this.name = name
                    initializeDefaultValues()
                }
            }
            RawUpdater.ensureUniqueNames(beans)
            assertEquals(expected, beans.map { it.displayName() })
        }
    }

    private fun encode(text: String) = Base64.getEncoder().encodeToString(text.toByteArray())

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/subscriptions/$name")).readText()
}
