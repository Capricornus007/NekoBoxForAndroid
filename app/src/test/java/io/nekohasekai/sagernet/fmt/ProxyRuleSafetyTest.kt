package io.nekohasekai.sagernet.fmt

import android.os.Looper
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.utils.PackageCache
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProxyRuleSafetyTest {
    private lateinit var main: ProxyEntity
    private lateinit var extra: ProxyEntity
    private val apps = setOf("test.bank")
    private val originalLogSink = Logs.sink
    private val diagnostics = mutableListOf<String>()

    @Before
    fun setUp() {
        Logs.sink = { diagnostics += it }
        ShadowToast.reset()
        ConfigBuilderTestEnv.reset()
        DataStore.serviceMode = Key.MODE_VPN
        PackageCache.packageMap = mapOf("test.bank" to 10001, "test.browser" to 10002)
        ConfigBuilderTestEnv.io {
            val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup())
            fun profile(name: String) = save(
                ProxyEntity(groupId = groupId).putBean(
                    SOCKSBean().applyDefaultValues().apply {
                        this.name = name
                        serverAddress = "192.0.2.10"
                        serverPort = 1080
                    },
                ),
            )
            main = profile("main")
            extra = profile("residential")
        }
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    @Test
    fun missingTarget_blocksOnlyMatchingTrafficAndDnsWithoutDnsOptIn() {
        rules(
            RuleEntity(packages = apps, outbound = Long.MAX_VALUE),
            RuleEntity(packages = setOf("test.browser"), outbound = 0),
        )
        val config = build()
        val routes = appRoutes(config)
        assertEquals(2, routes.size)
        assertEquals("reject", routes[0].getString("action"))
        assertFalse(routes[0].has("outbound"))
        assertEquals(10001, routes[0].getJSONArray("user_id").getInt(0))
        assertEquals("main", routes[1].getString("outbound"))
        val dns = appDns(config).single()
        assertEquals("REFUSED", dns.getString("rcode"))
        assertFalse(dns.has("server"))
        assertEquals("main", config.getJSONObject("route").getString("final"))
    }

    @Test
    fun brokenAndUnsupportedTargets_becomeBlocks() {
        val badBeans = listOf(
            ChainBean().applyDefaultValues(),
            ChainBean().applyDefaultValues().apply { proxies = listOf(Long.MAX_VALUE) },
            ArchivedBean(9001, byteArrayOf()),
            ConfigBean().applyDefaultValues().apply {
                type = 1
                config = "{"
            },
        )
        for (bean in badBeans) {
            ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.updateProxy(extra.putBean(bean)) }
            rules(RuleEntity(packages = apps, outbound = extra.id))
            val config = build()
            assertEquals("reject", appRoutes(config).single().getString("action"))
            assertEquals("REFUSED", appDns(config).single().getString("rcode"))
        }
    }

    @Test
    fun malformedOutboundFields_areBlockedAndSelectedProfileErrorsAreReadable() {
        for (json in listOf(
            """{"server":[]}""",
            """{"type":true}""",
            """{"type":"not-a-proxy"}""",
            """{"type":"dns"}""",
            """{"type":"tailscale"}""",
            """{"tag":{}}""",
            """{"server_port":"443"}""",
            """{"server_port":443.5}""",
            """{"server_port":0}""",
        )) {
            ConfigBuilderTestEnv.io {
                extra.requireBean().customOutboundJson = json
                SagerDatabase.proxyDao.updateProxy(extra)
            }
            rules(RuleEntity(packages = apps, outbound = extra.id))
            assertEquals("reject", appRoutes(build()).single().getString("action"))
            main.requireBean().customOutboundJson = json
            val error = assertThrows(IllegalArgumentException::class.java) { build() }
            assertEquals(
                io.nekohasekai.sagernet.ktx.app.getString(io.nekohasekai.sagernet.R.string.route_proxy_invalid, main.displayName()),
                error.message,
            )
            main.requireBean().customOutboundJson = ""
        }
    }

    @Test
    fun customOutboundTypes_followTheBundledRegistry() {
        val registry = File("../libcore/box_include.go").readText()
            .substringAfter("func nekoboxAndroidOutboundRegistry()")
            .substringBefore("func nekoboxAndroidEndpointRegistry()")
        val types = Regex("(\\w+)\\.RegisterOutbound\\(registry\\)").findAll(registry)
            .map { it.groupValues[1] }.filterNot { it == "protocolDns" }
            .map { if (it == "snellprotocol") "snell" else it }.toSet() + setOf("selector", "urltest")
        assertTrue(types.containsAll(listOf("socks", "shadowsocksr", "juicity", "amneziawg")))
        for (type in types) {
            ConfigBuilderTestEnv.io {
                SagerDatabase.proxyDao.updateProxy(
                    extra.putBean(
                        ConfigBean().applyDefaultValues().apply {
                            this.type = 1
                            name = "custom-outbound"
                            config = JSONObject().put("type", type).toString()
                        },
                    ),
                )
            }
            rules(RuleEntity(packages = apps, outbound = extra.id))
            assertEquals(type, "custom-outbound", appRoutes(build()).single().getString("outbound"))
        }
    }

    @Test
    fun failedTargetLogsCauseAndLocation_withoutConfigurationDetails() {
        ConfigBuilderTestEnv.io {
            SagerDatabase.proxyDao.updateProxy(
                extra.putBean(
                    ConfigBean().applyDefaultValues().apply {
                        type = 1
                        name = "private-profile-name"
                        config = """{"server":"private-proxy.example","password":"secret-fixture-value","type": """
                    },
                ),
            )
        }
        rules(RuleEntity(packages = apps, outbound = extra.id))
        assertEquals("reject", appRoutes(build()).single().getString("action"))
        val diagnostic = diagnostics.single()
        assertTrue(diagnostic.contains("Proxy ${extra.id}"))
        assertTrue(diagnostic.contains("JsonSyntaxException"))
        assertTrue(diagnostic.contains(" at "))
        assertFalse(diagnostic.contains("private-profile-name"))
        assertFalse(diagnostic.contains("private-proxy.example"))
        assertFalse(diagnostic.contains("secret-fixture-value"))
    }

    @Test
    fun delimiterOnlyRulesets_areRejectedAndBlockAppDns() {
        for (value in listOf(",", " , , ", ",\n,\n")) {
            val rule = RuleEntity(packages = apps, outbound = extra.id, dnsThroughOutbound = true, ruleset = value)
            assertThrows(IllegalArgumentException::class.java) { rule.validateDnsRouting() }
            assertThrows(IllegalArgumentException::class.java) { rule.copy(packages = emptySet()).validateDnsRouting() }
            rules(rule)
            val config = build()
            assertEquals("reject", appRoutes(config).single().getString("action"))
            assertEquals("REFUSED", appDns(config).single().getString("rcode"))
        }
        RuleEntity(packages = apps, outbound = extra.id, dnsThroughOutbound = true).validateDnsRouting()
        RuleEntity(outbound = extra.id, dnsThroughOutbound = true, ruleset = "rssite:https://rules.example/domains.srs").validateDnsRouting()
    }

    @Test
    fun repairedTarget_resumesItsRouteWithoutChangingTheSavedRule() {
        val original = extra.requireBean().clone()
        ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.updateProxy(extra.putBean(ChainBean().applyDefaultValues())) }
        rules(RuleEntity(packages = apps, outbound = extra.id, dnsThroughOutbound = true))
        assertEquals("reject", appRoutes(build()).single().getString("action"))
        ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.updateProxy(extra.putBean(original)) }
        assertEquals("residential", appRoutes(build()).single().getString("outbound"))
        assertTrue(appDns(build()).single().getString("server").startsWith("dns-rule-"))
    }

    @Test
    fun missingGroupHop_doesNotSilentlyShortenTheProxyPath() {
        ConfigBuilderTestEnv.io {
            val groupId = SagerDatabase.groupDao.createGroup(ProxyGroup(frontProxy = Long.MAX_VALUE))
            extra.groupId = groupId
            SagerDatabase.proxyDao.updateProxy(extra)
        }
        rules(RuleEntity(packages = apps, outbound = extra.id))
        assertEquals("reject", appRoutes(build()).single().getString("action"))
    }

    @Test
    fun failedGuard_preservesCustomCriteriaButOverridesCustomAction() {
        rules(
            RuleEntity(
                packages = apps,
                outbound = Long.MAX_VALUE,
                config = """{"domain_suffix":["bank.example"],"action":"route","outbound":"direct"}""",
            ),
        )
        val rule = appRoutes(build()).single()
        assertEquals("bank.example", rule.getJSONArray("domain_suffix").getString(0))
        assertEquals("reject", rule.getString("action"))
        assertFalse(rule.has("outbound"))
    }

    @Test
    fun unresolvedApps_neverBroadenARouteOrDnsRule() {
        rules(RuleEntity(name = "Missing app", packages = setOf("missing.app"), domains = "bank.example", outbound = Long.MAX_VALUE))
        val config = build()
        assertTrue(appRoutes(config).isEmpty())
        assertFalse(config.getJSONObject("route").getJSONArray("rules").toString().contains("bank.example"))
        assertFalse(config.getJSONObject("dns").getJSONArray("rules").toString().contains("bank.example"))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(app.getString(R.string.route_apps_unavailable, "Missing app"), ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun dnsOptIn_usesTheRuleProxyWithGlobalDnsRoutingOffAndFakeDnsOn() {
        DataStore.enableFakeDns = true
        rules(RuleEntity(packages = apps, outbound = extra.id, dnsThroughOutbound = true))
        val config = build()
        val dns = appDns(config).single()
        val server = config.getJSONObject("dns").getJSONArray("servers").objects()
            .single { it.getString("tag") == dns.getString("server") }
        assertEquals("residential", appRoutes(config).single().getString("outbound"))
        assertEquals("residential", server.getString("detour"))
        assertEquals("resolver.example", server.getString("server"))
        assertFalse(dns.has("query_type"))
        assertTrue(config.getJSONObject("dns").getBoolean("reverse_mapping"))
    }

    @Test
    fun dnsOptOut_doesNotChangeExistingDnsEvenWithSavedOverride() {
        rules(RuleEntity(packages = apps, outbound = extra.id, dnsServer = "local"))
        val config = build()
        assertTrue(appDns(config).isEmpty())
        assertFalse(config.getJSONObject("dns").getJSONArray("servers").toString().contains("dns-rule-"))
        assertEquals("residential", appRoutes(config).single().getString("outbound"))
    }

    @Test
    fun customResolver_isScopedToTheRuleAndSurvivesPersistence() {
        val custom = "https://203.0.113.53/custom-query"
        rules(RuleEntity(packages = apps, outbound = extra.id, dnsThroughOutbound = true, dnsServer = custom))
        val saved = ConfigBuilderTestEnv.io { SagerDatabase.rulesDao.allRules().single() }
        assertTrue(saved.dnsThroughOutbound)
        assertEquals(custom, saved.dnsServer)
        val config = build()
        val server = config.getJSONObject("dns").getJSONArray("servers").objects().single { it.getString("tag").startsWith("dns-rule-") }
        assertEquals("203.0.113.53", server.getString("server"))
        assertEquals("/custom-query", server.getString("path"))
        assertEquals("residential", server.getString("detour"))
        assertFalse(server.has("domain_resolver"))
        assertEquals("dns-remote", config.getJSONObject("dns").getString("final"))
    }

    @Test
    fun invalidOrLocalResolver_blocksInsteadOfFallingBack() {
        for (resolver in listOf("local", "https://user:pass@resolver.example/", "udp://resolver.example/path", "1.1.1.1\n9.9.9.9")) {
            val rule = RuleEntity(packages = apps, outbound = extra.id, dnsThroughOutbound = true, dnsServer = resolver)
            assertThrows(IllegalArgumentException::class.java) { rule.validateDnsRouting() }
            rules(rule)
            val config = build()
            assertEquals("reject", appRoutes(config).single().getString("action"))
            assertEquals("REFUSED", appDns(config).single().getString("rcode"))
            assertFalse(config.getJSONObject("dns").getJSONArray("servers").toString().contains("dns-rule-"))
        }
    }

    @Test
    fun unsupportedDnsCriteria_areRejectedByTheEditorValidator() {
        for (change in listOf<RuleEntity.() -> Unit>(
            { ip = "192.0.2.1" }, { port = "443" }, { network = "tcp" },
            { protocol = "tls" }, { sourcePort = "1000" }, { source = "192.0.2.1" },
            { config = "{}" }, { ruleset = "rsip:https://rules.example/ip.srs" },
            { outbound = -1 }, { domains = "full:" },
        )) {
            val rule = RuleEntity(packages = apps, outbound = extra.id, dnsThroughOutbound = true).apply(change)
            assertThrows(IllegalArgumentException::class.java) { rule.validateDnsRouting() }
        }
    }

    @Test
    fun explicitProfile_staysPinnedInsideASelector() {
        ConfigBuilderTestEnv.io {
            SagerDatabase.groupDao.updateGroup(SagerDatabase.groupDao.getById(main.groupId)!!.apply { isSelector = true })
        }
        rules(RuleEntity(packages = apps, outbound = main.id, dnsThroughOutbound = true))
        val config = build()
        assertEquals("main", appRoutes(config).single().getString("outbound"))
        assertEquals("proxy", config.getJSONObject("route").getString("final"))
        val server = config.getJSONObject("dns").getJSONArray("servers").objects().single { it.getString("tag").startsWith("dns-rule-") }
        assertEquals("main", server.getString("detour"))
    }

    @Test
    fun globalMode_continuesToIgnoreUserRules() {
        DataStore.globalMode = true
        rules(RuleEntity(packages = apps, outbound = Long.MAX_VALUE, dnsThroughOutbound = true))
        assertTrue(appRoutes(build()).isEmpty())
        assertTrue(appDns(build()).isEmpty())
    }

    private fun save(profile: ProxyEntity) = profile.also { it.id = SagerDatabase.proxyDao.addProxy(it) }
    private fun rules(vararg rules: RuleEntity) = ConfigBuilderTestEnv.io {
        SagerDatabase.rulesDao.reset()
        rules.forEachIndexed { index, rule ->
            rule.enabled = true
            rule.userOrder = index.toLong()
            SagerDatabase.rulesDao.createRule(rule)
        }
    }
    private fun build() = ConfigBuilderTestEnv.io { JSONObject(buildConfig(main).config) }
    private fun JSONArray.objects() = (0 until length()).map(::getJSONObject)
    private fun appRoutes(config: JSONObject) = config.getJSONObject("route").getJSONArray("rules").objects().filter { it.has("user_id") }
    private fun appDns(config: JSONObject) = config.getJSONObject("dns").getJSONArray("rules").objects().filter { it.has("user_id") }
}
