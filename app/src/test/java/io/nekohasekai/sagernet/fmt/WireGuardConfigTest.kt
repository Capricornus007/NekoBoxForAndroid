package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.wireguard.MAX_WIREGUARD_PEERS
import io.nekohasekai.sagernet.fmt.wireguard.MAX_WIREGUARD_PROFILE_BYTES
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardDnsMode
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardServiceStatus
import io.nekohasekai.sagernet.fmt.wireguard.wireGuardServiceStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WireGuardConfigTest {
    private val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
    private val secondKey = Base64.getEncoder().encodeToString(ByteArray(32) { 2 })
    private val thirdKey = Base64.getEncoder().encodeToString(ByteArray(32) { 3 })

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    private fun wg(name: String, block: WireGuardBean.() -> Unit = {}) = WireGuardBean().apply {
        this.name = name
        serverAddress = "192.0.2.20"
        serverPort = 51820
        localAddress = "10.7.0.2/32\nfd00:7::2/128"
        privateKey = key
        peerPublicKey = key
        initializeDefaultValues()
        block()
    }

    private fun WireGuardBean.profileDns(servers: String, mode: Int, domains: String = "") {
        importedDnsServers = servers
        dnsMode = mode
        importedDnsDomains = domains
    }

    private fun add(bean: AbstractBean, groupId: Long = 0L, order: Long = 0L): ProxyEntity = ProxyEntity(groupId = groupId, userOrder = order).putBean(bean.apply { initializeDefaultValues() })
        .also { it.id = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.addProxy(it) } }

    private fun socks(name: String) = SOCKSBean().apply {
        this.name = name
        serverAddress = "192.0.2.30"
        serverPort = 1080
    }

    private fun chain(name: String, vararg hops: ProxyEntity) = ChainBean().apply {
        this.name = name
        proxies = hops.map { it.id }
    }

    private fun rules(vararg rules: RuleEntity) = ConfigBuilderTestEnv.io {
        SagerDatabase.rulesDao.reset()
        rules.forEachIndexed { index, rule ->
            rule.enabled = true
            rule.userOrder = index.toLong()
            SagerDatabase.rulesDao.createRule(rule)
        }
    }

    private fun build(profile: ProxyEntity, forTest: Boolean = false) = ConfigBuilderTestEnv.io { buildConfig(profile, forTest = forTest) }

    private fun failure(profile: ProxyEntity) = assertThrows(IllegalArgumentException::class.java) { build(profile) }.message!!

    private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.orEmpty()

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }

    private fun outbound(config: JSONObject, tag: String) = config.objects("outbounds").single { it.getString("tag") == tag }

    private fun dnsServer(config: JSONObject, tag: String) = config.getJSONObject("dns").objects("servers").single { it.getString("tag") == tag }

    /** Checks that every detour resolves, then hands the config to the core check in libcore. */
    private fun save(name: String, result: ConfigBuildResult): JSONObject {
        val config = JSONObject(result.config)
        val tags = config.objects("outbounds").map { it.getString("tag") }
        config.getJSONObject("dns").objects("servers").mapNotNull { it.optString("detour").takeIf(String::isNotEmpty) }
            .forEach { assertTrue("unresolved DNS detour $it in $tags", it in tags) }
        File("build/generated-core-configs/wireguard").apply { mkdirs() }.resolve("$name.json").writeText(result.config)
        return config
    }

    @Test
    fun profileWithoutNewSettingsKeepsItsSinglePeerOptions() {
        val bean = wg("legacy")
        assertEquals(2, KryoConverters.serialize(bean)[0].toInt())
        val profile = add(bean)

        val outbound = outbound(save("legacy", build(profile)), "legacy")

        assertEquals("192.0.2.20", outbound.getString("server"))
        assertEquals(key, outbound.getString("peer_public_key"))
        assertFalse(outbound.has("peers"))
    }

    @Test
    fun routesKeepaliveAndExtraPeersBecomeThePeersList() {
        val extra = "[Peer]\nPublicKey = $secondKey\nEndpoint = [2001:db8::9]:51821\nAllowedIPs = 10.9.0.0/16\nPersistentKeepalive = 10\n\n" +
            "[Peer]\nPublicKey = $thirdKey\nAllowedIPs = 172.16.0.0/12, 192.168.7.1\n"
        val profile = add(
            wg("peers") {
                allowedIPs = "10.0.0.0/8\nfd00::/8"
                persistentKeepalive = 25
                extraPeers = extra
            },
        )
        val amnezia = add(
            AmneziaWGBean().apply {
                name = "amnezia"
                serverAddress = "192.0.2.21"
                serverPort = 51820
                localAddress = "10.7.0.3/32"
                privateKey = key
                peerPublicKey = key
                initializeDefaultValues()
                jc = 4
                jmin = 40
                jmax = 70
                allowedIPs = "0.0.0.0/0"
                extraPeers = extra
            },
        )

        val outbound = outbound(save("peers", build(profile)), "peers")
        val peers = outbound.objects("peers")

        assertFalse(outbound.has("server"))
        assertEquals(3, peers.size)
        assertEquals("192.0.2.20", peers[0].getString("server"))
        assertEquals(listOf("10.0.0.0/8", "fd00::/8"), peers[0].getJSONArray("allowed_ips").strings())
        assertEquals(25, peers[0].getInt("persistent_keepalive_interval"))
        assertEquals("2001:db8::9", peers[1].getString("server"))
        assertEquals(51821, peers[1].getInt("server_port"))
        assertEquals(10, peers[1].getInt("persistent_keepalive_interval"))
        assertFalse(peers[2].has("server"))
        assertFalse(peers[2].has("persistent_keepalive_interval"))
        assertEquals(listOf("172.16.0.0/12", "192.168.7.1/32"), peers[2].getJSONArray("allowed_ips").strings())

        val amneziaOutbound = outbound(save("amnezia-peers", build(amnezia)), "amnezia")
        assertEquals("amneziawg", amneziaOutbound.getString("type"))
        assertEquals(4, amneziaOutbound.getInt("jc"))
        assertEquals(listOf("0.0.0.0/0"), amneziaOutbound.objects("peers")[0].getJSONArray("allowed_ips").strings())
        assertEquals(3, amneziaOutbound.objects("peers").size)
    }

    @Test
    fun singleFamilyRoutesAreNotWidened() {
        for ((routes, name) in listOf("0.0.0.0/0" to "ipv4-only", "::/0" to "ipv6-only")) {
            val profile = add(wg(name) { allowedIPs = routes })
            val peers = outbound(save(name, build(profile)), name).objects("peers")
            assertEquals(listOf(routes), peers.single().getJSONArray("allowed_ips").strings())
        }
    }

    @Test
    fun brokenPeersFailTheBuildWithTheirNumber() {
        val noRoutes = add(wg("no-routes") { extraPeers = "[Peer]\nPublicKey = $secondKey\nEndpoint = 192.0.2.40:51820\n" })
        assertTrue(failure(noRoutes), failure(noRoutes).contains("peer 2 has no Allowed IPs"))
        val badRoute = add(wg("bad-route") { allowedIPs = "10.0.0.0/40" })
        assertTrue(failure(badRoute).contains("peer 1 has an invalid Allowed IPs entry"))
    }

    @Test
    fun importedDnsStaysUnusedWhileTheAppDnsIsSelected() {
        val profile = add(wg("app-dns") { importedDnsServers = "10.0.0.1" })

        val remote = dnsServer(build(profile).config.let(::JSONObject), "dns-remote")

        assertEquals("resolver.example", remote.getString("server"))
        assertEquals("app-dns", remote.getString("detour"))
    }

    @Test
    fun allQueriesModeSendsRemoteDnsThroughTheTunnel() {
        val ipv4 = add(
            wg("dns-all") {
                allowedIPs = "10.0.0.0/8"
                importedDnsServers = "10.0.0.1\n10.0.0.2"
                dnsMode = WireGuardDnsMode.ALL
            },
        )
        val remote = dnsServer(save("dns-all", build(ipv4)), "dns-remote")
        assertEquals("udp", remote.getString("type"))
        assertEquals("10.0.0.1", remote.getString("server"))
        assertEquals("dns-all", remote.getString("detour"))

        val ipv6 = add(
            wg("dns-all-v6") {
                allowedIPs = "fd00::/8"
                importedDnsServers = "fd00::53"
                dnsMode = WireGuardDnsMode.ALL
            },
        )
        assertEquals("fd00::53", dnsServer(save("dns-all-v6", build(ipv6)), "dns-remote").getString("server"))

        val fullTunnel = add(
            wg("dns-full-tunnel") {
                importedDnsServers = "1.1.1.1"
                dnsMode = WireGuardDnsMode.ALL
            },
        )
        assertEquals("dns-full-tunnel", dnsServer(save("dns-full-tunnel", build(fullTunnel)), "dns-remote").getString("detour"))
    }

    @Test
    fun resolverOutsideAllowedIpsFailsInsteadOfGoingDirect() {
        val public = add(
            wg("split") {
                allowedIPs = "10.0.0.0/8"
                importedDnsServers = "1.1.1.1"
                dnsMode = WireGuardDnsMode.ALL
            },
        )
        assertTrue(failure(public).contains("1.1.1.1"))
        val wrongFamily = add(
            wg("split-v4") {
                allowedIPs = "0.0.0.0/0"
                importedDnsServers = "fd00::53"
                dnsMode = WireGuardDnsMode.DOMAINS
                importedDnsDomains = "corp.example"
            },
        )
        assertTrue(failure(wrongFamily).contains("fd00::53"))
        val credentials = add(
            wg("credentials") {
                importedDnsServers = "https://user:secret@dns.example/dns-query?token=abc"
                dnsMode = WireGuardDnsMode.ALL
            },
        )
        val message = failure(credentials)
        assertFalse(message.contains("secret") || message.contains("token") || message.contains("dns.example"))
        val missing = add(wg("missing") { dnsMode = WireGuardDnsMode.ALL })
        assertTrue(failure(missing).contains("no DNS server"))
    }

    @Test
    fun resolverHostNameIsLookedUpByTheDirectDns() {
        val profile = add(
            wg("dns-host") {
                importedDnsServers = "https://dns.example/dns-query"
                dnsMode = WireGuardDnsMode.ALL
            },
        )

        val remote = dnsServer(save("dns-host", build(profile)), "dns-remote")

        assertEquals("https", remote.getString("type"))
        assertEquals("dns.example", remote.getString("server"))
        assertEquals("dns-direct", remote.getJSONObject("domain_resolver").getString("server"))
        assertEquals("dns-host", remote.getString("detour"))
    }

    @Test
    fun listedDomainsUseTheirOwnServerBehindUserRules() {
        DataStore.enableDnsRouting = true
        DataStore.enableFakeDns = true
        val profile = add(
            wg("dns-domains") {
                allowedIPs = "10.0.0.0/8"
                importedDnsServers = "10.0.0.1"
                dnsMode = WireGuardDnsMode.DOMAINS
                importedDnsDomains = "corp.example\n.lab.example"
            },
        )
        rules(RuleEntity(domains = "full:user.example", outbound = -1L))

        val config = save("dns-domains", build(profile))
        val dnsRules = config.getJSONObject("dns").objects("rules")
        val serverTag = "dns-wg-${profile.id}"
        val profileRule = dnsRules.single { it.optString("server") == serverTag }
        val userRule = dnsRules.indexOfFirst { it.optJSONArray("domain")?.strings()?.contains("user.example") == true }
        val fakeRule = dnsRules.indexOfFirst { it.optString("server") == "dns-fake" }

        assertEquals(listOf("corp.example", "lab.example"), profileRule.getJSONArray("domain_suffix").strings())
        assertEquals("dns-domains", dnsServer(config, serverTag).getString("detour"))
        assertTrue(userRule in 0 until dnsRules.indexOf(profileRule))
        assertTrue(dnsRules.indexOf(profileRule) < fakeRule)
        assertEquals("resolver.example", dnsServer(config, "dns-remote").getString("server"))
    }

    @Test
    fun selectorRejectsAllQueriesModeButKeepsListedDomains() {
        val groupId = ConfigBuilderTestEnv.io { SagerDatabase.groupDao.createGroup(ProxyGroup(isSelector = true)) }
        val member = add(
            wg("member") {
                importedDnsServers = "10.0.0.1"
                dnsMode = WireGuardDnsMode.ALL
            },
            groupId,
        )
        add(socks("other"), groupId, 1)
        assertTrue(failure(member).contains("selector group"))

        ConfigBuilderTestEnv.io {
            SagerDatabase.proxyDao.updateProxy(
                member.putBean(
                    (member.requireBean() as WireGuardBean).apply {
                        dnsMode = WireGuardDnsMode.DOMAINS
                        importedDnsDomains = "corp.example"
                    },
                ),
            )
        }
        val config = save("selector-domains", build(member))
        assertTrue(config.getJSONObject("dns").objects("rules").any { it.optString("server") == "dns-wg-${member.id}" })
    }

    @Test
    fun conflictingProfileDnsIsRejected() {
        val first = add(wg("first") { profileDns("10.0.0.1", WireGuardDnsMode.ALL) })
        val second = add(wg("second") { profileDns("10.0.0.2", WireGuardDnsMode.ALL) })
        val twoTunnels = add(chain("two-tunnels", first, second))
        assertTrue(failure(twoTunnels).contains("first") && failure(twoTunnels).contains("second"))

        val corp = add(wg("corp") { profileDns("10.0.0.1", WireGuardDnsMode.DOMAINS, "corp.example") })
        val lab = add(wg("lab") { profileDns("10.0.0.2", WireGuardDnsMode.DOMAINS, "a.corp.example") })
        rules(RuleEntity(domains = "full:a.corp.example", outbound = lab.id))
        assertTrue(failure(corp).contains("corp and lab both resolve corp.example"))

        ConfigBuilderTestEnv.io {
            SagerDatabase.proxyDao.updateProxy(lab.putBean((lab.requireBean() as WireGuardBean).apply { importedDnsDomains = "lab.example" }))
        }
        val dns = save("domains-two-profiles", build(corp)).getJSONObject("dns")
        assertEquals(2, dns.objects("servers").count { it.getString("tag").startsWith("dns-wg-") })
    }

    @Test
    fun routingRuleOutboundKeepsTheAppDnsForAllQueries() {
        val main = add(socks("main"))
        val tunnel = add(wg("tunnel") { profileDns("10.0.0.1", WireGuardDnsMode.ALL) })
        rules(RuleEntity(domains = "full:corp.example", outbound = tunnel.id))

        val remote = dnsServer(build(main).config.let(::JSONObject), "dns-remote")

        assertEquals("resolver.example", remote.getString("server"))
        assertEquals("main", remote.getString("detour"))
    }

    @Test
    fun profileInTwoChainsReportsEveryInstance() {
        val tunnel = add(wg("tunnel"))
        val chainA = add(chain("chain-a", add(socks("hop-a")), tunnel))
        val chainB = add(chain("chain-b", add(socks("hop-b")), tunnel))
        rules(RuleEntity(domains = "full:b.example", outbound = chainB.id))

        val result = build(chainA)
        save("two-chains", result)
        val instances = result.wireguardInstances.getValue(tunnel.id)

        assertEquals(listOf(chainA.id, chainB.id), instances.map { it.ownerProfileId })
        assertNotEquals(instances[0].tag, instances[1].tag)
        val tags = JSONObject(result.config).objects("outbounds").map { it.getString("tag") }
        assertTrue(tags.containsAll(instances.map { it.tag }))

        val peers = """{"peers":[{"publicKey":"$key","endpoint":"192.0.2.20:51820","lastHandshake":0,"rxBytes":0,"txBytes":0}]}"""
        val status = parseWireGuardServiceStatus(
            wireGuardServiceStatus(true, instances) { tag -> if (tag == instances[0].tag) peers else error("wireguard:unavailable: device closed") },
        )
        assertTrue(status.running)
        assertEquals(0L, status.instances[0].peers.single().lastHandshake)
        assertNull(status.instances[0].error)
        assertTrue(status.instances[1].peers.isEmpty())
        assertTrue(status.instances[1].error!!.contains("unavailable"))
        assertFalse(parseWireGuardServiceStatus(wireGuardServiceStatus(false, emptyList()) { "" }).running)
    }

    @Test
    fun serverPeerKeepsItsPositionAmongTheAdditionalPeers() {
        val extra = "[Peer]\nPublicKey = $secondKey\nAllowedIPs = 0.0.0.0/0\n\n[Peer]\nPublicKey = $thirdKey\nEndpoint = 192.0.2.41:51820\nAllowedIPs = 0.0.0.0/0\n"
        for ((position, order) in listOf(0 to listOf(key, secondKey, thirdKey), 1 to listOf(secondKey, key, thirdKey), 2 to listOf(secondKey, thirdKey, key))) {
            val profile = add(
                wg("ordered-$position") {
                    allowedIPs = "0.0.0.0/0"
                    extraPeers = extra
                    serverPeerPosition = position
                },
            )
            val peers = outbound(save("ordered-$position", build(profile)), "ordered-$position").objects("peers")
            // Every 0.0.0.0/0 declaration stays; the core gives the prefix to the last of them.
            assertEquals(order, peers.map { it.getString("public_key") })
            assertTrue(peers.all { it.getJSONArray("allowed_ips").strings() == listOf("0.0.0.0/0") })
        }
        val outOfRange = add(
            wg("out-of-range") {
                extraPeers = extra
                serverPeerPosition = 3
            },
        )
        assertTrue(failure(outOfRange).contains("between 0 and 2"))
        val withoutPeers = add(wg("position-without-peers") { serverPeerPosition = 1 })
        assertTrue(failure(withoutPeers).contains("between 0 and 0"))
    }

    @Test
    fun customDnsOverridesTheImportedServersAndBlankFollowsThem() {
        val profile = add(
            wg("override") {
                allowedIPs = "10.0.0.0/8"
                importedDnsServers = "10.0.0.1"
                importedDnsDomains = "corp.example"
                dnsMode = WireGuardDnsMode.ALL
            },
        )
        assertEquals("10.0.0.1", dnsServer(save("dns-imported", build(profile)), "dns-remote").getString("server"))

        ConfigBuilderTestEnv.io {
            SagerDatabase.proxyDao.updateProxy(profile.putBean((profile.requireBean() as WireGuardBean).apply { customDnsServers = "10.0.0.53" }))
        }
        assertEquals("10.0.0.53", dnsServer(save("dns-custom", build(profile)), "dns-remote").getString("server"))

        ConfigBuilderTestEnv.io {
            SagerDatabase.proxyDao.updateProxy(
                profile.putBean(
                    (profile.requireBean() as WireGuardBean).apply {
                        dnsMode = WireGuardDnsMode.DOMAINS
                        customDnsDomains = "lab.example"
                    },
                ),
            )
        }
        val rule = JSONObject(build(profile).config).getJSONObject("dns").objects("rules").single { it.optString("server") == "dns-wg-${profile.id}" }
        assertEquals(listOf("lab.example"), rule.getJSONArray("domain_suffix").strings())

        // An enabled mode with nothing imported and nothing entered fails instead of falling back.
        val empty = add(wg("nothing-imported") { dnsMode = WireGuardDnsMode.ALL })
        assertTrue(failure(empty).contains("no DNS server"))
    }

    @Test
    fun globalModeLeavesOutDomainsOfProfilesOnlyRulesUse() {
        val main = add(socks("main"))
        val tunnel = add(
            wg("rule-tunnel") {
                allowedIPs = "10.0.0.0/8"
                importedDnsServers = "10.0.0.1"
                dnsMode = WireGuardDnsMode.DOMAINS
                importedDnsDomains = "corp.example"
            },
        )
        rules(RuleEntity(domains = "full:corp.example", outbound = tunnel.id))
        fun hasProfileDns() = JSONObject(build(main).config).getJSONObject("dns").objects("servers").any { it.getString("tag") == "dns-wg-${tunnel.id}" }

        assertTrue(hasProfileDns())
        DataStore.globalMode = true
        try {
            assertFalse(hasProfileDns())
            // The selected chain keeps its own listed domains in Global mode.
            assertTrue(JSONObject(build(tunnel).config).getJSONObject("dns").objects("servers").any { it.getString("tag") == "dns-wg-${tunnel.id}" })
        } finally {
            DataStore.globalMode = false
        }
    }

    @Test
    fun additionalPeerReservedBytesReachThePeer() {
        val profile = add(
            wg("reserved") {
                allowedIPs = "10.0.0.0/8"
                extraPeers = "[Peer]\nPublicKey = $secondKey\nEndpoint = 192.0.2.42:51820\nAllowedIPs = 10.1.0.0/16\nReserved = 4, 5, 6\n"
            },
        )
        val peers = outbound(save("reserved", build(profile)), "reserved").objects("peers")
        assertEquals("BAUG", peers[1].getString("reserved"))

        val broken = add(wg("bad-reserved") { extraPeers = "[Peer]\nPublicKey = $secondKey\nAllowedIPs = 10.1.0.0/16\nReserved = 4, 5\n" })
        assertTrue(failure(broken).contains("peer 2 is invalid"))
        val stray = add(wg("stray-text") { extraPeers = "PublicKey = $secondKey\n[Peer]\nPublicKey = $thirdKey\nAllowedIPs = 10.1.0.0/16\n" })
        assertTrue(failure(stray).contains("must be [Peer] sections"))
    }

    @Test
    fun serverPeerReservedBytesAreValidatedWithoutChangingStoredValues() {
        for (value in listOf("1, 2", "AQI=", "not-base64")) {
            val profile = add(
                wg("bad-server-reserved") {
                    allowedIPs = "10.0.0.0/8"
                    reserved = value
                },
            )
            assertTrue(failure(profile).contains("peer 1 is invalid"))
            val stored = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.getById(profile.id) }!!
            assertEquals(value, (stored.requireBean() as WireGuardBean).reserved)
        }
        val valid = add(
            wg("server-reserved") {
                allowedIPs = "10.0.0.0/8"
                reserved = "1, 2, 3"
            },
        )
        val peers = outbound(save("server-reserved", build(valid)), "server-reserved").objects("peers")
        assertEquals("AQID", peers.single().getString("reserved"))
    }

    @Test
    fun oversizedStoredProfilesFailTheBuildInsteadOfBeingCut() {
        val peers = (1..MAX_WIREGUARD_PEERS).joinToString("\n") { "[Peer]\nPublicKey = $secondKey\nAllowedIPs = 10.${it / 256}.${it % 256}.0/24\n" }
        val tooMany = add(wg("too-many-peers") { extraPeers = peers })
        assertTrue(failure(tooMany).contains("more than $MAX_WIREGUARD_PEERS peers"))
        val keepalive = add(wg("keepalive-out-of-range") { persistentKeepalive = 70000 })
        assertTrue(failure(keepalive).contains("between 0 and 65535"))
        val tooLarge = add(wg("too-large") { extraPeers = "[Peer]\nPublicKey = ${"A".repeat(MAX_WIREGUARD_PROFILE_BYTES)}\nAllowedIPs = 10.0.0.0/8\n" })
        assertTrue(failure(tooLarge).contains("too large"))
    }

    @Test
    fun urlTestsIgnoreProfileDns() {
        val profile = add(
            wg("probe") {
                allowedIPs = "10.0.0.0/8"
                importedDnsServers = "1.1.1.1"
                dnsMode = WireGuardDnsMode.ALL
            },
        )

        val dns = JSONObject(build(profile, forTest = true).config).getJSONObject("dns")

        assertFalse(dns.objects("servers").any { it.getString("tag") == "dns-remote" })
    }
}
