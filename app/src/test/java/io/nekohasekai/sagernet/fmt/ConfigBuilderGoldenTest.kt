package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.masque.MasqueBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ConfigBuilderGoldenTest {

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
    }

    @Test
    fun socksForTest_preservesEndpointAndHasNoInbound() {
        val profile = addProfile(
            addGroup(),
            SOCKSBean().apply {
                serverAddress = "192.0.2.10"
                serverPort = 1080
                username = "reader"
                password = "alpha"
                protocol = 2
                name = "golden-socks"
                initializeDefaultValues()
            },
        )

        val result = build(profile, forTest = true)
        val root = JSONObject(result.config)
        val outbound = outbound(root, "socks")

        assertEquals("192.0.2.10", outbound.getString("server"))
        assertEquals(1080, outbound.getInt("server_port"))
        assertEquals(0, root.getJSONArray("inbounds").length())
        assertResultMaps(result, profile)
    }

    @Test
    fun vmessForTest_preservesUuidAndSecurity() {
        val profile = addProfile(
            addGroup(),
            VMessBean().apply {
                serverAddress = "192.0.2.11"
                serverPort = 443
                uuid = "b831381d-6324-4d53-ad4f-8cda48b30811"
                alterId = 0
                encryption = "auto"
                name = "golden-vmess"
                initializeDefaultValues()
            },
        )

        val result = build(profile, forTest = true)
        val outbound = outbound(JSONObject(result.config), "vmess")

        assertEquals("b831381d-6324-4d53-ad4f-8cda48b30811", outbound.getString("uuid"))
        assertEquals("auto", outbound.getString("security"))
        assertResultMaps(result, profile)
    }

    @Test
    fun tlsFragment_detoursTlsOutbound() {
        val previousTlsFragment = DataStore.enableTLSFragment
        try {
            DataStore.enableTLSFragment = true
            val profile = addProfile(
                addGroup(),
                VMessBean().apply {
                    serverAddress = "192.0.2.13"
                    serverPort = 443
                    uuid = "d342d11e-d424-4583-b36e-524ab1f0afa4"
                    alterId = 0
                    encryption = "auto"
                    security = "tls"
                    sni = "node.example"
                    name = "fragment-vmess"
                    initializeDefaultValues()
                },
            )

            val outbound = outbound(JSONObject(build(profile).config), "vmess")

            assertTrue(outbound.getJSONObject("tls").getBoolean("enabled"))
            assertEquals(TAG_FRAGMENT, outbound.getString("detour"))
        } finally {
            DataStore.enableTLSFragment = previousTlsFragment
        }
    }

    @Test
    fun shadowsocksForTest_preservesMethodAndPassword() {
        val profile = addProfile(
            addGroup(),
            ShadowsocksBean().apply {
                serverAddress = "192.0.2.12"
                serverPort = 8388
                method = "aes-256-gcm"
                password = "bravo"
                name = "golden-ss"
                initializeDefaultValues()
            },
        )

        val result = build(profile, forTest = true)
        val outbound = outbound(JSONObject(result.config), "shadowsocks")

        assertEquals("aes-256-gcm", outbound.getString("method"))
        assertEquals("bravo", outbound.getString("password"))
        assertResultMaps(result, profile)
    }

    @Test
    fun chainForTest_preservesBothHopsAndDetour() {
        val group = addGroup()
        val first = addSocks(group, "192.0.2.21", 1081, "chain-first")
        val second = addSocks(group, "192.0.2.22", 1082, "chain-second")
        val chain = addProfile(
            group,
            ChainBean().apply {
                name = "golden-chain"
                proxies = listOf(first.id, second.id)
                initializeDefaultValues()
            },
        )

        val result = build(chain, forTest = true)
        val socks = objects(JSONObject(result.config).getJSONArray("outbounds"))
            .filter { it.optString("type") == "socks" }

        assertEquals(2, socks.size)
        assertEquals(
            setOf("192.0.2.21" to 1081, "192.0.2.22" to 1082),
            socks.map { it.getString("server") to it.getInt("server_port") }.toSet(),
        )
        val tags = socks.map { it.getString("tag") }.toSet()
        val detours = socks.mapNotNull { outbound ->
            outbound.optString("detour").takeIf { it.isNotEmpty() }?.let { detour ->
                outbound.getString("tag") to detour
            }
        }
        assertEquals(1, detours.size)
        assertTrue(detours.single().first != detours.single().second)
        assertTrue(detours.single().second in tags)
        assertResultMaps(result, chain)
    }

    @Test
    fun hysteria2ForTest_usesNativeOutbound() {
        val profile = addProfile(
            addGroup(),
            HysteriaBean().apply {
                protocolVersion = 2
                serverAddress = "192.0.2.30"
                serverPorts = "8443"
                authPayload = "charlie"
                authPayloadType = HysteriaBean.TYPE_STRING
                name = "golden-hy2"
                initializeDefaultValues()
            },
        )

        val result = build(profile, forTest = true)
        val outbound = outbound(JSONObject(result.config), "hysteria2")

        assertEquals("192.0.2.30", outbound.getString("server"))
        assertEquals(8443, outbound.getInt("server_port"))
        assertEquals("charlie", outbound.getString("password"))
        assertFalse(outbound.getJSONObject("tls").has("ech"))
        assertResultMaps(result, profile)
    }

    @Test
    fun hysteria2Ech_emitsExplicitStaticConfig() {
        val profile = addProfile(
            addGroup(),
            HysteriaBean().apply {
                protocolVersion = 2
                serverAddress = "192.0.2.31"
                serverPorts = "443"
                authPayload = "delta"
                sni = "real.example.com"
                enableECH = true
                echConfig = "AEb+DQBCAAAgACAHo3y8FCCTyLdV3BsQ6Gy0JjdK0WqoU+0L38CyuG0cfAAMAAEAAQABAAIAAQADAAtleGFtcGxlLmNvbQAA"
                name = "golden-hy2-ech"
                initializeDefaultValues()
            },
        )

        val outbound = outbound(JSONObject(build(profile, forTest = true).config), "hysteria2")
        val ech = outbound.getJSONObject("tls").getJSONObject("ech")

        assertTrue(ech.getBoolean("enabled"))
        assertEquals(
            listOf(
                "-----BEGIN ECH CONFIGS-----",
                "AEb+DQBCAAAgACAHo3y8FCCTyLdV3BsQ6Gy0JjdK0WqoU+0L38CyuG0cfAAMAAEAAQABAAIAAQADAAtleGFtcGxlLmNvbQAA",
                "-----END ECH CONFIGS-----",
            ),
            strings(ech.getJSONArray("config")),
        )
    }

    @Test
    fun selectorGroup_containsEveryMember() {
        val group = addGroup(isSelector = true)
        val first = addSocks(group, "192.0.2.41", 1081, "selector-first")
        val second = addSocks(group, "192.0.2.42", 1082, "selector-second")

        val result = build(first)
        val selector = outbound(JSONObject(result.config), "selector")

        assertEquals(
            setOf(result.profileTagMap.getValue(first.id), result.profileTagMap.getValue(second.id)),
            strings(selector.getJSONArray("outbounds")).toSet(),
        )
        assertEquals(group, result.selectorGroupId)
        assertResultMaps(result, first)
    }

    @Test
    fun selectorGroup_appliesSharedFrontAndLandingToEveryMemberChain() {
        val supportGroup = addGroup()
        val front = addSocks(supportGroup, "192.0.2.43", 1083, "selector-front")
        val landing = addSocks(supportGroup, "192.0.2.44", 1084, "selector-landing")
        val selectorGroup = addGroup(isSelector = true)
        val first = addSocks(selectorGroup, "192.0.2.45", 1085, "selector-member-one")
        val second = addSocks(selectorGroup, "192.0.2.46", 1086, "selector-member-two")
        ConfigBuilderTestEnv.io {
            SagerDatabase.groupDao.getById(selectorGroup)!!.also {
                it.frontProxy = front.id
                it.landingProxy = landing.id
                SagerDatabase.groupDao.updateGroup(it)
            }
        }

        val result = build(first)
        val socks = objects(JSONObject(result.config).getJSONArray("outbounds"))
            .filter { it.optString("type") == "socks" }
        val frontOutbound = socks.single { it.getString("server") == "192.0.2.43" }
        val memberOutbounds = socks.filter {
            it.getString("server") in setOf("192.0.2.45", "192.0.2.46")
        }
        val landingOutbounds = socks.filter { it.getString("server") == "192.0.2.44" }

        assertEquals(2, memberOutbounds.size)
        assertTrue(memberOutbounds.all { it.getString("detour") == frontOutbound.getString("tag") })
        assertEquals(2, landingOutbounds.size)
        assertEquals(
            memberOutbounds.map { it.getString("tag") }.toSet(),
            landingOutbounds.map { it.getString("detour") }.toSet(),
        )
        assertTrue(result.trafficMap.values.all { chain -> front in chain && landing in chain })
        assertTrue(result.profileTagMap.keys.containsAll(listOf(first.id, second.id)))
    }

    @Test
    fun forExport_omitsClashApiSecret() {
        assertEquals("export-secret", DataStore.clashApiSecret)
        val profile = addSocks(addGroup(), "192.0.2.50", 1080, "export-socks")

        val root = JSONObject(build(profile, forExport = true).config)
        val clashApi = root.getJSONObject("experimental").getJSONObject("clash_api")

        assertEquals("127.0.0.1:9090", clashApi.getString("external_controller"))
        assertFalse(clashApi.has("secret"))
    }

    @Test
    fun customJsonOverlays_mergeIntoOutboundAndRoot() {
        val profile = addProfile(
            addGroup(),
            SOCKSBean().apply {
                serverAddress = "192.0.2.51"
                serverPort = 1080
                protocol = 2
                name = "custom-json"
                customOutboundJson = """{"marker":{"inner":1}}"""
                customConfigJson = """{"route":{"final":"custom-final"},"top_level_marker":true}"""
                initializeDefaultValues()
            },
        )

        val root = JSONObject(build(profile).config)
        val outbound = outbound(root, "socks")

        assertEquals(1, outbound.getJSONObject("marker").getInt("inner"))
        assertEquals("custom-final", root.getJSONObject("route").getString("final"))
        assertTrue(root.getBoolean("top_level_marker"))
    }

    @Test
    fun blankDnsHosts_omitsHostsServer() {
        val profile = addSocks(addGroup(), "192.0.2.60", 1080, "blank-hosts")

        val servers = objects(JSONObject(build(profile).config).getJSONObject("dns").getJSONArray("servers"))

        assertFalse(servers.any { it.optString("tag") == TAG_DNS_HOSTS })
    }

    @Test
    fun fakeDns_usesSingBox114ServerFormat() {
        DataStore.enableFakeDns = true
        val profile = addSocks(addGroup(), "192.0.2.61", 1080, "fake-dns")

        val dns = JSONObject(build(profile).config).getJSONObject("dns")
        val fakeIPServer = objects(dns.getJSONArray("servers"))
            .single { it.optString("tag") == "dns-fake" }

        assertFalse(dns.has("fakeip"))
        assertEquals("fakeip", fakeIPServer.getString("type"))
        assertEquals("198.18.0.0/15", fakeIPServer.getString("inet4_range"))
        assertEquals("fc00::/18", fakeIPServer.getString("inet6_range"))
        assertFalse(fakeIPServer.has("address"))
        assertFalse(fakeIPServer.has("strategy"))
    }

    @Test
    fun populatedDnsHosts_addsPredefinedServerAndScopedRule() {
        DataStore.dnsHosts = "node.example 192.0.2.70 2001:db8::70"
        val profile = addSocks(addGroup(), "192.0.2.71", 1080, "mapped-hosts")

        val dns = JSONObject(build(profile).config).getJSONObject("dns")
        val hostsServer = objects(dns.getJSONArray("servers"))
            .single { it.optString("tag") == TAG_DNS_HOSTS }
        val predefined = hostsServer.getJSONObject("predefined").getJSONArray("node.example")
        val rules = objects(dns.getJSONArray("rules"))
        val hostsRule = rules.single { it.optString("server") == TAG_DNS_HOSTS }

        assertEquals("hosts", hostsServer.getString("type"))
        assertEquals(listOf("192.0.2.70", "2001:db8::70"), strings(predefined))
        assertEquals(listOf("A", "AAAA"), strings(hostsRule.getJSONArray("query_type")))
        assertTrue(strings(hostsRule.getJSONArray("domain")).contains("node.example"))
        val directRuleIndex = rules.indexOfFirst { it.optString("server") == "dns-direct" }
        assertTrue(directRuleIndex >= 0)
        assertTrue(rules.indexOf(hostsRule) > directRuleIndex)
    }

    @Test
    fun masqueForTest_emitsSingBoxJsonTags() {
        val profile = addProfile(
            addGroup(),
            MasqueBean().apply {
                initializeDefaultValues()
                name = "golden-masque"
                transport = "h2"
                useIPv6 = true
                system = true
                interfaceName = "warp0"
                allowedIPs = "0.0.0.0/0, ::/0"
                profileId = "cf-id"
                profileAuthToken = "cf-token"
                profilePrivateKey = "cf-pk"
                profileRecreate = false
                configPrivateKey = "cfgpk"
                configEndpointV4 = "162.159.193.10"
                configEndpointV6 = "2603:2e0::1"
                configEndpointH2V4 = "188.114.96.0"
                configEndpointH2V6 = "2a06:98c0:3000::"
                configEndpointPubKey = "PUBKEYPEM"
                configLicense = "lic"
                configId = "dev"
                configAccessToken = "at"
                configIPv4 = "10.0.0.2"
                configIPv6 = "fd00::2"
                udpTimeout = "3m0s"
                udpKeepalivePeriod = "25s"
                udpInitialPacketSize = 1252
                disablePathMTUDiscovery = true
                h3FallbackTimeout = "7s"
                mtu = 1420
                reconnectDelay = "8s"
                tlsSNI = "custom.example"
                tlsInsecure = true
                tlsCipherSuites = "TLS_AES_128_GCM_SHA256,TLS_CHACHA20_POLY1305_SHA256"
                tlsCurvePreferences = "X25519,P256"
                tlsFragment = true
                tlsFragmentFallbackDelay = "2s"
                tlsRecordFragment = true
                tlsKernelTx = true
                tlsKernelRx = true
            },
        )

        val outbound = outbound(JSONObject(build(profile, forTest = true).config), "masque")

        // Top-level outbound keys must match option/masque.go json tags.
        for (key in listOf(
            "system", "name", "allowed_ips", "use_http2", "transport", "use_ipv6",
            "profile", "config", "udp_timeout", "udp_keepalive_period",
            "udp_initial_packet_size", "disable_path_mtu_discovery", "h3_fallback_timeout",
            "mtu", "reconnect_delay", "tls",
        )) {
            assertTrue("missing outbound key: $key", outbound.has(key))
        }
        assertEquals("h2", outbound.getString("transport"))
        assertTrue(outbound.getBoolean("use_http2"))
        assertTrue(outbound.getBoolean("use_ipv6"))
        assertTrue(outbound.getBoolean("system"))
        assertEquals("warp0", outbound.getString("name"))
        assertEquals(1420, outbound.getInt("mtu"))
        assertEquals(1252, outbound.getInt("udp_initial_packet_size"))
        assertEquals(
            setOf("0.0.0.0/0", "::/0"),
            (0 until outbound.getJSONArray("allowed_ips").length())
                .map { outbound.getJSONArray("allowed_ips").getString(it) }.toSet(),
        )

        val cloudflareProfile = outbound.getJSONObject("profile")
        for (key in listOf("id", "auth_token", "private_key", "recreate")) {
            assertTrue("missing profile key: $key", cloudflareProfile.has(key))
        }
        assertEquals("cf-id", cloudflareProfile.getString("id"))

        val config = outbound.getJSONObject("config")
        for (key in listOf(
            "private_key", "endpoint_v4", "endpoint_v6", "endpoint_h2_v4", "endpoint_h2_v6",
            "endpoint_pub_key", "license", "id", "access_token", "ipv4", "ipv6",
        )) {
            assertTrue("missing config key: $key", config.has(key))
        }
        assertEquals("162.159.193.10", config.getString("endpoint_v4"))

        val tls = outbound.getJSONObject("tls")
        for (key in listOf(
            "insecure", "cipher_suites", "curve_preferences", "fragment",
            "fragment_fallback_delay", "record_fragment", "kernel_tx", "kernel_rx", "sni",
        )) {
            assertTrue("missing tls key: $key", tls.has(key))
        }
        assertEquals("custom.example", tls.getString("sni"))
        assertTrue(tls.getBoolean("insecure"))

        assertResultMaps(result = build(profile, forTest = true), profile = profile)
    }

    private fun addGroup(isSelector: Boolean = false) = ConfigBuilderTestEnv.io {
        SagerDatabase.groupDao.createGroup(ProxyGroup(ungrouped = true, isSelector = isSelector))
    }

    private fun addSocks(groupId: Long, address: String, port: Int, name: String) = addProfile(
        groupId,
        SOCKSBean().apply {
            serverAddress = address
            serverPort = port
            protocol = 2
            this.name = name
            initializeDefaultValues()
        },
    )

    private fun addProfile(groupId: Long, bean: AbstractBean) = ConfigBuilderTestEnv.io {
        ProxyEntity(groupId = groupId).apply {
            putBean(bean)
            id = SagerDatabase.proxyDao.addProxy(this)
        }
    }

    private fun build(profile: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false) =
        ConfigBuilderTestEnv.io { buildConfig(profile, forTest = forTest, forExport = forExport) }

    private fun assertResultMaps(result: ConfigBuildResult, profile: ProxyEntity) {
        assertTrue(result.profileTagMap.containsKey(profile.id))
        assertTrue(result.trafficMap.values.flatten().any { it.id == profile.id })
    }

    private fun outbound(root: JSONObject, type: String) =
        objects(root.getJSONArray("outbounds")).single { it.optString("type") == type }

    private fun objects(array: JSONArray) = (0 until array.length()).map(array::getJSONObject)

    private fun strings(array: JSONArray) = (0 until array.length()).map(array::getString)
}
