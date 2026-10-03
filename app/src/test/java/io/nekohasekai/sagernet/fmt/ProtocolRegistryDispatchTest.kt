package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProtocolRegistry
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.toUri
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.juicity.JuicityBean
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.toUri
import io.nekohasekai.sagernet.fmt.shadowsocksr.ShadowsocksRBean
import io.nekohasekai.sagernet.fmt.snell.SnellBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.toUriVMessVLESSTrojan
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ui.profile.*
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.AnyTLSSettingsActivity
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.config.ConfigSettingActivity
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSSettingsActivity
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

/**
 * Wire-format safety net for the protocol descriptor registry (Plan 029, Option C).
 *
 * The registry may change *how* type dispatch is expressed (putByteArray / requireBean /
 * putBean) but must NEVER change the on-disk Kryo wire format or the persisted TYPE_* ids -
 * existing device profiles deserialize by exactly these bytes + ids. This Robolectric test runs
 * on the Namespace unit-test job and provides the golden net:
 * for every persistable bean type it asserts
 *   1. serialize -> deserialize -> serialize is byte-stable (write/read paths are symmetric), and
 *   2. the ProxyEntity type-dispatch round-trips: putBean(bean) sets the expected TYPE_* id and
 *      the correct typed field; serialize(requireBean()) reproduces the bytes; and
 *      putByteArray(type, bytes) then requireBean() yields a bean that re-serializes identically.
 *
 * If the registry ever maps a type id to the wrong (de)serializer or drops a field, one of these
 * assertions fails - catching the "profiles become undeserializable / mis-typed" data hazard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProtocolRegistryDispatchTest {

    private fun <T : AbstractBean> byteStable(bean: T) {
        bean.initializeDefaultValues()
        val first = KryoConverters.serialize(bean)
        // A populated bean must not serialize to empty (empty => null-bean path).
        assertTrue("bean serialized to empty bytes: ${bean.javaClass.simpleName}", first.isNotEmpty())
        val decoded = KryoConverters.deserialize(bean.javaClass.getDeclaredConstructor().newInstance(), first)
        val second = KryoConverters.serialize(decoded)
        assertArrayEquals("round-trip not byte-stable: ${bean.javaClass.simpleName}", first, second)
    }

    /**
     * Full ProxyEntity dispatch parity: putBean -> expected type id + serialize(requireBean())
     * byte-identical to serialize(bean); then putByteArray(type, bytes) -> requireBean()
     * re-serializes to the same bytes (deserialize dispatch lands on the right converter).
     */
    private fun dispatchParity(bean: AbstractBean, expectedType: Int) {
        bean.initializeDefaultValues()
        val entity = ProxyEntity()
        entity.putBean(bean)
        assertEquals("putBean set wrong type id for ${bean.javaClass.simpleName}", expectedType, entity.type)

        val viaRequireBean = KryoConverters.serialize(entity.requireBean())
        val direct = KryoConverters.serialize(bean)
        assertArrayEquals("requireBean() bytes differ for ${bean.javaClass.simpleName}", direct, viaRequireBean)

        // Deserialize dispatch: a fresh entity of the same type must land the bytes on the
        // correct typed field and re-serialize identically.
        val entity2 = ProxyEntity()
        entity2.type = expectedType
        entity2.putByteArray(direct)
        val roundTrip = KryoConverters.serialize(entity2.requireBean())
        assertArrayEquals("putByteArray dispatch differs for ${bean.javaClass.simpleName}", direct, roundTrip)
    }

    private fun socks() = SOCKSBean().apply {
        serverAddress = "192.0.2.1"
        serverPort = 1080
        username = "u"
        password = "p"
    }
    private fun http() = HttpBean().apply {
        serverAddress = "192.0.2.2"
        serverPort = 8080
        username = "u"
        password = "p"
    }
    private fun ss() = ShadowsocksBean().apply {
        serverAddress = "192.0.2.3"
        serverPort = 8388
        method = "aes-128-gcm"
        password = "p"
    }
    private fun ssr() = ShadowsocksRBean().apply {
        serverAddress = "192.0.2.4"
        serverPort = 8389
        method = "aes-128-cfb"
        password = "p"
        protocol = "origin"
        obfs = "plain"
    }
    private fun vmess() = VMessBean().apply {
        serverAddress = "192.0.2.5"
        serverPort = 443
        uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
    }
    private fun trojan() = TrojanBean().apply {
        serverAddress = "192.0.2.6"
        serverPort = 443
        password = "p"
    }
    private fun mieru() = MieruBean().apply {
        serverAddress = "192.0.2.8"
        serverPort = 4443
        username = "u"
        password = "p"
    }
    private fun naive() = NaiveBean().apply {
        serverAddress = "192.0.2.9"
        serverPort = 443
        username = "u"
        password = "p"
    }
    private fun hysteria() = HysteriaBean().apply {
        serverAddress = "192.0.2.10"
        serverPorts = "443"
    }
    private fun ssh() = SSHBean().apply {
        serverAddress = "192.0.2.11"
        serverPort = 22
        username = "u"
        password = "p"
    }
    private fun wg() = WireGuardBean().apply {
        serverAddress = "192.0.2.12"
        serverPort = 51820
    }
    private fun awg() = AmneziaWGBean().apply {
        serverAddress = "192.0.2.13"
        serverPort = 51820
    }
    private fun tailscale() = TailscaleBean().apply {
        authKey = "tskey-auth-test"
        exitNode = "100.64.0.1"
        onlyTcp443 = true
    }
    private fun tuic() = TuicBean().apply {
        serverAddress = "192.0.2.14"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000000"
        token = "t"
    }
    private fun juicity() = JuicityBean().apply {
        serverAddress = "192.0.2.15"
        serverPort = 443
        uuid = "00000000-0000-0000-0000-000000000000"
        password = "p"
    }
    private fun shadowTls() = ShadowTLSBean().apply {
        serverAddress = "192.0.2.16"
        serverPort = 443
    }
    private fun anyTls() = AnyTLSBean().apply {
        serverAddress = "192.0.2.17"
        serverPort = 443
        password = "p"
    }
    private fun snell() = SnellBean().apply {
        serverAddress = "192.0.2.18"
        serverPort = 443
        psk = "k"
    }
    private fun chain() = ChainBean().apply { name = "chain" }
    private fun config() = ConfigBean().apply { name = "config" }

    private val allBeans: List<Pair<AbstractBean, Int>> = listOf(
        socks() to ProxyEntity.TYPE_SOCKS,
        http() to ProxyEntity.TYPE_HTTP,
        ss() to ProxyEntity.TYPE_SS,
        ssr() to ProxyEntity.TYPE_SSR,
        vmess() to ProxyEntity.TYPE_VMESS,
        trojan() to ProxyEntity.TYPE_TROJAN,
        mieru() to ProxyEntity.TYPE_MIERU,
        naive() to ProxyEntity.TYPE_NAIVE,
        hysteria() to ProxyEntity.TYPE_HYSTERIA,
        ssh() to ProxyEntity.TYPE_SSH,
        wg() to ProxyEntity.TYPE_WG,
        awg() to ProxyEntity.TYPE_AWG,
        tailscale() to ProxyEntity.TYPE_TAILSCALE,
        tuic() to ProxyEntity.TYPE_TUIC,
        juicity() to ProxyEntity.TYPE_JUICITY,
        shadowTls() to ProxyEntity.TYPE_SHADOWTLS,
        anyTls() to ProxyEntity.TYPE_ANYTLS,
        snell() to ProxyEntity.TYPE_SNELL,
        chain() to ProxyEntity.TYPE_CHAIN,
        config() to ProxyEntity.TYPE_CONFIG,
    )

    @Test
    fun nativeProtocolConfigurationsUseTheApplicationBuilder() {
        ConfigBuilderTestEnv.reset()
        val directory = File("build/generated-core-configs/protocols").apply { mkdirs() }
        val key = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val beans = allBeans.map { it.first }.filterNot { it is ChainBean || it is ConfigBean }.toMutableList()
        beans += (1..5).map { version -> snell().apply { this.version = version } }
        beans += snell().apply {
            version = 5
            network = "udp"
        }
        beans += hysteria().apply { protocolVersion = 1 }
        beans += hysteria().apply { protocolVersion = 2 }
        beans += listOf("tcp", "ws", "http", "grpc", "xhttp").map { transport ->
            vmess().apply {
                alterId = -1
                type = transport
                security = "tls"
                sni = "example.invalid"
            }
        }
        for ((index, bean) in beans.withIndex()) {
            bean.initializeDefaultValues()
            when (bean) {
                is WireGuardBean -> {
                    bean.localAddress = "192.0.2.100/32"
                    bean.privateKey = key
                    bean.peerPublicKey = key
                }

                is AmneziaWGBean -> {
                    bean.localAddress = "192.0.2.100/32"
                    bean.privateKey = key
                    bean.peerPublicKey = key
                }
            }
            val profile = ProxyEntity(id = index + 1L).putBean(bean)
            if (profile.needExternal()) continue
            // Tailscale refuses test builds (a URL test would start a second node); build it as a
            // service config so the core still checks the generated endpoint.
            val config = ConfigBuilderTestEnv.io { buildConfig(profile, forTest = bean !is TailscaleBean).config }
            directory.resolve("$index-${bean.javaClass.simpleName}.json").writeText(config)
        }
    }

    @Test
    fun tailscaleRunsOneInstancePerConfig() {
        ConfigBuilderTestEnv.reset()
        val groupId = ConfigBuilderTestEnv.io { SagerDatabase.groupDao.createGroup(ProxyGroup(isSelector = true)) }
        fun add(bean: AbstractBean, order: Long = 0L) = ProxyEntity(groupId = groupId, userOrder = order).putBean(bean.apply { initializeDefaultValues() })
            .also { it.id = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.addProxy(it) } }
        val node = add(tailscale())
        val server = add(socks())
        // Both Tailscale hops are the same node, so this chain would start it twice.
        val twice = add(
            ChainBean().apply {
                name = "twice"
                proxies = listOf(node.id, server.id, node.id)
            },
        )
        // Built first as a group member: its Tailscale exit hop is appended before the broken
        // Hysteria hop fails, so the skipped chain must be rolled back and must not block the node.
        val broken = add(hysteria().apply { enableECH = true }, order = -2)
        add(
            ChainBean().apply {
                name = "broken-first"
                proxies = listOf(broken.id, node.id)
            },
            order = -1,
        )

        val urlTest = assertThrows(IllegalArgumentException::class.java) {
            ConfigBuilderTestEnv.io { buildConfig(node, forTest = true) }
        }
        assertTrue(urlTest.message!!, urlTest.message!!.contains("cannot be tested"))

        val duplicate = assertThrows(IllegalArgumentException::class.java) {
            ConfigBuilderTestEnv.io { buildConfig(twice, forExport = true) }
        }
        assertTrue(duplicate.message!!, duplicate.message!!.contains("once per configuration"))

        // A user block rule must stay ahead of the automatic tailnet rules (route and DNS).
        DataStore.enableDnsRouting = true
        ConfigBuilderTestEnv.io { SagerDatabase.rulesDao.createRule(RuleEntity(enabled = true, domains = "full:blocked.ts.test", outbound = -2)) }

        // As a selector member the broken chain is skipped before it touches the shared lists:
        // the group still builds and every detour points at an existing tag.
        val selector = JSONObject(ConfigBuilderTestEnv.io { buildConfig(server).config })
        val tags = mutableSetOf<String>()
        val detours = mutableSetOf<String>()
        for (key in listOf("outbounds", "endpoints")) {
            val array = selector.optJSONArray(key) ?: continue
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                tags += item.getString("tag")
                item.optString("detour").takeIf { it.isNotEmpty() }?.let { detours += it }
            }
        }
        assertTrue("$detours not in $tags", tags.containsAll(detours))
        assertTrue(tags.toString(), tags.count { it.startsWith("Tailscale") } == 1)
        val endpoints = selector.getJSONArray("endpoints")
        assertEquals(1, endpoints.length())
        assertEquals("tailscale/${node.id}", endpoints.getJSONObject(0).getString("state_directory"))

        // MagicDNS resolver bound to the node, and tailnet destinations routed to it.
        val tsTag = endpoints.getJSONObject(0).getString("tag")
        val dnsServers = selector.getJSONObject("dns").getJSONArray("servers")
        val magic = (0 until dnsServers.length()).map { dnsServers.getJSONObject(it) }.single { it.optString("type") == "tailscale" }
        assertEquals(tsTag, magic.getString("endpoint"))
        assertTrue(magic.getBoolean("accept_search_domain"))
        val dnsRules = selector.getJSONObject("dns").getJSONArray("rules")
        val magicRule = (0 until dnsRules.length()).map { dnsRules.getJSONObject(it) }.single { it.has("preferred_by") }
        assertEquals(magic.getString("tag"), magicRule.getString("server"))
        assertEquals(magic.getString("tag"), magicRule.getJSONArray("preferred_by").getString(0))
        val routeRules = (0 until selector.getJSONObject("route").getJSONArray("rules").length())
            .map { selector.getJSONObject("route").getJSONArray("rules").getJSONObject(it) }
        val tailnetRule = routeRules.single { it.has("preferred_by") }
        assertEquals(tsTag, tailnetRule.getString("outbound"))
        assertEquals(tsTag, tailnetRule.getJSONArray("preferred_by").getString(0))
        val blockRule = routeRules.single { it.optString("action") == "reject" && it.has("domain") }
        assertTrue("block rule after tailnet rule", routeRules.indexOf(blockRule) < routeRules.indexOf(tailnetRule))
        val dnsRuleList = (0 until dnsRules.length()).map { dnsRules.getJSONObject(it) }
        val dnsBlock = dnsRuleList.single { it.optString("action") == "predefined" && it.has("domain") }
        assertTrue("DNS block rule after MagicDNS rule", dnsRuleList.indexOf(dnsBlock) < dnsRuleList.indexOf(magicRule))

        ConfigBuilderTestEnv.io { SagerDatabase.groupDao.updateGroup(ProxyGroup(id = groupId, isSelector = true, landingProxy = node.id)) }
        val landing = assertThrows(IllegalArgumentException::class.java) {
            ConfigBuilderTestEnv.io { buildConfig(server) }
        }
        assertTrue(landing.message!!, landing.message!!.contains("landing proxy"))
    }

    @Test
    fun everyBean_roundTripIsByteStable() {
        for ((bean, _) in allBeans) byteStable(bean)
    }

    @Test
    fun emptyBlob_clearsTheTypedBeanForEveryProtocol() {
        for ((bean, type) in allBeans) {
            val entity = ProxyEntity().putBean(bean)
            entity.putByteArray(byteArrayOf())
            org.junit.Assert.assertNull(ProtocolRegistry.forType(type)!!.getBean(entity))
        }
    }

    @Test
    fun everyBean_proxyEntityDispatchParity() {
        for ((bean, type) in allBeans) dispatchParity(bean, type)
    }

    @Test
    fun standardLinkDispatch_matchesExistingFormatters() {
        val socks = socks().apply { initializeDefaultValues() }
        assertEquals(socks.toUri(), ProxyEntity().putBean(socks).toStdLink())
        val shadowsocks = ss().apply { initializeDefaultValues() }
        assertEquals(shadowsocks.toUri(), ProxyEntity().putBean(shadowsocks).toStdLink())
        val vmess = vmess().apply { initializeDefaultValues() }
        assertEquals(
            vmess.toUriVMessVLESSTrojan(false),
            ProxyEntity().putBean(vmess).toStdLink(),
        )
        val hysteria = hysteria().apply { initializeDefaultValues() }
        assertEquals(hysteria.toUri(), ProxyEntity().putBean(hysteria).toStdLink())
    }

    @Test
    fun dataOnlyEntriesKeepTheirBytesWithoutAnExecutionPath() {
        ConfigBuilderTestEnv.reset()
        for (type in listOf(7, 25, 27, 9997)) {
            val bean = ArchivedBean(type, byteArrayOf(1, 0, -1, 42))
            bean.initializeDefaultValues()
            val profile = ProxyEntity().putBean(bean)
            val bytes = KryoConverters.serialize(profile)
            val restored = KryoConverters.deserialize(ProxyEntity(), bytes)
            assertEquals(type, restored.type)
            assertArrayEquals(bytes, KryoConverters.serialize(restored))
            assertFalse(restored.needExternal())
            assertFalse(restored.canBuild())
            assertFalse(restored.haveSettings())
            assertThrows(IllegalArgumentException::class.java) {
                io.nekohasekai.sagernet.group.RawUpdater.requireUpdatableProfiles(listOf(restored))
            }
            assertNull(restored.settingIntent(org.robolectric.RuntimeEnvironment.getApplication(), false))
            assertArrayEquals(KryoConverters.serialize(bean), KryoConverters.serialize(parseUniversal(bean.toUniversalLink())))
            assertThrows(IllegalArgumentException::class.java) {
                ConfigBuilderTestEnv.io { buildConfig(restored, forTest = true) }
            }
        }
    }

    @Test
    fun registryMetadata_matchesEveryPersistableType() {
        val settingsActivities = mapOf(
            ProxyEntity.TYPE_SOCKS to SocksSettingsActivity::class.java,
            ProxyEntity.TYPE_HTTP to HttpSettingsActivity::class.java,
            ProxyEntity.TYPE_SS to ShadowsocksSettingsActivity::class.java,
            ProxyEntity.TYPE_SSR to ShadowsocksRSettingsActivity::class.java,
            ProxyEntity.TYPE_VMESS to VMessSettingsActivity::class.java,
            ProxyEntity.TYPE_TROJAN to TrojanSettingsActivity::class.java,
            ProxyEntity.TYPE_MIERU to MieruSettingsActivity::class.java,
            ProxyEntity.TYPE_NAIVE to NaiveSettingsActivity::class.java,
            ProxyEntity.TYPE_HYSTERIA to HysteriaSettingsActivity::class.java,
            ProxyEntity.TYPE_SSH to SSHSettingsActivity::class.java,
            ProxyEntity.TYPE_WG to WireGuardSettingsActivity::class.java,
            ProxyEntity.TYPE_AWG to AmneziaWGSettingsActivity::class.java,
            ProxyEntity.TYPE_TAILSCALE to TailscaleSettingsActivity::class.java,
            ProxyEntity.TYPE_TUIC to TuicSettingsActivity::class.java,
            ProxyEntity.TYPE_JUICITY to JuicitySettingsActivity::class.java,
            ProxyEntity.TYPE_SHADOWTLS to ShadowTLSSettingsActivity::class.java,
            ProxyEntity.TYPE_ANYTLS to AnyTLSSettingsActivity::class.java,
            ProxyEntity.TYPE_CHAIN to ChainSettingsActivity::class.java,
            ProxyEntity.TYPE_CONFIG to ConfigSettingActivity::class.java,
            ProxyEntity.TYPE_SNELL to SnellSettingsActivity::class.java,
        )
        val nonStandardLinkTypes = setOf(
            ProxyEntity.TYPE_SSH,
            ProxyEntity.TYPE_WG,
            ProxyEntity.TYPE_AWG,
            ProxyEntity.TYPE_TAILSCALE,
            ProxyEntity.TYPE_SHADOWTLS,
            ProxyEntity.TYPE_CONFIG,
        )
        val dedicatedStandardLinkTypes = setOf(
            ProxyEntity.TYPE_SOCKS,
            ProxyEntity.TYPE_HTTP,
            ProxyEntity.TYPE_SS,
            ProxyEntity.TYPE_SSR,
            ProxyEntity.TYPE_VMESS,
            ProxyEntity.TYPE_TROJAN,
            ProxyEntity.TYPE_NAIVE,
            ProxyEntity.TYPE_HYSTERIA,
            ProxyEntity.TYPE_TUIC,
            ProxyEntity.TYPE_JUICITY,
            ProxyEntity.TYPE_ANYTLS,
            ProxyEntity.TYPE_SNELL,
        )

        assertEquals(20, allBeans.size)
        assertEquals(allBeans.map { it.second }.toSet(), settingsActivities.keys)
        for ((_, type) in allBeans) {
            val descriptor = ProtocolRegistry.forType(type)!!
            assertEquals(settingsActivities.getValue(type), descriptor.settingsActivityClass)
            assertEquals(type !in nonStandardLinkTypes, descriptor.hasStandardLink)
            assertEquals(type in dedicatedStandardLinkTypes, descriptor.toStandardLink != null)
        }
    }
}
