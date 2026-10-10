package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.MAX_WIREGUARD_ALLOWED_IPS
import io.nekohasekai.sagernet.fmt.wireguard.MAX_WIREGUARD_DNS_ENTRIES
import io.nekohasekai.sagernet.fmt.wireguard.MAX_WIREGUARD_PEERS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardDnsMode
import io.nekohasekai.sagernet.fmt.wireguard.orderedPeers
import io.nekohasekai.sagernet.fmt.wireguard.wireGuardSettings
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WireGuardImportTest {

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

    private fun conf(vararg peers: String, dns: String = "") = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $KEY_A")
        appendLine("Address = 10.8.0.2/32, fd00:8::2/128")
        if (dns.isNotEmpty()) appendLine("DNS = $dns")
        for (peer in peers) {
            appendLine()
            appendLine(peer.trimIndent())
        }
    }

    private fun peer(key: String, routes: String, endpoint: String? = null) = buildString {
        appendLine("[Peer]")
        appendLine("PublicKey = $key")
        if (endpoint != null) appendLine("Endpoint = $endpoint")
        appendLine("AllowedIPs = $routes")
    }

    private fun parse(text: String) = RawUpdater.parseWireGuardConf(text).single()

    private fun failure(text: String) = assertThrows(Exception::class.java) { parse(text) }.message

    @Test
    fun multiPeerConfImportsAsOneProfileThatKeepsEveryPeer() = runTest {
        val text = conf(
            """
            [Peer]
            PublicKey = $KEY_B
            PresharedKey = $KEY_C
            Endpoint = vpn.example:51820
            AllowedIPs = 10.0.0.0/8, 192.168.1.5
            PersistentKeepalive = 25
            """,
            """
            [Peer]
            PublicKey = $KEY_C
            Endpoint = [2001:db8::7]:51821
            AllowedIPs = fd00::/8
            """,
            """
            [Peer]
            PublicKey = $KEY_D
            AllowedIPs = 172.16.0.0/12
            PersistentKeepalive = off
            """,
            dns = "10.0.0.1, fd00::53, corp.example",
        )

        val bean = RawUpdater.parseRaw(text, "office.conf")!!.single() as WireGuardBean

        // The first peer keeps the identity the old per-peer import gave its first profile.
        assertEquals("office", bean.name)
        assertEquals("vpn.example", bean.serverAddress)
        assertEquals(51820, bean.serverPort)
        assertEquals(KEY_B, bean.peerPublicKey)
        assertEquals(KEY_C, bean.peerPreSharedKey)
        assertEquals("10.0.0.0/8\n192.168.1.5/32", bean.allowedIPs)
        assertEquals(25, bean.persistentKeepalive)
        assertEquals(0, bean.serverPeerPosition)
        assertEquals(
            "[Peer]\nPublicKey = $KEY_C\nEndpoint = [2001:db8::7]:51821\nAllowedIPs = fd00::/8\n\n" +
                "[Peer]\nPublicKey = $KEY_D\nAllowedIPs = 172.16.0.0/12\n",
            bean.extraPeers,
        )
        // Imported DNS is kept apart, and the app DNS stays in charge until the user picks it.
        assertEquals("10.0.0.1\nfd00::53", bean.importedDnsServers)
        assertEquals("corp.example", bean.importedDnsDomains)
        assertEquals(WireGuardDnsMode.APP, bean.dnsMode)
        assertEquals("", bean.customDnsServers)
        assertEquals("", bean.customDnsDomains)
    }

    @Test
    fun peersKeepTheirOrderWhenAPeerWithoutEndpointComesFirst() = runTest {
        val bean = parse(
            conf(
                peer(KEY_B, "10.1.0.0/16"),
                peer(KEY_C, "0.0.0.0/0", "192.0.2.1:51820"),
                peer(KEY_D, "0.0.0.0/0", "192.0.2.2:51820"),
            ),
        ) as WireGuardBean

        // The first peer with an endpoint is the server; both 0.0.0.0/0 declarations stay.
        assertEquals("192.0.2.1", bean.serverAddress)
        assertEquals(KEY_C, bean.peerPublicKey)
        assertEquals(1, bean.serverPeerPosition)
        val peers = bean.wireGuardSettings()!!.orderedPeers()
        assertEquals(listOf(KEY_B, KEY_C, KEY_D), peers.map { it.public_key })
        assertEquals(listOf(listOf("10.1.0.0/16"), listOf("0.0.0.0/0"), listOf("0.0.0.0/0")), peers.map { it.allowed_ips })
    }

    @Test
    fun peerRoutesStayExactlyAsWritten() = runTest {
        for (routes in listOf("0.0.0.0/0", "::/0", "192.168.0.0/16, fd12::/16")) {
            val bean = parse(conf(peer(KEY_B, routes, "192.0.2.1:51820"))) as WireGuardBean
            assertEquals(routes.split(", ").joinToString("\n"), bean.allowedIPs)
        }
    }

    @Test
    fun peerWithoutRoutesIsRejectedInsteadOfBecomingAFullTunnel() = runTest {
        val missing = conf("[Peer]\nPublicKey = $KEY_B\nEndpoint = 192.0.2.1:51820")
        val empty = conf("[Peer]\nPublicKey = $KEY_B\nEndpoint = 192.0.2.1:51820\nAllowedIPs =")
        val secondPeerMissing = conf(peer(KEY_B, "0.0.0.0/0", "192.0.2.1:51820"), "[Peer]\nPublicKey = $KEY_C\nEndpoint = 192.0.2.2:51820")
        assertEquals("Peer 1 has no AllowedIPs", failure(missing))
        assertEquals("Peer 1 has no AllowedIPs", failure(empty))
        assertEquals("Peer 2 has no AllowedIPs", failure(secondPeerMissing))
        assertEquals("Peer 1 has an invalid AllowedIPs entry", failure(conf(peer(KEY_B, "10.0.0.0/33", "192.0.2.1:51820"))))
    }

    @Test
    fun keysMatchWithoutCaseAndInlineCommentsAreIgnored() = runTest {
        val text = """
            [interface]
            privatekey = $KEY_A
            ADDRESS = 10.8.0.2/32 # tunnel address
            dns = 10.0.0.1

            [PEER]
            publickey = $KEY_B
            endpoint = 192.0.2.1:51820
            allowedips = 10.0.0.0/8, 192.168.0.0/16 # office ranges
            persistentkeepalive = 15
        """.trimIndent()

        val bean = RawUpdater.parseRaw(text)!!.single() as WireGuardBean

        assertEquals(KEY_A, bean.privateKey)
        assertEquals("10.8.0.2/32", bean.localAddress)
        assertEquals("10.0.0.0/8\n192.168.0.0/16", bean.allowedIPs)
        assertEquals(15, bean.persistentKeepalive)
        assertEquals("10.0.0.1", bean.importedDnsServers)
    }

    @Test
    fun boundsRejectOversizedConfigsWithoutTruncating() = runTest {
        val tooManyPeers = conf(*Array(MAX_WIREGUARD_PEERS + 1) { peer(KEY_B, "10.${it / 256}.${it % 256}.0/24", "192.0.2.1:51820") })
        assertEquals(true, failure(tooManyPeers)?.contains("more than $MAX_WIREGUARD_PEERS peers"))
        val atPeerLimit = parse(conf(*Array(MAX_WIREGUARD_PEERS) { peer(KEY_B, "10.${it / 256}.${it % 256}.0/24", "192.0.2.1:51820") }))
        assertEquals(MAX_WIREGUARD_PEERS, atPeerLimit.wireGuardSettings()!!.orderedPeers().size)

        val manyRoutes = (0..MAX_WIREGUARD_ALLOWED_IPS).joinToString(", ") { "10.${it / 256 % 256}.${it % 256}.${it / 65536}/32" }
        assertEquals(true, failure(conf(peer(KEY_B, manyRoutes, "192.0.2.1:51820")))?.contains("more than $MAX_WIREGUARD_ALLOWED_IPS"))

        val manyServers = (0..MAX_WIREGUARD_DNS_ENTRIES).joinToString(", ") { "10.0.${it / 256}.${it % 256}" }
        assertEquals(true, failure(conf(peer(KEY_B, "0.0.0.0/0", "192.0.2.1:51820"), dns = manyServers))?.contains("more than $MAX_WIREGUARD_DNS_ENTRIES"))

        val hugeKey = "A".repeat(600 * 1024)
        assertEquals(true, failure(conf(peer(hugeKey, "0.0.0.0/0", "192.0.2.1:51820")))?.contains("too large"))

        assertEquals("Peer 1 has an invalid PersistentKeepalive", failure(conf(peer(KEY_B, "0.0.0.0/0", "192.0.2.1:51820") + "PersistentKeepalive = 65536\n")))
        assertEquals(65535, (parse(conf(peer(KEY_B, "0.0.0.0/0", "192.0.2.1:51820") + "PersistentKeepalive = 65535\n")) as WireGuardBean).persistentKeepalive)
    }

    @Test
    fun amneziaVpnLinkKeepsRoutesKeepaliveAndDns() = runTest {
        val text = conf(
            """
            [Peer]
            PublicKey = $KEY_B
            Endpoint = 198.51.100.7:51820
            AllowedIPs = 0.0.0.0/0, ::/0
            PersistentKeepalive = 15
            """,
            dns = "1.1.1.1",
        ).replace("[Interface]\n", "[Interface]\nJc = 4\nH1 = 1\n")
        val link = "vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

        val bean = parseProxies(link).single() as AmneziaWGBean

        assertEquals(4, bean.jc)
        assertEquals("0.0.0.0/0\n::/0", bean.allowedIPs)
        assertEquals(15, bean.persistentKeepalive)
        assertEquals("1.1.1.1", bean.importedDnsServers)
        assertEquals(WireGuardDnsMode.APP, bean.dnsMode)
    }

    @Test
    fun wireguardLinkKeepsDnsAndKeepaliveAndUsesTheFullTunnelConvention() = runTest {
        val link = "wireguard://${encode(KEY_A)}@198.51.100.7:51820?publickey=${encode(KEY_B)}" +
            "&address=${encode("10.0.0.2/32")}&dns=${encode("1.1.1.1,https://dns.example/dns-query,corp.example")}&keepalive=25#wg"

        val bean = parseProxies(link).single() as WireGuardBean

        // URLs are servers too, never search domains.
        assertEquals("1.1.1.1\nhttps://dns.example/dns-query", bean.importedDnsServers)
        assertEquals("corp.example", bean.importedDnsDomains)
        assertEquals(25, bean.persistentKeepalive)
        assertEquals("", bean.allowedIPs)
        assertEquals(WireGuardDnsMode.APP, bean.dnsMode)
    }

    private fun clash(node: String) = "proxies:\n" + node.trimIndent().lines().joinToString("\n") { "  $it" }

    @Test
    fun clashPeersFollowMihomo() = runTest {
        val yaml = clash(
            """
            - name: wg-clash
              type: wireguard
              private-key: $KEY_A
              ip: 10.9.0.2
              ipv6: fd00:9::2
              mtu: 1280
              persistent-keepalive: 20
              reserved: [1, 2, 3]
              dns: [10.9.0.1, "https://dns.example/dns-query", dns.example]
              peers:
                - server: 192.0.2.10
                  port: 51820
                  public-key: $KEY_B
                  allowed-ips: ['10.9.0.0/16']
                - server: 192.0.2.11
                  port: 51821
                  public-key: $KEY_C
                  pre-shared-key: $KEY_D
                  allowed-ips: ['10.10.0.0/16', 'fd10::/16']
                  reserved: [4, 5, 6]
                - server: 192.0.2.12
                  port: 51822
                  public-key: $KEY_D
                  allowed-ips: ['10.11.0.0/16']
            """,
        )

        val bean = RawUpdater.parseRaw(yaml)!!.single() as WireGuardBean

        assertEquals(KEY_A, bean.privateKey)
        assertEquals("10.9.0.2/32\nfd00:9::2/128", bean.localAddress)
        assertEquals(1280, bean.mtu)
        assertEquals("192.0.2.10", bean.serverAddress)
        assertEquals(KEY_B, bean.peerPublicKey)
        assertEquals("10.9.0.0/16", bean.allowedIPs)
        assertEquals(20, bean.persistentKeepalive)
        // Mihomo reads every dns entry as a name server.
        assertEquals("10.9.0.1\nhttps://dns.example/dns-query\ndns.example", bean.importedDnsServers)
        assertEquals("", bean.importedDnsDomains)
        val peers = bean.wireGuardSettings()!!.orderedPeers()
        assertEquals(listOf(KEY_B, KEY_C, KEY_D), peers.map { it.public_key })
        // The top-level keepalive and reserved bytes apply to every peer without its own.
        assertEquals(listOf(20, 20, 20), peers.map { it.persistent_keepalive_interval })
        assertEquals(listOf("AQID", "BAUG", "AQID"), peers.map { it.reserved })
        assertEquals(KEY_D, peers[1].pre_shared_key)
        assertEquals(listOf("10.10.0.0/16", "fd10::/16"), peers[1].allowed_ips)
    }

    @Test
    fun clashTopLevelPeerKeepsTheFullTunnelConvention() = runTest {
        val yaml = clash(
            """
            - name: wg-single
              type: wireguard
              server: 192.0.2.10
              port: 51820
              private-key: $KEY_A
              public-key: $KEY_B
              ip: 10.9.0.2
            """,
        )

        val bean = RawUpdater.parseRaw(yaml)!!.single() as WireGuardBean

        assertEquals("", bean.allowedIPs)
        assertEquals(listOf("0.0.0.0/0", "::/0"), bean.wireGuardSettings()!!.orderedPeers().single().allowed_ips)
    }

    private fun clashWithSecondPeer(topLevel: List<String> = emptyList(), secondPeer: List<String>) = buildString {
        appendLine("proxies:")
        appendLine("  - name: checked")
        appendLine("    type: wireguard")
        appendLine("    private-key: $KEY_A")
        appendLine("    ip: 10.9.0.2")
        topLevel.forEach { appendLine("    $it") }
        appendLine("    peers:")
        appendLine("      - server: 192.0.2.10")
        appendLine("        port: 51820")
        appendLine("        public-key: $KEY_B")
        appendLine("        allowed-ips: ['10.9.0.0/16']")
        secondPeer.forEachIndexed { index, line -> appendLine(if (index == 0) "      - $line" else "        $line") }
        appendLine("  - name: other")
        appendLine("    type: socks5")
        appendLine("    server: 192.0.2.30")
        appendLine("    port: 1080")
    }

    @Test
    fun clashNodesThatCannotBeImportedFaithfullyAreSkipped() = runTest {
        val validPeer = listOf("server: 192.0.2.11", "port: 51821", "public-key: $KEY_C", "allowed-ips: ['10.10.0.0/16']")
        assertEquals(listOf("checked", "other"), RawUpdater.parseRaw(clashWithSecondPeer(secondPeer = validPeer))!!.map { it.name })

        val cases = mapOf(
            "peer without allowed-ips" to clashWithSecondPeer(secondPeer = validPeer.dropLast(1)),
            "peer with empty allowed-ips" to clashWithSecondPeer(secondPeer = validPeer.dropLast(1) + "allowed-ips: []"),
            "peer without server" to clashWithSecondPeer(secondPeer = validPeer.drop(1)),
            "line break in a key" to clashWithSecondPeer(
                secondPeer = listOf("server: 192.0.2.11", "port: 51821", "public-key: \"$KEY_C\\nEndpoint = 203.0.113.1:1\"", "allowed-ips: ['10.10.0.0/16']"),
            ),
            "line break in a route" to clashWithSecondPeer(secondPeer = validPeer.dropLast(1) + "allowed-ips: [\"10.10.0.0/16\\n0.0.0.0/0\"]"),
            "reserved of the wrong length" to clashWithSecondPeer(secondPeer = validPeer + "reserved: [1, 2]"),
            "unsupported DNS server" to clashWithSecondPeer(listOf("dns: ['dhcp://en0']"), validPeer),
            "AmneziaWG options" to clashWithSecondPeer(listOf("amnezia-wg-option:", "  jc: 4"), validPeer),
        )
        for ((case, yaml) in cases) {
            assertEquals(case, listOf("other"), RawUpdater.parseRaw(yaml)!!.map { it.name })
        }
    }

    @Test
    fun parsersNeverSetTheUserDnsSettings() = runTest {
        val beans = listOf(
            parse(conf(peer(KEY_B, "0.0.0.0/0", "192.0.2.1:51820"), dns = "10.0.0.1, corp.example")),
            parseProxies("wireguard://${encode(KEY_A)}@198.51.100.7:51820?publickey=${encode(KEY_B)}&address=10.0.0.2&dns=10.0.0.1").single(),
        )
        for (bean in beans) {
            val settings = bean.wireGuardSettings()!!
            assertEquals(WireGuardDnsMode.APP, settings.dnsMode)
            assertEquals("", settings.customDnsServers)
            assertEquals("", settings.customDnsDomains)
        }
        assertFalse(beans.any { it.wireGuardSettings()!!.importedDnsServers.isEmpty() })
    }

    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val KEY_A = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="
        const val KEY_B = "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI="
        const val KEY_C = "Q0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0M="
        const val KEY_D = "REREREREREREREREREREREREREREREREREREREREREQ="
    }
}
