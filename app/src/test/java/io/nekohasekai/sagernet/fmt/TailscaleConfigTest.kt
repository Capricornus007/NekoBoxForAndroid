package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TailscaleConfigTest {
    private val modes = listOf("normal", "global", "export", "test")

    private fun node() = TailscaleBean().apply {
        name = "tailnet"
        exitNode = "100.64.0.1"
        controlUrl = "https://control.invalid"
    }

    private fun socks(name: String = "socks") = SOCKSBean().apply {
        this.name = name
        serverAddress = "192.0.2.1"
        serverPort = 1080
    }

    private fun add(bean: AbstractBean, groupId: Long = 0, order: Long = 0): ProxyEntity = ProxyEntity(groupId = groupId, userOrder = order).putBean(
        bean.apply {
            initializeDefaultValues()
        },
    )
        .also { it.id = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.addProxy(it) } }

    private fun chain(vararg hops: ProxyEntity) = ChainBean().apply { proxies = hops.map { it.id } }

    private fun JSONObject.objects(key: String): List<JSONObject> = optJSONArray(key)?.let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    }.orEmpty()

    private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

    // The sidecar is independent of route.final and is checked against the core's effective
    // default after PreStart, not merely against the serialized configuration.
    private fun fixture(name: String, result: ConfigBuildResult, expectedTag: String): JSONObject {
        val config = JSONObject(result.config)
        val outbounds = config.objects("outbounds") + config.objects("endpoints")
        val tags = outbounds.map { it.getString("tag") }
        assertEquals("duplicate outbound tags", tags.size, tags.toSet().size)
        assertTrue("missing expected default $expectedTag in $tags", expectedTag in tags)
        assertEquals(expectedTag, config.getJSONObject("route").getString("final"))
        fun checkDetours(value: Any) {
            when (value) {
                is JSONObject -> value.keys().forEach { key ->
                    if (key == "detour") {
                        assertTrue("unresolved detour ${value.getString(key)} in $tags", value.getString(key) in tags)
                    } else {
                        checkDetours(value.get(key))
                    }
                }

                is JSONArray -> (0 until value.length()).forEach { checkDetours(value.get(it)) }
            }
        }
        checkDetours(config)
        config.getJSONObject("route").objects("rules").filter { it.has("outbound") }.forEach {
            assertTrue("unresolved route outbound $it", it.getString("outbound") in tags)
        }
        outbounds.filter { it.has("outbounds") }.forEach {
            assertTrue(tags.containsAll(it.getJSONArray("outbounds").strings()))
        }
        val directory = File("build/generated-core-configs/tailscale-routing").apply { mkdirs() }
        directory.resolve("$name.json").writeText(result.config)
        directory.resolve("$name.json.expected-tag").writeText("$expectedTag\n")
        return config
    }

    private fun build(profile: ProxyEntity, mode: String): ConfigBuildResult {
        DataStore.globalMode = mode == "global"
        return ConfigBuilderTestEnv.io { buildConfig(profile, forTest = mode == "test", forExport = mode == "export") }
    }

    private fun assertMagicDns(config: JSONObject, forTest: Boolean, extraServerTags: Set<String> = emptySet()) {
        val dns = config.getJSONObject("dns")
        val servers = dns.objects("servers")
        val preferred = dns.objects("rules").filter { it.has("preferred_by") }
        val endpoints = config.objects("endpoints")
        assertEquals(endpoints.size, servers.count { it.optString("type") == "tailscale" })
        assertEquals(endpoints.size, preferred.size)
        endpoints.forEach { endpoint ->
            val server = servers.single { it.optString("endpoint") == endpoint.getString("tag") }
            assertTrue(server.getBoolean("accept_search_domain"))
            val rule = preferred.single { it.getString("server") == server.getString("tag") }
            assertEquals(listOf(server.getString("tag")), rule.getJSONArray("preferred_by").strings())
        }
        assertEquals(if (forTest) "dns-direct" else "dns-remote", dns.getString("final"))
        if (forTest) {
            assertTrue(config.objects("inbounds").isEmpty())
            val tailscaleServers = servers.filter { it.optString("type") == "tailscale" }.map { it.getString("tag") }
            assertEquals(
                // dns-block 是本倉每份配置都建的封鎖伺服器（見 ProtocolRegistryDispatchTest
                // 對「本倉用 dns-block、上游 fork 用 action: predefined」的記錄）。
                setOf("dns-local", "dns-direct", "dns-block") + tailscaleServers + extraServerTags,
                servers.map { it.getString("tag") }.toSet(),
            )
            assertEquals(preferred, dns.objects("rules"))
            // 本倉把 dns-local／dns-direct 一律釘在 direct 出口上（ConfigBuilder 的
            // detour = TAG_DIRECT），探測配置裡 direct 出口恆在，fixture() 已驗過
            // 每個 detour 都解析得到；上游 fork 探測時不釘，這一條認的是我們的寫法。
            assertEquals("direct", servers.single { it.getString("tag") == "dns-direct" }.getString("detour"))
        }
    }

    @Test
    fun standaloneAndRegularDefaultsInEveryMode() {
        ConfigBuilderTestEnv.reset()
        val node = add(node())
        val socks = add(socks())
        for (mode in modes) {
            val config = fixture("standalone-$mode", build(node, mode), "tailnet")
            assertEquals(1, config.objects("endpoints").size)
            assertMagicDns(config, mode == "test")
            val regular = fixture("regular-$mode", build(socks, mode), "socks")
            assertTrue(regular.objects("endpoints").isEmpty())
            assertMagicDns(regular, mode == "test")
        }
    }

    @Test
    fun tailscaleLandingHopIsTheDefaultNotItsUnderlay() {
        ConfigBuilderTestEnv.reset()
        val node = add(node())
        val socks = add(socks())
        val chain = add(chain(socks, node))
        for (mode in modes) {
            val config = fixture("landing-chain-$mode", build(chain, mode), "tailnet")
            assertEquals("g-${socks.id}", config.objects("endpoints").single().getString("detour"))
            assertMagicDns(config, mode == "test")
        }
        val group = ProxyGroup(landingProxy = node.id)
        group.id = ConfigBuilderTestEnv.io { SagerDatabase.groupDao.createGroup(group) }
        socks.groupId = group.id
        ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.updateProxy(socks) }
        val config = fixture("group-landing", build(socks, "normal"), "tailnet")
        assertEquals("g-${socks.id}", config.objects("endpoints").single().getString("detour"))
    }

    @Test
    fun sharedEntryHopResolvesBeforeLinkingInEitherSelectorOrder() {
        for (tailscale in listOf(true, false)) {
            for (chainFirst in listOf(false, true)) {
                ConfigBuilderTestEnv.reset()
                val groupId = ConfigBuilderTestEnv.io {
                    SagerDatabase.groupDao.createGroup(
                        ProxyGroup(isSelector = true),
                    )
                }
                val entry = add(if (tailscale) node() else socks("entry"), groupId, 0)
                val exit = add(socks(), groupId, 1)
                val chain = add(chain(entry, exit), groupId, if (chainFirst) -1 else 2)
                val result = build(entry, "normal")
                val config = fixture("shared-$tailscale-$chainFirst", result, TAG_PROXY)
                val entryTag = if (chainFirst) {
                    "g-${entry.id}"
                } else if (tailscale) {
                    "tailnet"
                } else {
                    "entry"
                }
                val chainTag = if (chainFirst) "socks" else "socks-1"
                val outbounds = config.objects("outbounds") + config.objects("endpoints")
                assertEquals(entryTag, outbounds.single { it.optString("tag") == chainTag }.getString("detour"))
                assertEquals(
                    3,
                    config.objects("outbounds").single {
                        it.optString("type") == "selector"
                    }.getJSONArray("outbounds").length(),
                )
                assertEquals(entryTag, result.profileTagMap[entry.id])
                assertEquals(chainTag, result.profileTagMap[chain.id])
                assertEquals(if (tailscale) 1 else 0, config.objects("endpoints").size)
                assertMagicDns(config, false)
            }
        }
    }

    @Test
    fun ruleOutboundsDoNotReplaceTheMainDefaultAndReuseTheEntry() {
        ConfigBuilderTestEnv.reset()
        val node = add(node())
        val socks = add(socks())
        val chain = add(chain(node, socks))
        ConfigBuilderTestEnv.io {
            SagerDatabase.rulesDao.createRule(
                RuleEntity(enabled = true, domains = "full:chain.example", outbound = chain.id),
            )
        }
        for (mode in modes) {
            val config = fixture("extra-chain-$mode", build(node, mode), "tailnet")
            assertEquals(1, config.objects("endpoints").size)
            if (mode != "test") {
                assertEquals(
                    "tailnet",
                    config.objects("outbounds").single { it.optString("tag") == "socks" }.getString("detour"),
                )
            }
            assertMagicDns(config, mode == "test")
        }
        ConfigBuilderTestEnv.io {
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.createRule(
                RuleEntity(enabled = true, domains = "full:tailnet.example", outbound = node.id),
            )
        }
        val config = fixture("extra-tailnet", build(socks, "normal"), "socks")
        assertEquals(1, config.objects("endpoints").size)
        assertMagicDns(config, false)
    }

    @Test
    fun serviceDnsPrecedenceAndProbeIsolation() {
        ConfigBuilderTestEnv.reset(dnsHosts = "mapped.ts.test 192.0.2.100\nresolver.example 192.0.2.101")
        DataStore.enableDnsRouting = true
        DataStore.enableFakeDns = true
        ConfigBuilderTestEnv.io {
            SagerDatabase.rulesDao.createRule(
                RuleEntity(enabled = true, domains = "full:blocked.ts.test", outbound = -2),
            )
        }
        val node = add(node())
        val service = fixture("dns-service", build(node, "normal"), "tailnet")
        assertMagicDns(service, false)
        val rules = service.getJSONObject("dns").objects("rules")
        val bootstrap = rules.indexOfFirst { it.optString("server") == "dns-direct" }
        val hosts = rules.indexOfFirst { it.optString("server") == TAG_DNS_HOSTS }
        val block = rules.indexOfFirst { it.optString("server") == "dns-block" }
        val magic = rules.indexOfFirst { it.has("preferred_by") }
        val fake = rules.indexOfFirst { it.optString("server") == "dns-fake" }
        assertTrue(
            "DNS precedence: $rules",
            bootstrap >= 0 && bootstrap < hosts && hosts < block && block < magic && magic < fake,
        )
        val probe = fixture("dns-test", build(node, "test"), "tailnet")
        assertMagicDns(probe, true, setOf(TAG_DNS_HOSTS))
    }

    @Test
    fun brokenSelectorMembersRollBackBeforeAValidSharedChain() {
        ConfigBuilderTestEnv.reset()
        val groupId = ConfigBuilderTestEnv.io { SagerDatabase.groupDao.createGroup(ProxyGroup(isSelector = true)) }
        val node = add(node(), groupId, 0)
        val socks = add(socks(), groupId, 1)
        val broken = add(
            HysteriaBean().apply {
                serverAddress = "192.0.2.2"
                enableECH = true
            },
        )
        val brokenChain = add(chain(broken, node), groupId, -1)
        val duplicate = add(chain(node, socks, node), groupId, 2)
        val valid = add(chain(node, socks), groupId, 3)
        val result = build(node, "normal")
        val config = fixture("selector-rollback", result, TAG_PROXY)
        assertFalse(result.profileTagMap.containsKey(brokenChain.id))
        assertFalse(result.profileTagMap.containsKey(duplicate.id))
        assertTrue(result.profileTagMap.containsKey(valid.id))
        assertEquals(1, config.objects("endpoints").size)
        val nodeTag = config.objects("endpoints").single().getString("tag")
        assertEquals(
            nodeTag,
            config.objects("outbounds").single {
                it.optString("tag") == result.profileTagMap[valid.id]
            }.getString("detour"),
        )
        assertMagicDns(config, false)
    }

    @Test
    fun explicitCustomFinalOverridesTheGeneratedDefault() {
        ConfigBuilderTestEnv.reset()
        val node = add(node())
        DataStore.globalCustomConfig = """{"route":{"final":"direct"}}"""
        fixture("global-final-overlay", build(node, "normal"), TAG_DIRECT)
        node.requireBean().customConfigJson = """{"route":{"final":"bypass"}}"""
        for (mode in modes) {
            fixture("profile-final-overlay-$mode", build(node, mode), TAG_BYPASS)
        }
    }
}
