package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    private fun fixture(name: String) = requireNotNull(javaClass.getResource("/subscriptions/$name")).readText()
}
