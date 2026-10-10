package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.MAX_IMPORT_BYTES
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder
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
    fun clashYaml_keepsJuicityAndItsOwnTlsFlagName() = runTest {
        val warnings = mutableListOf<String>()
        Logs.sink = { warnings.add(it) }
        val nodes = RawUpdater.parseRaw(fixture("clash-juicity.yaml"))!!
            .filterIsInstance<JuicityBean>()

        assertEquals(2, nodes.size)
        val insecure = nodes.first { it.displayName() == "jq-one" }
        assertEquals("jq.example.com", insecure.serverAddress)
        assertEquals(443, insecure.serverPort)
        assertEquals("00000000-0000-4000-8000-00000000000a", insecure.uuid)
        assertEquals("alpha", insecure.password)
        assertEquals("jq.example.com", insecure.sni)
        assertEquals(true, insecure.allowInsecure)
        val quotedPort = nodes.first { it.displayName() == "jq-two" }
        assertEquals(80, quotedPort.serverPort)
        assertTrue(quotedPort.sni.isNullOrEmpty())
        // An unlisted protocol must be visible in the log, not just absent from the list.
        assertTrue(
            "expected skip summary, got $warnings",
            warnings.any { it.contains("skipped 1 unsupported node(s)") && it.contains("haproxy×1") },
        )
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
    fun base64WrappedClashYaml_isParsedThroughRecursion() = runTest {
        // 有些面板會把整份 Clash YAML 再 base64 一次。解開之後不是連結清單，若只餵給
        // parseProxies 會全部識別不出來，最後回 null，使用者只看到「找不到节点」。
        val yaml = fixture("clash-basic.yaml")
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(yaml.toByteArray())

        val beans = RawUpdater.parseRaw(encoded)!!

        assertEquals(2, beans.size)
        assertEquals(
            "alpha",
            beans.filterIsInstance<ShadowsocksBean>().single().password,
        )
        assertEquals(
            "example.com",
            beans.filterIsInstance<VMessBean>().single().serverAddress,
        )
    }

    @Test
    fun singboxOutbounds_parsesOutboundAsNativeBean() = runTest {
        val bean = RawUpdater.parseRaw(fixture("singbox-outbounds.json"))!!.single() as SOCKSBean

        assertEquals("edge-one", bean.name)
        assertEquals("192.0.2.9", bean.serverAddress)
        assertEquals(1080, bean.serverPort)
    }

    @Test
    fun singboxEndpoints_parsesWireGuardAsNativeBean() = runTest {
        val config =
            """{"endpoints":[{"type":"wireguard","tag":"wg-edge","address":["10.0.0.2/32"],"private_key":"private","peers":[{"address":"vpn.example.com","port":51820,"public_key":"public","allowed_ips":["0.0.0.0/0"],"persistent_keepalive_interval":25}]}],"outbounds":[{"type":"direct","tag":"direct"}]}"""

        val bean = RawUpdater.parseRaw(config)!!.single() as WireGuardBean
        assertEquals("wg-edge", bean.name)
        assertEquals("vpn.example.com", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals(25, bean.persistentKeepaliveInterval)
    }

    @Test
    fun singboxEndpoints_parsesAmneziaWireGuardAsNativeBean() = runTest {
        val config =
            """{"endpoints":[{"type":"awg","tag":"awg-edge","address":["10.0.0.2/32"],"private_key":"private","listen_port":51821,"jc":4,"h1":"123","peers":[{"address":"vpn.example.com","port":51820,"public_key":"public","preshared_key":"psk","allowed_ips":["0.0.0.0/0"],"persistent_keepalive_interval":25,"reserved":[1,2,3]}]}],"outbounds":[{"type":"direct","tag":"direct"}]}"""

        val bean = RawUpdater.parseRaw(config)!!.single() as AmneziaWGBean
        assertEquals("awg-edge", bean.name)
        assertEquals("vpn.example.com", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals(51821, bean.listenPort)
        assertEquals(25, bean.persistentKeepaliveInterval)
        assertEquals("1, 2, 3", bean.reserved)
        assertEquals(4, bean.jc)
        assertEquals("123", bean.h1)
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
    fun singboxEndpoints_keepsEveryPeer() = runTest {
        val config =
            """{"endpoints":[{"type":"wireguard","tag":"wg-edge","address":"10.0.0.2/32","private_key":"private","peers":[{"address":"vpn.example.com","port":51820,"public_key":"public","allowed_ips":["10.0.0.0/24"]},{"address":"2001:db8::1","port":51821,"public_key":"public2","allowed_ips":["198.51.100.0/24"]}]}],"outbounds":[{"type":"direct","tag":"direct"}]}"""

        val bean = RawUpdater.parseRaw(config)!!.single() as WireGuardBean

        assertEquals("10.0.0.0/24", bean.peerAllowedIps)
        assertTrue(bean.extraPeers.contains("Endpoint = [2001:db8::1]:51821"))
        assertTrue(bean.extraPeers.contains("198.51.100.0/24"))
    }

    @Test
    fun clashYaml_keepsPeerListAndAllowedIps() = runTest {
        val input = buildString {
            appendLine("proxies:")
            appendLine("  - name: wg-split")
            appendLine("    type: wireguard")
            appendLine("    ip: 10.0.0.2/32")
            appendLine("    private-key: AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
            appendLine("    peers:")
            appendLine("      - server: 192.0.2.10")
            appendLine("        port: 51820")
            appendLine("        public-key: BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=")
            appendLine("        allowed-ips:")
            appendLine("          - 10.0.0.0/24")
            appendLine("      - server: 192.0.2.11")
            appendLine("        port: 51821")
            appendLine("        public-key: CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=")
            appendLine("        allowed-ips: 198.51.100.0/24")
        }

        val bean = RawUpdater.parseRaw(input)!!.single() as WireGuardBean

        assertEquals("10.0.0.2/32", bean.localAddress)
        assertEquals("192.0.2.10", bean.serverAddress)
        assertEquals("10.0.0.0/24", bean.peerAllowedIps)
        assertTrue(bean.extraPeers.contains("192.0.2.11:51821"))
        assertTrue(bean.extraPeers.contains("198.51.100.0/24"))
    }

    @Test
    fun amneziawgConfig_mergesRouteSplitPeersIntoSingleProfile() = runTest {
        val input = """
            [Interface]
            Address = 10.0.0.2/32
            PrivateKey = AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=
            Jc = 6
            Jmin = 10
            Jmax = 50

            [Peer]
            PublicKey = BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=
            Endpoint = 192.0.2.10:51820
            AllowedIPs = 10.0.0.0/24

            [Peer]
            PublicKey = CCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCCC=
            Endpoint = [2001:db8::1]:51821
            AllowedIPs = 198.51.100.0/24
        """.trimIndent()

        val bean = RawUpdater.parseRaw(input, "split.conf")!!.single() as AmneziaWGBean

        assertEquals("split", bean.name)
        assertEquals(6, bean.jc)
        assertEquals("192.0.2.10", bean.serverAddress)
        assertEquals("10.0.0.0/24", bean.peerAllowedIps)
        assertTrue(bean.extraPeers.contains("Endpoint = [2001:db8::1]:51821"))
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
    fun clashNodesWhoseTransportOrSecurityHasNoCounterpart_areSkippedNotDowngraded() = runTest {
        val uuid = "00000000-0000-4000-8000-000000000001"
        val yaml = """
            proxies:
              - {name: split, type: vless, server: 192.0.2.1, port: 443, uuid: $uuid, network: splithttp, xhttp-opts: {path: /x, headers: {X-Edge: one}, reuse-settings: {max-concurrency: "16-32"}}}
              - {name: kcp, type: vmess, server: 192.0.2.2, port: 443, uuid: $uuid, cipher: auto, network: kcp}
              - {name: download, type: vless, server: 192.0.2.3, port: 443, uuid: $uuid, network: xhttp, xhttp-opts: {download-settings: {path: /down}}}
              - {name: mode, type: vless, server: 192.0.2.4, port: 443, uuid: $uuid, network: xhttp, xhttp-opts: {mode: stream-two}}
              - {name: reuse, type: vless, server: 192.0.2.5, port: 443, uuid: $uuid, network: xhttp, xhttp-opts: {reuse-settings: {unknown: 1}}}
              - {name: pinned, type: trojan, server: 192.0.2.6, port: 443, password: secret, skip-cert-verify: true, fingerprint: "0123abcd"}
              - {name: upgrade, type: vmess, server: 192.0.2.7, port: 443, uuid: $uuid, cipher: auto, network: ws, ws-opts: {path: /u, headers: {Host: cdn.example}, v2ray-http-upgrade: true}}
        """.trimIndent()
        val parsed = RawUpdater.parseImport(yaml)!!
        val beans = parsed.proxies
        assertEquals(listOf("split", "upgrade"), beans.map { it.displayName() })

        val split = beans[0] as VMessBean
        assertEquals("xhttp", split.type)
        assertEquals("/x", split.path)
        val extra = JSONObject(split.xhttpExtra)
        assertEquals("one", extra.getJSONObject("headers").getString("X-Edge"))
        assertEquals("16-32", extra.getJSONObject("xmux").getString("max_concurrency"))
        val upgrade = beans[1] as VMessBean
        assertEquals("httpupgrade", upgrade.type)
        assertEquals("/u", upgrade.path)
        assertEquals("cdn.example", upgrade.host)
        assertEquals(listOf("vmess", "vless", "vless", "vless", "trojan"), ImportPreview.of(parsed).failed)
    }

    @Test
    fun singBoxOutboundsOfTypesTheCoreDoesNotRun_areSkipped() = runTest {
        val text = JSONObject().put(
            "outbounds",
            JSONArray()
                .put(JSONObject().put("type", "socks").put("tag", "kept").put("server", "192.0.2.1").put("server_port", 1080))
                .put(JSONObject().put("type", "naive").put("tag", "plugin-only").put("server", "192.0.2.2").put("server_port", 443))
                .put(JSONObject().put("type", "xhttp").put("tag", "xray-only").put("server", "192.0.2.3").put("server_port", 443))
                .put(JSONObject().put("type", "selector").put("tag", "group").put("outbounds", JSONArray().put("kept"))),
        ).toString()
        val parsed = RawUpdater.parseImport(text)!!
        assertEquals(listOf("kept"), parsed.proxies.map { it.displayName() })
        val preview = ImportPreview.of(parsed)
        assertEquals(2, preview.unparsedCount)
        assertEquals(1, preview.skipped[ImportPreview.Reason.BUILTIN])
        assertNull(RawUpdater.parseRaw("""{"server":"192.0.2.4","server_port":443,"type":"naive"}"""))
    }

    @Test
    fun clashOptionsThatWouldBeLostRejectTheNodeInsteadOfWeakeningIt() = runTest {
        val uuid = "00000000-0000-4000-8000-000000000001"
        val base = "server: 192.0.2.1, port: 443"
        val refused = listOf(
            "{name: pin-vless, type: vless, $base, uuid: $uuid, tls: true, skip-cert-verify: true, fingerprint: aa11}",
            "{name: pin-http, type: http, $base, tls: true, skip-cert-verify: true, fingerprint: aa11}",
            "{name: pin-hy2, type: hysteria2, $base, password: p, skip-cert-verify: true, fingerprint: aa11}",
            "{name: pin-anytls, type: anytls, $base, password: p, skip-cert-verify: true, fingerprint: aa11}",
            "{name: pin-plugin, type: ss, $base, cipher: aes-128-gcm, password: p, plugin: v2ray-plugin, plugin-opts: {mode: websocket, tls: true, fingerprint: aa11}}",
            "{name: relay, type: trojan, $base, password: p, dialer-proxy: other}",
            "{name: ws-header, type: vmess, $base, uuid: $uuid, cipher: auto, network: ws, ws-opts: {path: /w, headers: {Host: cdn.example, X-Token: secret}}}",
            "{name: http-header, type: vmess, $base, uuid: $uuid, cipher: auto, network: http, http-opts: {path: [/], headers: {Authorization: [x]}}}",
            "{name: ss-layer, type: trojan, $base, password: p, ss-opts: {enabled: true, method: aes-128-gcm, password: q}}",
            "{name: socks-tls, type: socks5, $base, tls: true}",
            "{name: shadow-tls, type: ss, $base, cipher: aes-128-gcm, password: p, plugin: shadow-tls, plugin-opts: {host: a.example}}",
            "{name: mlkem, type: vless, $base, uuid: $uuid, tls: true, reality-opts: {public-key: k, support-x25519mlkem768: true}}",
            "{name: reality-extra, type: vless, $base, uuid: $uuid, tls: true, reality-opts: {public-key: k, spider-x: /}}",
            "{name: ech-name, type: vless, $base, uuid: $uuid, tls: true, ech-opts: {enable: true, query-server-name: other.example}}",
            "{name: mux-anytls, type: anytls, $base, password: p, smux: {enabled: true}}",
            "{name: mux-only-tcp, type: vmess, $base, uuid: $uuid, cipher: auto, smux: {enabled: true, only-tcp: true}}",
            "{name: mux-limits, type: vmess, $base, uuid: $uuid, cipher: auto, smux: {enabled: true, max-streams: 4, max-connections: 2}}",
            "{name: mux-brutal, type: vmess, $base, uuid: $uuid, cipher: auto, smux: {enabled: true, brutal-opts: {enabled: true, up: fast}}}",
            "{name: tls-typo, type: vmess, $base, uuid: $uuid, cipher: auto, tls: tru}",
            "{name: insecure-typo, type: trojan, $base, password: p, skip-cert-verify: maybe}",
            "{name: server-only, type: vless, $base, uuid: $uuid, network: xhttp, xhttp-opts: {sc-max-buffered-posts: 30}}",
        )
        val accepted = listOf(
            "{name: tls-string, type: vmess, $base, uuid: $uuid, cipher: auto, tls: 'true', skip-cert-verify: 'false'}",
            "{name: tls-yaml, type: vless, $base, uuid: $uuid, tls: yes}",
            "{name: ss-off, type: trojan, $base, password: p, ss-opts: {enabled: false}}",
            "{name: host-only, type: vmess, $base, uuid: $uuid, cipher: auto, network: ws, ws-opts: {path: /w, headers: {host: cdn.example}}}",
            "{name: mux, type: vmess, $base, uuid: $uuid, cipher: auto, smux: {enabled: true, protocol: smux, max-connections: 4, min-streams: 2, padding: 'true', statistic: true, brutal-opts: {enabled: true, up: '50 Mbps', down: 100}}}",
            "{name: ss-mux, type: ss, $base, cipher: aes-128-gcm, password: p, smux: {enabled: true, protocol: yamux, max-streams: 16}}",
            "{name: mux-off, type: anytls, $base, password: p, smux: {enabled: false, only-tcp: true}}",
        )
        val parsed = RawUpdater.parseImport("proxies:\n" + (refused + accepted).joinToString("\n") { "  - $it" })!!
        assertEquals(accepted.map { it.substringAfter("name: ").substringBefore(',') }, parsed.proxies.map { it.displayName() })
        assertEquals(refused.size, parsed.report.failed.size)
        val byName = parsed.proxies.associateBy { it.displayName() }

        // Valid boolean spellings keep TLS on; nothing weaker was accepted.
        val tlsString = byName.getValue("tls-string") as VMessBean
        assertEquals("tls", tlsString.security)
        assertFalse(tlsString.allowInsecure!!)
        assertEquals("tls", (byName.getValue("tls-yaml") as VMessBean).security)
        assertTrue(parsed.proxies.none { (it as? StandardV2RayBean)?.allowInsecure == true })

        val mux = byName.getValue("mux") as VMessBean
        assertTrue(mux.enableMux!!)
        assertEquals(1, mux.muxType)
        assertEquals(1, mux.muxMode)
        assertEquals(4, mux.muxMaxConnections)
        assertEquals(2, mux.muxMinStreams)
        assertTrue(mux.muxPadding!!)
        assertTrue(mux.muxBrutal!!)
        assertEquals(50, mux.muxBrutalUpMbps)
        assertEquals(100, mux.muxBrutalDownMbps)
        val ssMux = byName.getValue("ss-mux") as ShadowsocksBean
        assertTrue(ssMux.enableMux!!)
        assertEquals(2, ssMux.muxType)
        assertEquals(0, ssMux.muxMode)
        assertEquals(16, ssMux.muxConcurrency)
        assertFalse(ssMux.muxBrutal!!)
    }

    @Test
    fun clashXhttpOptionsKeepTheCoreSchemaTypes() = runTest {
        val bean = RawUpdater.parseRaw(
            """
            proxies:
              - name: typed
                type: vless
                server: 192.0.2.1
                port: 443
                uuid: 00000000-0000-4000-8000-000000000001
                network: xhttp
                xhttp-opts:
                  path: /x
                  no-grpc-header: "true"
                  x-padding-bytes: 100-1000
                  sc-max-each-post-bytes: 1000000
                  reuse-settings:
                    max-concurrency: 16-32
                    c-max-reuse-times: 4
                    h-keep-alive-period: "30"
            """.trimIndent(),
        )!!.single() as VMessBean
        val extra = JSONObject(bean.xhttpExtra)
        assertEquals(true, extra.get("no_grpc_header"))
        assertEquals("100-1000", extra.get("x_padding_bytes"))
        assertEquals(1000000, extra.getInt("sc_max_each_post_bytes"))
        assertTrue(extra.get("sc_max_each_post_bytes") is Number)
        val xmux = extra.getJSONObject("xmux")
        assertEquals("16-32", xmux.get("max_concurrency"))
        assertTrue(xmux.get("c_max_reuse_times") is Number)
        assertTrue(xmux.get("h_keep_alive_period") is Number)
        assertEquals(30L, xmux.getLong("h_keep_alive_period"))
    }

    @Test
    fun hysteria2CertificatePinsAreRefusedInLinksAndJson() = runTest {
        assertTrue(parseProxies("hy2://secret@192.0.2.1:443?insecure=1&pinSHA256=AA:BB#pinned").isEmpty())
        assertEquals(1, parseProxies("hy2://secret@192.0.2.1:443?insecure=1#plain").size)
        val json = """{"server": "192.0.2.1:443", "auth": "secret", "tls": {"insecure": true, "pinSHA256": "AA:BB"}}"""
        assertNull(RawUpdater.parseRaw(json))
        assertEquals(1, RawUpdater.parseRaw(json.replace(""", "pinSHA256": "AA:BB"""", ""))!!.size)
    }

    @Test
    fun linksWithCertificateChecksOrMaskingTheCoreCannotApply_areRefusedInEveryForm() = runTest {
        val uuid = "00000000-0000-4000-8000-000000000001"
        // Synthetic values: a SHA-256 pin, a certificate name and a Finalmask object.
        val pin = "0f".repeat(32)
        val mask = URLEncoder.encode("""{"udp":[{"type":"noise"}]}""", "UTF-8")
        val refused = mutableListOf<String>()
        for (security in listOf("security=tls&sni=a.example", "security=reality&pbk=key&sid=01&sni=a.example")) {
            for (insecure in listOf("", "&allowInsecure=0", "&allowInsecure=1")) {
                for (option in listOf("pcs=$pin", "vcn=a.example", "fm=$mask", "pcs=$pin%2C$pin")) {
                    refused += "vless://$uuid@192.0.2.1:443?type=tcp&$security$insecure&$option#vless"
                    refused += "trojan://secret@192.0.2.2:443?type=ws&$security$insecure&$option#trojan"
                    refused += "vmess://$uuid@192.0.2.3:443?type=tcp&encryption=auto&$security$insecure&$option#vmess"
                }
            }
        }
        fun vmessJson(vararg extra: Pair<String, String>) = "vmess://" + encode(
            JSONObject(mapOf("v" to "2", "ps" to "json", "add" to "192.0.2.6", "port" to "443", "id" to uuid, "aid" to "0", "net" to "tcp", "tls" to "tls") + extra).toString(),
        )
        val legacy = "vmess://tcp+tls:$uuid-0@192.0.2.4:443?tlsServerName=a.example"
        val kitsunebi = "vmess://" + encode("auto:$uuid@192.0.2.5:443") + "?remarks=kitsunebi&tls=1"
        refused += "$legacy&pcs=$pin#legacy"
        refused += "$kitsunebi&vcn=a.example"
        refused += vmessJson("pcs" to pin)
        refused += vmessJson("vcn" to "a.example")
        refused += vmessJson("fm" to """{"udp":[]}""")
        for ((index, link) in refused.withIndex()) {
            val report = ImportReport()
            assertTrue("link $index was accepted", parseProxies(link, report).isEmpty())
            // Reported as an entry that did not import, so a subscription keeps the stored profile.
            assertEquals("link $index was not reported", 1, report.failed.size)
        }

        // The same forms import without those options, with empty ones, and with a question mark
        // that belongs to the remark rather than to a query.
        val accepted = listOf(
            "vless://$uuid@192.0.2.1:443?type=tcp&security=tls&sni=a.example&pcs=&vcn=&fm=#empty",
            "trojan://secret@192.0.2.2:443?security=tls&sni=a.example#title?pcs=$pin",
            "$legacy#legacy",
            kitsunebi,
            vmessJson("pcs" to ""),
            "vmess://" + encode("""{"v":"2","ps":"null-pin","add":"192.0.2.7","port":"443","id":"$uuid","aid":"0","net":"tcp","tls":"tls","vcn":null}"""),
        )
        for ((index, link) in accepted.withIndex()) assertEquals("link $index", 1, parseProxies(link).size)
    }

    @Test
    fun everySchemeRefusesUnsupportedLinkOptionsAtTheImportBoundary() = runTest {
        val uuid = "00000000-0000-4000-8000-000000000001"
        val key = "A".repeat(43) + "%3D"
        val links = listOf(
            "ss://aes-128-gcm:pw@192.0.2.1:8388#ss",
            "naive+https://user:pass@192.0.2.2:443#naive",
            "hysteria2://secret@192.0.2.3:443?sni=a.example#hy2",
            "hy2://secret@192.0.2.3:443?sni=a.example#hy2b",
            "tuic://$uuid:pass@192.0.2.4:443?sni=a.example#tuic",
            "anytls://pass@192.0.2.5:443?sni=a.example#anytls",
            "wireguard://$key@192.0.2.6:51820?publickey=$key&address=10.0.0.2%2F32#wireguard",
            "wg://$key@192.0.2.6:51820?publickey=$key&address=10.0.0.2%2F32#wg",
            "vless://$uuid@192.0.2.7:443?type=tcp&security=tls&sni=a.example#vless",
            "vmess://$uuid@192.0.2.8:443?type=tcp&encryption=auto&security=tls#vmess",
            "trojan://secret@192.0.2.9:443?security=tls&sni=a.example#trojan",
            "socks5://192.0.2.10:1080#socks",
            "hysteria://192.0.2.11:443?auth=x&peer=a.example&upmbps=10&downmbps=50#hy1",
            "https://user:pass@192.0.2.12:443?sni=a.example#https-proxy",
        )
        fun with(link: String, option: String) = link.substringBefore('#') + (if ('?' in link.substringBefore('#')) "&" else "?") + option + "#" + link.substringAfter('#')
        for (link in links) {
            val scheme = link.substringBefore("://")
            // Each link imports as it is, so a refusal below comes from the option alone.
            assertEquals(scheme, 1, parseProxies(link).size)
            for (option in listOf("pcs=${"0f".repeat(32)}", "vcn=a.example", "fm=%7B%7D")) {
                val report = ImportReport()
                assertTrue("$scheme with ${option.substringBefore('=')}", parseProxies(with(link, option), report).isEmpty())
                assertEquals("$scheme with ${option.substringBefore('=')}", listOf<String?>(scheme), report.failed)
            }
            // An empty value asks for nothing.
            assertEquals(scheme, 1, parseProxies(with(link, "pcs=&vcn=&fm=")).size)
        }
        // A web address that is not a proxy link stays a subscription candidate, whatever its query.
        assertThrows(SubscriptionFoundException::class.java) { runBlocking { parseProxies("https://provider.example/sub?fm=abc&pcs=x") } }
    }

    @Test
    fun aRefusedCompleteLinkDoesNotDiscardItsValidNeighbors() = runTest {
        val uuid = "00000000-0000-4000-8000-000000000001"
        val firsts = listOf("socks5://192.0.2.1:1080", "socks5://192.0.2.1:1080#first${" ".repeat(2048)}server")
        val last = "socks5://192.0.2.3:1080"
        for (option in listOf("pcs=pin", "vcn=verify.example", "fm=%7B%7D")) {
            for (query in listOf("security=tls&$option", "security=tls &$option")) {
                val refused = "vless://$uuid@192.0.2.2:443?$query"
                for (separator in listOf(" ", "  ", "\t")) {
                    for (first in firsts) {
                        val report = ImportReport()
                        val beans = parseProxies(listOf(first, refused, last).joinToString(separator), report)
                        assertEquals(listOf("192.0.2.1", "192.0.2.3"), beans.map { it.serverAddress })
                        assertEquals(listOf<String?>("vless"), report.failed)
                    }
                }
            }
        }
    }

    @Test
    fun equallySizedParsesKeepTheReportedFailure() = runTest {
        val uuid = "00000000-0000-4000-8000-000000000001"
        val valid = "vless://$uuid@192.0.2.1:443?x=1"
        val refused = "vless://$uuid@192.0.2.2:443?pcs=unsupported"
        val report = ImportReport()
        val beans = parseProxies("$valid $refused", report)
        assertEquals("192.0.2.1", beans.single().serverAddress)
        assertEquals(listOf<String?>("vless"), report.failed)
    }

    @Test
    fun splittingMalformedLinesCannotDiscardVerificationOptions() = runTest {
        val uuid = "00000000-0000-4000-8000-000000000001"
        val valid = "socks5://192.0.2.2:1080#first socks5://192.0.2.3:1080#second"
        for (option in listOf("pcs=${"0f".repeat(32)}", "vcn=verify.example", "fm=%7B%7D")) {
            val malformed = "vless://$uuid@192.0.2.1:443?security=tls&sni=a b&$option#refused"
            val report = ImportReport()
            val beans = parseProxies("$malformed\n$valid", report)
            assertEquals(listOf("first", "second"), beans.map { it.displayName() })
            assertEquals(listOf<String?>("vless"), report.failed)
            assertTrue(parseProxies(malformed).isEmpty())
        }
    }

    @Test
    fun shareLinksWithUnknownTransportOrSecurity_areRefusedNotDowngraded() = runTest {
        val uuid = "00000000-0000-4000-8000-000000000001"
        fun vmess(vararg fields: Pair<String, String>) = "vmess://" + encode(
            JSONObject(mapOf("v" to "2", "add" to "192.0.2.9", "port" to "443", "id" to uuid, "aid" to "0") + fields).toString(),
        )
        val links = listOf(
            "vless://$uuid@192.0.2.1:443?type=raw&security=reality&pbk=key&sid=01&sni=front.example#raw",
            "vless://$uuid@192.0.2.2:443?type=quantum&security=none#unknown-transport",
            "vless://$uuid@192.0.2.3:443?type=tcp&security=xtls#unknown-security",
            "trojan://secret@192.0.2.4:443?type=ws&security=foo#trojan-unknown-security",
            "vless://$uuid@192.0.2.5:443?type=tcp&security=reality&pbk=key&pqv=verify#post-quantum-verify",
            vmess("ps" to "h2", "net" to "h2", "tls" to "tls", "path" to "/h2"),
            vmess("ps" to "vmess-xtls", "net" to "tcp", "tls" to "xtls"),
            vmess("ps" to "vmess-mkcp2", "net" to "mkcp2", "tls" to ""),
        )
        val beans = parseProxies(links.joinToString("\n"))
        assertEquals(listOf("raw", "h2"), beans.map { it.displayName() })
        val raw = beans[0] as VMessBean
        assertEquals("tcp", raw.type)
        assertEquals("tls", raw.security)
        assertEquals("key", raw.realityPubKey)
        val h2 = beans[1] as VMessBean
        assertEquals("http", h2.type)
        assertEquals("tls", h2.security)
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
