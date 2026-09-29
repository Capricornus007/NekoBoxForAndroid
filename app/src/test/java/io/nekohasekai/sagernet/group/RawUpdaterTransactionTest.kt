package io.nekohasekai.sagernet.group

import android.database.Cursor
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SubscriptionFilterMode
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.ArchivedBean
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
