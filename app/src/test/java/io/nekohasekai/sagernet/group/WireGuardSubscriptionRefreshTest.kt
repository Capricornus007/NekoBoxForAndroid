package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.amneziawg.AmneziaWGBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardDnsMode
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Subscription refreshes of WireGuard profiles, through the real update path: the user's DNS choice
 * and overrides survive, imported DNS follows the provider, a remark-only rename keeps the profile,
 * and per-peer profiles from older imports collapse into one without orphaning a referenced peer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WireGuardSubscriptionRefreshTest {
    private lateinit var group: ProxyGroup
    private lateinit var subscription: SubscriptionBean
    private lateinit var originalLogSink: (String) -> Unit

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        originalLogSink = Logs.sink
        Logs.sink = {}
        ConfigBuilderTestEnv.io {
            subscription = SubscriptionBean().applyDefaultValues().apply { link = "https://subscription.example/wireguard" }
            group = ProxyGroup(name = "Subscription #1", type = GroupType.SUBSCRIPTION, subscription = subscription)
                .also { it.id = SagerDatabase.groupDao.createGroup(it) }
        }
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    private fun conf(dns: String, remark: String? = "office", vararg endpoints: String = arrayOf("192.0.2.1:51820")) = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=")
        appendLine("Address = 10.8.0.2/32")
        appendLine("DNS = $dns")
        endpoints.forEachIndexed { index, endpoint ->
            appendLine()
            if (remark != null && endpoints.size == 1) appendLine("# $remark")
            appendLine("[Peer]")
            appendLine("PublicKey = ${listOf("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=", "Q0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0M=")[index]}")
            appendLine("Endpoint = $endpoint")
            appendLine("AllowedIPs = 10.$index.0.0/16")
        }
    }

    private suspend fun update(text: String) = RawUpdater.updateFromContent(group, subscription, text, reconfigureUpdater = {})

    private fun stored() = SagerDatabase.proxyDao.getByGroup(group.id)

    private fun ProxyEntity.wg() = requireBean() as WireGuardBean

    private fun legacyPeers() = listOf("192.0.2.1", "192.0.2.2").mapIndexed { index, host ->
        ProxyEntity(groupId = group.id, userOrder = index + 1L).putBean(
            WireGuardBean().applyDefaultValues().apply {
                serverAddress = host
                serverPort = 51820
                localAddress = "10.8.0.2/32"
                privateKey = "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE="
                peerPublicKey = listOf("QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=", "Q0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0NDQ0M=")[index]
            },
        ).also { it.id = SagerDatabase.proxyDao.addProxy(it) }
    }

    private class Decision(private val approved: Boolean) : GroupManager.Interface {
        var asked = 0
        override suspend fun confirm(message: String): Boolean {
            asked++
            return approved
        }
        override suspend fun alert(message: String) = Unit
        override suspend fun onUpdateFailure(group: ProxyGroup, message: String) = Unit
        override suspend fun onUpdateSuccess(group: ProxyGroup, changed: Int, added: List<String>, updated: Map<String, String>, deleted: List<String>, duplicate: List<String>, byUser: Boolean) = Unit
    }

    @Test
    fun refreshKeepsTheDnsChoiceAndOverridesAndFollowsImportedDns() = runTest {
        withContext(Dispatchers.IO) {
            update(conf("10.0.0.1, corp.example"))
            val entity = stored().single()
            SagerDatabase.proxyDao.updateProxy(
                entity.putBean(
                    entity.wg().apply {
                        dnsMode = WireGuardDnsMode.DOMAINS
                        customDnsServers = "10.0.0.53"
                    },
                ),
            )

            update(conf("10.0.0.2, corp.example"))

            val after = stored().single()
            assertEquals(entity.id, after.id)
            assertEquals(WireGuardDnsMode.DOMAINS, after.wg().dnsMode)
            assertEquals("10.0.0.53", after.wg().customDnsServers)
            assertEquals("", after.wg().customDnsDomains)
            assertEquals("10.0.0.2", after.wg().importedDnsServers)
            assertEquals("corp.example", after.wg().importedDnsDomains)
        }
    }

    @Test
    fun unchangedProviderConfigLeavesAProfileWithLocalSettingsUntouched() = runTest {
        withContext(Dispatchers.IO) {
            update(conf("10.0.0.1"))
            val entity = stored().single()
            SagerDatabase.proxyDao.updateProxy(entity.putBean(entity.wg().apply { dnsMode = WireGuardDnsMode.ALL }))
            val before = stored().single().wg()

            update(conf("10.0.0.1"))

            assertEquals(before, stored().single().wg())
            assertEquals(WireGuardDnsMode.ALL, stored().single().wg().dnsMode)
        }
    }

    @Test
    fun remarkOnlyRenameKeepsTheProfileAndItsLocalSettings() = runTest {
        withContext(Dispatchers.IO) {
            update(conf("10.0.0.1", remark = "office"))
            val entity = stored().single()
            SagerDatabase.proxyDao.updateProxy(entity.putBean(entity.wg().apply { customDnsDomains = "lab.example" }))

            update(conf("10.0.0.1", remark = "office west"))

            val after = stored().single()
            assertEquals(entity.id, after.id)
            assertEquals("office west", after.displayName())
            assertEquals("lab.example", after.wg().customDnsDomains)
        }
    }

    @Test
    fun perPeerProfilesFromOlderImportsCollapseWithoutOrphaningAReferencedPeer() = runTest {
        withContext(Dispatchers.IO) {
            // The older parser stored one profile per peer, without routes.
            val old = legacyPeers()
            SagerDatabase.rulesDao.createRule(RuleEntity(name = "second peer", domains = "full:b.example", outbound = old[1].id, enabled = true))

            update(conf("10.0.0.1", remark = null, "192.0.2.1:51820", "192.0.2.2:51820"))

            val after = stored().associateBy { it.id }
            // The first peer's profile now holds both peers; the referenced second one stays.
            assertEquals(setOf(old[0].id, old[1].id), after.keys)
            val merged = after.getValue(old[0].id).wg()
            assertTrue(merged.extraPeers!!.contains("Endpoint = 192.0.2.2:51820"))
            assertEquals("10.0.0.0/16", merged.allowedIPs)
        }
    }

    @Test
    fun providerDnsAndRemarkChangesKeepTheProfileHistoryAndLocalPolicy() = runTest {
        withContext(Dispatchers.IO) {
            update(conf("10.0.0.1, corp.example", "office"))
            val original = stored().single().apply {
                tx = 123L
                rx = 456L
                putBean(
                    wg().apply {
                        dnsMode = WireGuardDnsMode.DOMAINS
                        customDnsServers = "10.0.0.53"
                        customDnsDomains = "private.example"
                        customOutboundJson = "{\"connect_timeout\":\"5s\"}"
                    },
                )
            }
            SagerDatabase.proxyDao.updateProxy(original)
            val before = KryoConverters.serialize(original.wg())

            update(conf("10.0.0.2, changed.example", "office renewed"))

            val after = stored().single()
            assertEquals(original.id, after.id)
            assertEquals(123L, after.tx)
            assertEquals(456L, after.rx)
            assertEquals("office renewed", after.displayName())
            assertEquals("10.0.0.2", after.wg().importedDnsServers)
            assertEquals("changed.example", after.wg().importedDnsDomains)
            assertEquals(WireGuardDnsMode.DOMAINS, after.wg().dnsMode)
            assertEquals("10.0.0.53", after.wg().customDnsServers)
            assertEquals("private.example", after.wg().customDnsDomains)
            assertEquals(original.wg().customOutboundJson, after.wg().customOutboundJson)
            assertArrayEquals(before, KryoConverters.serialize(original.wg()))
        }
    }

    @Test
    fun amneziaDnsAndRemarkChangesKeepTheLocalChoice() = runTest {
        withContext(Dispatchers.IO) {
            fun awg(dns: String, remark: String) = conf(dns, remark).replaceFirst("[Interface]", "[Interface]\nJc = 1")
            update(awg("10.0.0.1, old.example", "old"))
            val original = stored().single()
            val bean = (original.requireBean() as AmneziaWGBean).apply {
                dnsMode = WireGuardDnsMode.DOMAINS
                customDnsServers = "10.0.0.53"
                customDnsDomains = "private.example"
                customConfigJson = "{\"route\":{\"auto_detect_interface\":true}}"
            }
            SagerDatabase.proxyDao.updateProxy(original.putBean(bean))
            val before = KryoConverters.serialize(bean)

            update(awg("10.0.0.2, new.example", "new"))

            val after = stored().single()
            val refreshed = after.requireBean() as AmneziaWGBean
            assertEquals(original.id, after.id)
            assertEquals("10.0.0.2", refreshed.importedDnsServers)
            assertEquals("new.example", refreshed.importedDnsDomains)
            assertEquals(WireGuardDnsMode.DOMAINS, refreshed.dnsMode)
            assertEquals("10.0.0.53", refreshed.customDnsServers)
            assertEquals("private.example", refreshed.customDnsDomains)
            assertEquals(bean.customConfigJson, refreshed.customConfigJson)
            assertArrayEquals(before, KryoConverters.serialize(bean))
        }
    }

    @Test
    fun refusedClashWireGuardEntriesKeepUnmatchedStoredProfiles() = runTest {
        withContext(Dispatchers.IO) {
            update(conf("10.0.0.1", "stored"))
            val original = stored().single()
            val before = KryoConverters.serialize(original.requireBean())
            fun peer() = JSONObject().put("server", "192.0.2.1").put("port", 51820)
                .put("public-key", "QkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkJCQkI=")
                .put("allowed-ips", JSONArray().put("10.0.0.0/16"))
            fun node() = JSONObject().put("name", "incoming").put("type", "wireguard")
                .put("private-key", "QUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUFBQUE=")
                .put("ip", "10.8.0.2").put("peers", JSONArray().put(peer()))
            val refused = listOf(
                node().apply { getJSONArray("peers").getJSONObject(0).remove("allowed-ips") },
                node().put("reserved", JSONArray().put(1).put(2)),
                node().put("dns", JSONArray().put("dhcp://eth0")),
                node().put("amnezia-wg-option", JSONObject()),
                node().apply { getJSONArray("peers").put(peer().put("public-key", "key\nAllowedIPs = 0.0.0.0/0")) },
                node().put("peers", JSONArray().apply { repeat(257) { put(peer()) } }),
            )
            val valid = JSONObject().put("name", "kept").put("type", "socks5").put("server", "192.0.2.9").put("port", 1080)
            for ((index, invalid) in refused.withIndex()) {
                // JSON's optional slash escapes are not YAML escapes; keep this fixture valid YAML.
                val input = JSONObject().put("proxies", JSONArray().put(valid).put(invalid)).toString().replace("\\/", "/")
                assertNotNull("refused case $index", RawUpdater.parseImport(input))
                update(input)
                assertArrayEquals(before, KryoConverters.serialize(stored().single { it.id == original.id }.requireBean()))
                assertEquals(2, stored().size)
                assertTrue(subscription.importWarning!!.contains("wireguard 1"))
            }
        }
    }

    @Test
    fun deletingAnUnreferencedLegacyPeerRequiresAManualDecision() = runTest {
        withContext(Dispatchers.IO) {
            val old = legacyPeers()
            old[0].tx = 123L
            old[1].rx = 456L
            SagerDatabase.proxyDao.updateProxy(old)
            DataStore.selectedProxy = old[0].id
            DataStore.currentProfile = old[0].id
            val before = old.map { KryoConverters.serialize(it.requireBean()) }
            val incoming = conf("10.0.0.1", null, "192.0.2.1:51820", "192.0.2.2:51820")

            assertNotNull(runCatching { update(incoming) }.exceptionOrNull())
            val declined = Decision(false)
            assertNotNull(
                runCatching {
                    RawUpdater.updateFromContent(group, subscription, incoming, declined, byUser = true, reconfigureUpdater = {})
                }.exceptionOrNull(),
            )
            assertEquals(1, declined.asked)
            assertEquals(old.map { it.id }, stored().map { it.id })
            stored().forEachIndexed { index, row -> assertArrayEquals(before[index], KryoConverters.serialize(row.requireBean())) }
            assertEquals(456L, stored()[1].rx)

            val approved = Decision(true)
            RawUpdater.updateFromContent(group, subscription, incoming, approved, byUser = true, reconfigureUpdater = {})
            assertEquals(1, approved.asked)
            assertEquals(old[0].id, stored().single().id)
            assertEquals(123L, stored().single().tx)
            assertEquals(old[0].id, DataStore.selectedProxy)
            assertEquals(old[0].id, DataStore.currentProfile)
        }
    }
}
