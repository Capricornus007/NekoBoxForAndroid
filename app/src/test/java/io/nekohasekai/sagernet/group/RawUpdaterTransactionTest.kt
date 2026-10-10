package io.nekohasekai.sagernet.group

import android.database.Cursor
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.bg.NetworkAutomation
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RoutingProfiles
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.ArchivedBean
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RawUpdaterTransactionTest {
    private lateinit var group: ProxyGroup
    private lateinit var subscription: SubscriptionBean
    private lateinit var originalLogSink: (String) -> Unit

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        originalLogSink = Logs.sink
        Logs.sink = {}
        ConfigBuilderTestEnv.io {
            subscription = SubscriptionBean().applyDefaultValues().apply {
                link = "https://subscription.example/list"
                lastUpdated = 1234
                subscriptionUserinfo = "download=20; total=100"
                customDnsResolver = "https://resolver.example/dns-query"
                autoUpdate = true
                autoUpdateDelay = 90
            }
            group = ProxyGroup(
                name = "Subscription #1",
                type = GroupType.SUBSCRIPTION,
                subscription = subscription,
                userOrder = 7,
                isSelector = true,
            ).also { it.id = SagerDatabase.groupDao.createGroup(it) }
            listOf("first", "second", "stale").forEachIndexed { index, name ->
                val bean = SOCKSBean().applyDefaultValues().apply {
                    this.name = name
                    serverAddress = "192.0.2.${index + 1}"
                    serverPort = 1080
                    customOutboundJson = """{"marker":"stored-$name"}"""
                    customConfigJson = """{"marker":"config-$name"}"""
                }
                SagerDatabase.proxyDao.addProxy(
                    ProxyEntity(
                        groupId = group.id,
                        userOrder = listOf(9L, 3L, 18L)[index],
                        tx = 10L + index,
                        rx = 20L + index,
                        lifetimeTx = 100L + index,
                        lifetimeRx = 200L + index,
                        status = 1,
                        ping = 50 + index,
                        uuid = "stored-$name",
                        error = "stored-result",
                    ).putBean(bean),
                )
            }
        }
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    @Test
    fun rejectedResponses_preserveEveryDatabaseColumnAndInMemoryMetadata() = runTest {
        withContext(Dispatchers.IO) {
            val inputs = listOf(
                "", " \n\t", "{}", "[]", "{\"outbounds\":[]}",
                "{\"method\":[]}", "[{\"method\":[]}]", "{\"method\":\"aes-128-gcm\"}",
                "proxies: []", "proxies: [{type: unsupported}]",
                "proxies: [{type: socks5, server: 192.0.2.1, port: invalid}]",
                "proxies: !!invalid.GlobalTag {}",
                "[Interface]\nPrivateKey=invalid",
                "socks://192.0.2.1:bad-port",
                "<html>Service unavailable</html>",
                "#support-url: https://support.example/help",
            )
            for (input in inputs) {
                val body = "#profile-title: Replacement\n#subscription-userinfo: download=999\n$input"
                assertRejected(body)
                assertRejected(Base64.getEncoder().encodeToString(body.toByteArray()))
            }
        }
    }

    @Test
    fun emptyFilterResults_areDistinctFromInvalidResponsesAndPreserveData() = runTest {
        withContext(Dispatchers.IO) {
            for ((mode, regex) in listOf(SubscriptionFilterMode.INCLUDE to "^missing$", SubscriptionFilterMode.EXCLUDE to ".*")) {
                subscription.filterMode = mode
                subscription.filterRegex = regex
                SagerDatabase.groupDao.updateGroup(group)
                assertEquals(app.getString(R.string.subscription_filter_empty), assertRejected(validContent).message)
            }
            subscription.filterRegex = "["
            SagerDatabase.groupDao.updateGroup(group)
            assertEquals(app.getString(R.string.subscription_filter_invalid), assertRejected(validContent).message)
        }
    }

    @Test
    fun dataOnlyRecords_rejectReconciliationWithoutChangingAnyRows() = runTest {
        withContext(Dispatchers.IO) {
            val bean = ArchivedBean(25, byteArrayOf(1, 2, 3)).applyDefaultValues()
            SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = group.id, userOrder = 30).putBean(bean))
            assertEquals(app.getString(R.string.profile_unsupported), assertRejected(validContent).message)
        }
    }

    @Test
    fun groupWriteFailure_rollsBackInsertsUpdatesDeletesAndMetadata() = runTest {
        withContext(Dispatchers.IO) {
            val db = SagerDatabase.instance.openHelper.writableDatabase
            db.execSQL("CREATE TRIGGER reject_group_update BEFORE UPDATE ON proxy_groups BEGIN SELECT RAISE(ABORT, 'test rejection'); END")
            try {
                val failure = assertRejected(validContent)
                assertTrue(failure.toString().contains("test rejection"))
            } finally {
                db.execSQL("DROP TRIGGER reject_group_update")
            }
        }
    }

    @Test
    fun countGuardFailure_rollsBackProfileWritesAndMetadata() = runTest {
        withContext(Dispatchers.IO) {
            val db = SagerDatabase.instance.openHelper.writableDatabase
            db.execSQL("CREATE TRIGGER ignore_profile_insert BEFORE INSERT ON proxy_entities BEGIN SELECT RAISE(IGNORE); END")
            try {
                assertTrue(assertRejected(validContent).message!!.startsWith("Exist profiles:"))
            } finally {
                db.execSQL("DROP TRIGGER ignore_profile_insert")
            }
        }
    }

    @Test
    fun acceptedUpdate_commitsProfilesAndHttpMetadataTogether() = runTest {
        withContext(Dispatchers.IO) {
            val before = SagerDatabase.proxyDao.getByGroup(group.id).associateBy { it.displayName() }
            RawUpdater.updateFromContent(
                group,
                subscription,
                validContent,
                httpTitle = "base64:${Base64.getEncoder().encodeToString("HTTP title".toByteArray())}",
                httpUserinfo = "download=50; total=100",
                contentDisposition = "attachment; filename*=UTF-8''disposition",
            )
            val after = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(listOf("second", "first", "added"), after.map { it.displayName() })
            assertEquals(listOf(1L, 2L, 3L), after.map { it.userOrder })
            for (entity in after.take(2)) {
                val old = before.getValue(entity.displayName())
                assertEquals(old.id, entity.id)
                assertEquals(old.tx, entity.tx)
                assertEquals(old.rx, entity.rx)
                assertEquals(old.lifetimeTx, entity.lifetimeTx)
                assertEquals(old.lifetimeRx, entity.lifetimeRx)
                assertEquals(old.status, entity.status)
                assertEquals(old.ping, entity.ping)
                assertEquals(old.uuid, entity.uuid)
                assertEquals(old.error, entity.error)
                assertEquals(old.requireBean().customOutboundJson, entity.requireBean().customOutboundJson)
                assertEquals(old.requireBean().customConfigJson, entity.requireBean().customConfigJson)
            }
            assertEquals("192.0.2.22", after.first().requireBean().serverAddress)
            val storedGroup = SagerDatabase.groupDao.getById(group.id)!!
            assertEquals("HTTP title", group.name)
            assertEquals(group.name, storedGroup.name)
            assertEquals("download=50; total=100", subscription.subscriptionUserinfo)
            assertArrayEquals(KryoConverters.serialize(subscription), KryoConverters.serialize(storedGroup.subscription))
            assertTrue(subscription.lastUpdated!! > 1234)
            assertEquals("https://resolver.example/dns-query", subscription.customDnsResolver)
        }
    }

    @Test
    fun remarkChanges_preserveProfileIdsHistoryAndOverrides() = runTest {
        withContext(Dispatchers.IO) {
            val before = SagerDatabase.proxyDao.getByGroup(group.id).associateBy { it.requireBean().serverAddress }
            RawUpdater.updateFromContent(
                group,
                subscription,
                "socks://192.0.2.2:1080#first\nsocks://192.0.2.1:1080#quota-remaining\nsocks://192.0.2.3:1080#days-remaining",
            )
            val after = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(listOf("first", "quota-remaining", "days-remaining"), after.map { it.displayName() })
            for (entity in after) {
                val old = before.getValue(entity.requireBean().serverAddress)
                assertEquals(old.id, entity.id)
                assertEquals(old.uuid, entity.uuid)
                assertEquals(old.lifetimeTx, entity.lifetimeTx)
                assertEquals(old.lifetimeRx, entity.lifetimeRx)
                assertEquals(old.requireBean().customOutboundJson, entity.requireBean().customOutboundJson)
                assertEquals(old.requireBean().customConfigJson, entity.requireBean().customConfigJson)
            }
        }
    }

    @Test
    fun remarkTakenByAnUnrelatedNode_startsFreshWithoutStoredOverrides() = runTest {
        withContext(Dispatchers.IO) {
            val first = SagerDatabase.proxyDao.getByGroup(group.id).single { it.displayName() == "first" }
            RawUpdater.updateFromContent(group, subscription, "socks://other:secret@198.51.100.7:1080#first\nsocks://192.0.2.2:1080#second")
            val replacement = SagerDatabase.proxyDao.getByGroup(group.id).single { it.displayName() == "first" }
            assertNotEquals(first.id, replacement.id)
            assertEquals("", replacement.requireBean().customOutboundJson)
            assertEquals("", replacement.requireBean().customConfigJson)
            assertNull(SagerDatabase.proxyDao.getById(first.id))
        }
    }

    @Test
    fun partialResponse_keepsUnmatchedProfilesAfterTheListedOnes() = runTest {
        withContext(Dispatchers.IO) {
            val stale = SagerDatabase.proxyDao.getByGroup(group.id).single { it.displayName() == "stale" }
            // The third entry names a transport the core cannot build, so it is not imported.
            val content = "socks://192.0.2.1:1080#first\nsocks://192.0.2.2:1080#second\n" +
                "vless://00000000-0000-4000-8000-000000000001@192.0.2.5:443?type=future&security=none#future"
            RawUpdater.updateFromContent(group, subscription, content)
            val after = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(listOf("first", "second", "stale"), after.map { it.displayName() })
            assertEquals(listOf(1L, 2L, 3L), after.map { it.userOrder })
            assertEquals(stale.id, after.last().id)
            assertEquals(stale.requireBean().customOutboundJson, after.last().requireBean().customOutboundJson)
            assertEquals(
                listOf(
                    app.getString(R.string.subscription_import_failed, 2, 1, "vless 1"),
                    app.getString(R.string.subscription_kept_partial, 1),
                ).joinToString("\n"),
                subscription.importWarning,
            )

            // A complete response removes it.
            RawUpdater.updateFromContent(group, subscription, "socks://192.0.2.1:1080#first\nsocks://192.0.2.2:1080#second")
            assertEquals(listOf("first", "second"), SagerDatabase.proxyDao.getByGroup(group.id).map { it.displayName() })
            assertEquals("", subscription.importWarning)
        }
    }

    @Test
    fun aNodeThatGainsACertificatePin_isRefusedAndItsStoredProfileKept() = runTest {
        withContext(Dispatchers.IO) {
            val node = "vless://00000000-0000-4000-8000-000000000001@192.0.2.5:443?type=tcp&security=tls&sni=a.example"
            val socks = "socks://192.0.2.1:1080#first\nsocks://192.0.2.2:1080#second"
            RawUpdater.updateFromContent(group, subscription, "$socks\n$node#pinned")
            val pinned = SagerDatabase.proxyDao.getByGroup(group.id).single { it.displayName() == "pinned" }

            // The provider now pins the certificate (synthetic value); the link is refused, not imported without it.
            RawUpdater.updateFromContent(group, subscription, "$socks\n$node&pcs=${"0f".repeat(32)}#pinned")
            val after = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(listOf("first", "second", "pinned"), after.map { it.displayName() })
            assertEquals(pinned.id, after.last().id)
            assertEquals(pinned.requireBean(), after.last().requireBean())
            assertEquals(
                listOf(
                    app.getString(R.string.subscription_import_failed, 2, 1, "vless 1"),
                    app.getString(R.string.subscription_kept_partial, 1),
                ).joinToString("\n"),
                subscription.importWarning,
            )
        }
    }

    @Test
    fun aHysteria2NodeThatGainsAPinnedCertificate_keepsItsStoredProfile() = runTest {
        withContext(Dispatchers.IO) {
            val node = "hy2://secret@192.0.2.5:443?sni=a.example"
            val socks = "socks://192.0.2.1:1080#first\nsocks://192.0.2.2:1080#second"
            RawUpdater.updateFromContent(group, subscription, "$socks\n$node#fast")
            val fast = SagerDatabase.proxyDao.getByGroup(group.id).single { it.displayName() == "fast" }
            // Synthetic certificate name constraint; the boundary refuses it for this scheme too.
            RawUpdater.updateFromContent(group, subscription, "$socks\n$node&vcn=a.example#fast")
            val after = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(listOf("first", "second", "fast"), after.map { it.displayName() })
            assertEquals(fast.id, after.last().id)
            assertEquals(app.getString(R.string.subscription_import_failed, 2, 1, "hy2 1"), subscription.importWarning.orEmpty().lines().first())
        }
    }

    @Test
    fun droppedProfilesStillReferenced_areKeptInsteadOfOrphaned() = runTest {
        withContext(Dispatchers.IO) {
            val stale = SagerDatabase.proxyDao.getByGroup(group.id).single { it.displayName() == "stale" }
            val second = SagerDatabase.proxyDao.getByGroup(group.id).single { it.displayName() == "second" }
            val first = SagerDatabase.proxyDao.getByGroup(group.id).single { it.displayName() == "first" }
            val chains = ProxyGroup(name = "chains").also { it.id = SagerDatabase.groupDao.createGroup(it) }
            SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "via stale", domains = "example.com", outbound = stale.id)))
            SagerDatabase.proxyDao.addProxy(
                ProxyEntity(groupId = chains.id, userOrder = 1).putBean(ChainBean().applyDefaultValues().apply { proxies = listOf(second.id) }),
            )
            val fronted = ProxyGroup(name = "fronted", frontProxy = first.id).also { it.id = SagerDatabase.groupDao.createGroup(it) }

            RawUpdater.updateFromContent(group, subscription, "socks://192.0.2.4:1080#added")
            // Kept profiles follow the listed one in their stored order (second, first, stale).
            val after = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(listOf("added", "second", "first", "stale"), after.map { it.displayName() })
            assertEquals(listOf(second.id, first.id, stale.id), after.drop(1).map { it.id })
            assertEquals(listOf(1L, 2L, 3L, 4L), after.map { it.userOrder })
            assertEquals(app.getString(R.string.subscription_kept_referenced, 3), subscription.importWarning)

            // Once nothing refers to them, the next update deletes them.
            SagerDatabase.rulesDao.reset()
            SagerDatabase.proxyDao.deleteByGroup(chains.id)
            SagerDatabase.groupDao.updateGroup(fronted.apply { frontProxy = -1L })
            RawUpdater.updateFromContent(group, subscription, "socks://192.0.2.4:1080#added")
            assertEquals(listOf("added"), SagerDatabase.proxyDao.getByGroup(group.id).map { it.displayName() })
            assertEquals("", subscription.importWarning)
        }
    }

    @Test
    fun networkRuleTargetsSurviveCompleteSubscriptionUpdates() = runTest {
        withContext(Dispatchers.IO) {
            val target = SagerDatabase.proxyDao.getByGroup(group.id).single { it.displayName() == "stale" }
            DataStore.selectedProxy = 0L
            DataStore.currentProfile = 0L
            DataStore.networkAutomation = false
            NetworkAutomation.saveRules(listOf(NetworkAutomation.Rule(NetworkAutomation.Kind.MOBILE, action = NetworkAutomation.Action.CONNECT, profileId = target.id)))

            RawUpdater.updateFromContent(group, subscription, validContent)

            val kept = SagerDatabase.proxyDao.getById(target.id)!!
            assertArrayEquals(KryoConverters.serialize(target.requireBean()), KryoConverters.serialize(kept.requireBean()))
            assertEquals(target.lifetimeTx, kept.lifetimeTx)
            assertEquals(target.lifetimeRx, kept.lifetimeRx)
            assertEquals(target.id, NetworkAutomation.rules().single().profileId)
            assertEquals(app.getString(R.string.subscription_kept_referenced, 1), subscription.importWarning)

            NetworkAutomation.saveRules(emptyList())
            RawUpdater.updateFromContent(group, subscription, validContent)
            assertNull(SagerDatabase.proxyDao.getById(target.id))
        }
    }

    @Test
    fun selectedAndRunningProfiles_areKeptWhenTheProviderDropsThem() = runTest {
        withContext(Dispatchers.IO) {
            val stored = SagerDatabase.proxyDao.getByGroup(group.id).associateBy { it.displayName() }
            DataStore.selectedProxy = stored.getValue("stale").id
            DataStore.currentProfile = stored.getValue("second").id
            RawUpdater.updateFromContent(group, subscription, "socks://192.0.2.1:1080#first")
            val after = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(listOf("first", "second", "stale"), after.map { it.displayName() })
            assertEquals(stored.getValue("stale").id, DataStore.selectedProxy)
            assertTrue(after.any { it.id == DataStore.selectedProxy })
            assertEquals(app.getString(R.string.subscription_kept_referenced, 2), subscription.importWarning)
        }
    }

    @Test
    fun representationChange_keepsEveryStoredProfileUntilTheUserAgrees() = runTest {
        withContext(Dispatchers.IO) {
            for (index in 4..10) addSocks("node$index", "192.0.2.$index", 15L + index)
            val stored = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(10, stored.size)
            DataStore.selectedProxy = stored.single { it.displayName() == "second" }.id
            SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "via first", domains = "example.com", outbound = stored.single { it.displayName() == "first" }.id)))
            val profiles = snapshot()[0]
            val native = stored.joinToString("\n") { "socks://${it.requireBean().serverAddress}:1080#${it.displayName()}" }
            val raw = singBoxOutbounds((1..11).map { "raw$it" to "198.51.100.$it" })

            // A background update is refused; only the notice is stored.
            val notice = app.getString(R.string.subscription_representation_kept, 8, app.getString(R.string.subscription_representation_raw))
            assertEquals(notice, runCatching { RawUpdater.updateFromContent(group, subscription, raw) }.exceptionOrNull()?.message)
            assertEquals(notice, subscription.importWarning)
            assertEquals(notice, SagerDatabase.groupDao.getById(group.id)!!.subscription!!.importWarning)
            assertEquals(profiles, snapshot()[0])

            // A manual update the user declines changes nothing either.
            val declined = RecordingInterface(confirm = false)
            assertEquals(notice, runCatching { RawUpdater.updateFromContent(group, subscription, raw, declined, byUser = true) }.exceptionOrNull()?.message)
            assertEquals(1, declined.confirms.size)
            assertTrue(declined.confirms.single().contains("8"))
            assertEquals(profiles, snapshot()[0])

            // The native feed returns: every profile keeps its ID, history, overrides and references.
            RawUpdater.updateFromContent(group, subscription, native)
            val after = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(stored.map { it.id }, after.map { it.id })
            for ((old, new) in stored.zip(after)) {
                assertEquals(old.lifetimeTx, new.lifetimeTx)
                assertEquals(old.uuid, new.uuid)
                assertEquals(old.requireBean().customOutboundJson, new.requireBean().customOutboundJson)
            }
            assertEquals(stored.single { it.displayName() == "second" }.id, DataStore.selectedProxy)
            assertEquals(stored.single { it.displayName() == "first" }.id, SagerDatabase.rulesDao.allRules().single().outbound)
            assertEquals("", subscription.importWarning)
        }
    }

    @Test
    fun representationChange_onceConfirmed_replacesOnlyProfilesNotInUse() = runTest {
        withContext(Dispatchers.IO) {
            val stored = SagerDatabase.proxyDao.getByGroup(group.id).associateBy { it.displayName() }
            DataStore.selectedProxy = stored.getValue("stale").id
            SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "via first", domains = "example.com", outbound = stored.getValue("first").id)))
            val accepted = RecordingInterface(confirm = true)
            RawUpdater.updateFromContent(group, subscription, singBoxOutbounds(listOf("raw1" to "198.51.100.1", "raw2" to "198.51.100.2")), accepted, byUser = true)

            val after = SagerDatabase.proxyDao.getByGroup(group.id)
            assertEquals(listOf("raw1", "raw2", "first", "stale"), after.map { it.displayName() })
            assertEquals(listOf(stored.getValue("first").id, stored.getValue("stale").id), after.drop(2).map { it.id })
            assertEquals("""{"marker":"stored-first"}""", after[2].requireBean().customOutboundJson)
            assertTrue(after.take(2).all { it.requireBean() is ConfigBean && it.requireBean().customOutboundJson == "" })
            // The summary lists replacements as removed and added, never as updated.
            assertEquals(listOf("second"), accepted.deleted)
            assertEquals(listOf("raw1", "raw2"), accepted.added)
            assertTrue(accepted.updated.isEmpty())
            assertEquals(app.getString(R.string.subscription_kept_referenced, 2), subscription.importWarning)
        }
    }

    @Test
    fun mixedFeeds_convertingSomeNodesAreAlsoAskedFirst() = runTest {
        withContext(Dispatchers.IO) {
            val shadowsocks = """{"server":"192.0.2.50","server_port":8388,"method":"aes-128-gcm","password":"x","remarks":"ss-node"}"""
            SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = group.id, userOrder = 30).putBean(RawUpdater.parseJSON(JSONObject(shadowsocks)).single()))
            val profiles = snapshot()[0]
            // One native entry stays; the other nodes now arrive as sing-box outbounds.
            val mixed = "[" + shadowsocks + "," + singBoxOutbounds(listOf("first" to "192.0.2.1", "second" to "192.0.2.2")).let { JSONObject(it).getJSONArray("outbounds").join(",") } + "]"
            val failure = runCatching { RawUpdater.updateFromContent(group, subscription, mixed) }.exceptionOrNull()
            assertEquals(app.getString(R.string.subscription_representation_kept, 3, app.getString(R.string.subscription_representation_raw)), failure?.message)
            assertEquals(profiles, snapshot()[0])
        }
    }

    @Test
    fun warningsShowAfterTheUpdateFinishesAndOnlyWhenTheyChange() = runTest {
        withContext(Dispatchers.IO) {
            val partial = "socks://192.0.2.1:1080#first\nsocks://192.0.2.2:1080#second\n" +
                "vless://00000000-0000-4000-8000-000000000001@192.0.2.5:443?type=future&security=none#future"
            val updatingWhileShown = mutableListOf<Boolean>()
            val ui = RecordingInterface(confirm = false) { updatingWhileShown += group.id in GroupUpdater.updating }
            GroupUpdater.updating += group.id
            RawUpdater.updateFromContent(group, subscription, partial, ui, byUser = true)
            assertEquals(listOf(false), updatingWhileShown)
            assertEquals(listOf(subscription.importWarning), ui.alerts)

            // An unchanged warning stays in the settings only; a changed one shows again.
            RawUpdater.updateFromContent(group, subscription, partial, ui, byUser = true)
            assertEquals(1, ui.alerts.size)
            RawUpdater.updateFromContent(group, subscription, "$partial\nmieru://192.0.2.6", ui, byUser = true)
            assertEquals(2, ui.alerts.size)
            // Background updates never show it.
            RawUpdater.updateFromContent(group, subscription, partial, ui)
            assertEquals(2, ui.alerts.size)
        }
    }

    @Test
    fun aWarningNobodyDismisses_neverHoldsTheUpdateOrTheNextOne() = runTest {
        withContext(Dispatchers.IO) {
            val partial = "socks://192.0.2.1:1080#first\n" +
                "vless://00000000-0000-4000-8000-000000000001@192.0.2.5:443?type=future&security=none#future"
            val shown = CompletableDeferred<Unit>()
            val stuck = RecordingInterface(confirm = false) {
                shown.complete(Unit)
                awaitCancellation()
            }
            GroupUpdater.updating += group.id
            val first = launch { RawUpdater.updateFromContent(group, subscription, partial, stuck, byUser = true) }
            shown.await()
            // The update committed and released the group before the warning showed.
            assertFalse(group.id in GroupUpdater.updating)
            assertEquals(listOf("first", "second", "stale"), SagerDatabase.proxyDao.getByGroup(group.id).map { it.displayName() })
            RawUpdater.updateFromContent(group, subscription, validContent)
            assertEquals(listOf("second", "first", "added"), SagerDatabase.proxyDao.getByGroup(group.id).map { it.displayName() })
            first.cancelAndJoin()
        }
    }

    @Test
    fun providerProposedLinks_areStoredAsHttpsOffersWithoutTouchingTheLink() = runTest {
        withContext(Dispatchers.IO) {
            subscription.backupLinks = "https://backup.example/list"
            RawUpdater.updateFromContent(
                group,
                subscription,
                validContent,
                httpMeta = mapOf("new-domain" to "moved.example", "fallback-url" to "https://backup.example/list"),
            )
            assertEquals("https://subscription.example/list", subscription.link)
            assertEquals("https://moved.example/list", subscription.offeredLink)
            // Already an approved backup, so nothing to offer.
            assertEquals("", subscription.offeredBackupLink)

            RawUpdater.updateFromContent(
                group,
                subscription,
                validContent,
                httpMeta = mapOf("new-url" to "https://new.example/list?t=1", "new-domain" to "ignored.example", "fallback-url" to "http://cleartext.example/list"),
            )
            assertEquals("https://new.example/list?t=1", subscription.offeredLink)
            assertEquals("", subscription.offeredBackupLink)

            RawUpdater.updateFromContent(group, subscription, "#new-url: https://subscription.example/list\n#fallback-url: https://second.example/list\n$validContent")
            assertEquals("", subscription.offeredLink)
            assertEquals("https://second.example/list", subscription.offeredBackupLink)
            assertArrayEquals(KryoConverters.serialize(subscription), KryoConverters.serialize(SagerDatabase.groupDao.getById(group.id)!!.subscription))

            // Offers describe the last update only.
            RawUpdater.updateFromContent(group, subscription, validContent)
            assertEquals("", subscription.offeredLink)
            assertEquals("", subscription.offeredBackupLink)
            assertEquals("https://subscription.example/list", subscription.link)
            assertEquals("https://backup.example/list", subscription.backupLinks)
        }
    }

    @Test
    fun providerRoutingLine_isStoredAsAnInactiveRulesOnlyProfile() = runTest {
        withContext(Dispatchers.IO) {
            DataStore.configurationStore.remove(Key.ROUTING_PROFILES)
            DataStore.configurationStore.remove(Key.ROUTING_PROFILE_ACTIVE)
            val profile = JSONObject()
                .put("Name", "Provider")
                .put("GlobalProxy", "true")
                .put("RemoteDNSType", "DoH")
                .put("DirectSites", JSONArray(listOf("geosite:private", "ext:custom.dat:tag")))
                .put("BlockSites", JSONArray(listOf("geosite:category-ads-all")))
            val link = "happ://routing/onadd/" + Base64.getEncoder().encodeToString(profile.toString().toByteArray())
            val nodes = validContent.lines().filterNot { it.startsWith('#') }.joinToString("\n")
            RawUpdater.updateFromContent(group, subscription, "$link\n$nodes")

            assertEquals(3, SagerDatabase.proxyDao.countByGroup(group.id))
            val stored = RoutingProfiles.list().single()
            assertEquals(RoutingProfiles.subscriptionSource(group.id), stored.source)
            assertEquals("Provider", stored.name)
            assertEquals(2, stored.ruleCount)
            assertFalse(stored.content.has("settings"))
            assertEquals(0L, RoutingProfiles.activeId)
            assertEquals(
                app.getString(R.string.routing_profile_not_applied, "Provider", "DirectSites (1), RemoteDNSType, onadd"),
                subscription.importWarning,
            )

            RawUpdater.updateFromContent(group, subscription, "happ://routing/off\n$nodes")
            assertEquals(app.getString(R.string.subscription_routing_off_ignored), subscription.importWarning)
            assertEquals(1, RoutingProfiles.list().size)
        }
    }

    @Test
    fun providerMetadata_isStoredValidatedAndClearedWhenOmitted() = runTest {
        withContext(Dispatchers.IO) {
            val body = "#announce: base64:${Base64.getEncoder().encodeToString("Body notice".toByteArray())}\n#support-url: https://body.example\n$validContent"
            var reconfigured = 0
            RawUpdater.updateFromContent(
                group,
                subscription,
                body,
                httpMeta = mapOf(
                    "support-url" to "https://t.me/support",
                    "profile-web-page-url" to "javascript:alert(1)",
                    "announce" to "x".repeat(300),
                    "profile-update-interval" to "12",
                ),
                reconfigureUpdater = { reconfigured++ },
            )
            assertEquals(1, reconfigured)
            assertEquals("https://t.me/support", subscription.supportUrl)
            assertEquals("", subscription.webPageUrl)
            assertEquals("x".repeat(200), subscription.announce)
            assertEquals(true, subscription.autoUpdate)
            assertEquals(12 * 60, subscription.autoUpdateDelay)
            assertEquals(12 * 60, subscription.providerUpdateInterval)
            val stored = SagerDatabase.groupDao.getById(group.id)!!.subscription!!
            assertArrayEquals(KryoConverters.serialize(subscription), KryoConverters.serialize(stored))

            // The user's schedule survives an unchanged provider interval and applies again on a new one.
            subscription.autoUpdate = false
            subscription.autoUpdateDelay = 45
            RawUpdater.updateFromContent(group, subscription, body, httpMeta = mapOf("profile-update-interval" to "12"), reconfigureUpdater = { reconfigured++ })
            assertEquals(false, subscription.autoUpdate)
            assertEquals(45, subscription.autoUpdateDelay)
            assertEquals(1, reconfigured)
            RawUpdater.updateFromContent(group, subscription, body, httpMeta = mapOf("profile-update-interval" to "6"), reconfigureUpdater = { reconfigured++ })
            assertEquals(true, subscription.autoUpdate)
            assertEquals(6 * 60, subscription.autoUpdateDelay)
            assertEquals(2, reconfigured)

            // Preamble values apply when the response carries no headers; omitted keys clear.
            RawUpdater.updateFromContent(group, subscription, body)
            assertEquals("https://body.example", subscription.supportUrl)
            assertEquals("Body notice", subscription.announce)
            assertEquals(0, subscription.providerUpdateInterval)
            RawUpdater.updateFromContent(group, subscription, validContent)
            assertEquals("", subscription.supportUrl)
            assertEquals("", subscription.announce)
            assertEquals(6 * 60, subscription.autoUpdateDelay)
        }
    }

    @Test
    fun metadataFallbacks_preserveCustomNamesAndFileUsage() = runTest {
        withContext(Dispatchers.IO) {
            RawUpdater.updateFromContent(group, subscription, validContent, httpTitle = "base64:!", contentDisposition = "attachment; filename*=UTF-8''Disposition%20title")
            assertEquals("Disposition title", group.name)
            assertEquals("download=30; total=100", subscription.subscriptionUserinfo)
            group.name = "Subscription #1"
            RawUpdater.updateFromContent(group, subscription, validContent, httpTitle = " ", httpUserinfo = " ", contentDisposition = "attachment; filename*=UTF-8''%invalid")
            assertEquals("Body title", group.name)
            assertEquals("download=30; total=100", subscription.subscriptionUserinfo)
            group.name = "Custom name"
            val links = validContent.lines().filterNot { it.startsWith('#') }.joinToString("\n")
            RawUpdater.updateFromContent(group, subscription, links, httpTitle = "Ignored title")
            assertEquals("Custom name", group.name)
            assertEquals("download=30; total=100", subscription.subscriptionUserinfo)
            RawUpdater.updateFromContent(group, subscription, links, httpUserinfo = "")
            assertEquals("", subscription.subscriptionUserinfo)
            assertEquals("Custom name", SagerDatabase.groupDao.getById(group.id)!!.name)
        }
    }

    private fun addSocks(name: String, address: String, order: Long) {
        val bean = SOCKSBean().applyDefaultValues().apply {
            this.name = name
            serverAddress = address
            serverPort = 1080
            customOutboundJson = """{"marker":"stored-$name"}"""
        }
        SagerDatabase.proxyDao.addProxy(ProxyEntity(groupId = group.id, userOrder = order, lifetimeTx = order).putBean(bean))
    }

    private fun singBoxOutbounds(nodes: List<Pair<String, String>>) = JSONObject().put(
        "outbounds",
        JSONArray().apply {
            for ((tag, server) in nodes) put(JSONObject().put("type", "socks").put("tag", tag).put("server", server).put("server_port", 1080))
        },
    ).toString()

    /** Records what an update shows; [onAlert] runs while a warning is showing. */
    private class RecordingInterface(private val confirm: Boolean, private val onAlert: suspend () -> Unit = {}) : GroupManager.Interface {
        val confirms = mutableListOf<String>()
        val alerts = mutableListOf<String>()
        var added = emptyList<String>()
        var updated = emptyMap<String, String>()
        var deleted = emptyList<String>()

        override suspend fun confirm(message: String): Boolean {
            confirms += message
            return confirm
        }

        override suspend fun alert(message: String) {
            alerts += message
            onAlert()
        }

        override suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean,
        ) {
            this.added = added
            this.updated = updated
            this.deleted = deleted
        }

        override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {}
    }

    private suspend fun assertRejected(content: String): Throwable {
        val before = snapshot()
        val groupBytes = KryoConverters.serialize(group)
        val subscriptionBytes = KryoConverters.serialize(subscription)
        val failure = runCatching {
            RawUpdater.updateFromContent(group, subscription, content, httpTitle = "HTTP replacement", httpUserinfo = "download=999")
        }.exceptionOrNull()
        assertNotNull("Update must fail without reconciling", failure)
        assertEquals(before, snapshot())
        assertArrayEquals(groupBytes, KryoConverters.serialize(group))
        assertArrayEquals(subscriptionBytes, KryoConverters.serialize(subscription))
        assertSame(subscription, group.subscription)
        return failure!!
    }

    private fun snapshot() = listOf("proxy_entities", "proxy_groups").map { table ->
        SagerDatabase.instance.openHelper.readableDatabase.query("SELECT * FROM $table ORDER BY id").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).map { index ->
                            when (cursor.getType(index)) {
                                Cursor.FIELD_TYPE_NULL -> null
                                Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(index).toList()
                                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                else -> cursor.getString(index)
                            }
                        },
                    )
                }
            }
        }
    }

    private val validContent = """
        #profile-title: Body title
        #subscription-userinfo: download=30; total=100
        socks://192.0.2.22:1080#second
        socks://192.0.2.1:1080#first
        socks://192.0.2.4:1080#added
    """.trimIndent()
}
