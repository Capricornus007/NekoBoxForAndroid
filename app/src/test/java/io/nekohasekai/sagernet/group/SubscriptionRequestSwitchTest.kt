package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.group.SubscriptionFormat.AUTO
import io.nekohasekai.sagernet.group.SubscriptionFormat.CLASH
import io.nekohasekai.sagernet.group.SubscriptionFormat.DEFAULT
import io.nekohasekai.sagernet.group.SubscriptionFormat.LINKS
import io.nekohasekai.sagernet.group.SubscriptionFormat.UNKNOWN
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.GroupSettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Updates through the same path as the app, from the settings screen's save to the fetch, with the
 * network replaced: which request each update makes, and what happens to stored profiles when the
 * request that answers is not the one they came from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SubscriptionRequestSwitchTest {
    private lateinit var originalLogSink: (String) -> Unit
    private lateinit var group: ProxyGroup
    private lateinit var subscription: SubscriptionBean
    private val linksAgent = SubscriptionFormat.plan(AUTO, 0, false, "").first.single { it.format == LINKS }.userAgent
    private val uuids = listOf("10", "11", "12").map { "00000000-0000-4000-8000-0000000000$it" }

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        originalLogSink = Logs.sink
        Logs.sink = {}
        ConfigBuilderTestEnv.io {
            subscription = SubscriptionBean().applyDefaultValues().apply { link = "https://subscription.example/list" }
            group = ProxyGroup(name = "Provider", type = GroupType.SUBSCRIPTION, subscription = subscription)
                .also { it.id = SagerDatabase.groupDao.createGroup(it) }
        }
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    // Quota left is part of each remark, as 3x-ui panels write it.
    private fun clash(quota: String) = "proxies:\n" + listOf("edge", "relay", "spare").mapIndexed { index, name ->
        "  - {name: \"$name | $quota\", type: vless, server: 192.0.2.1$index, port: 443, uuid: ${uuids[index]}, " +
            "tls: true, servername: $name.example, alpn: [h2, http/1.1], network: tcp}"
    }.joinToString("\n")

    // The same nodes as share links: no packet encoding and the ALPN list in another form.
    private fun links(quota: String) = listOf("edge", "relay", "spare").mapIndexed { index, name ->
        "vless://${uuids[index]}@192.0.2.1$index:443?type=tcp&security=tls&sni=$name.example&alpn=h2%2Chttp%2F1.1#$name%20%7C%20$quota"
    }.joinToString("\n")

    private class Ui(private val answer: Boolean, private val whileAsking: suspend () -> Unit = {}) : GroupManager.Interface {
        val questions = mutableListOf<String>()
        var added = emptyList<String>()
        var deleted = emptyList<String>()

        override suspend fun confirm(message: String): Boolean {
            questions += message
            whileAsking()
            return answer
        }
        override suspend fun alert(message: String) {}
        override suspend fun onUpdateSuccess(group: ProxyGroup, changed: Int, added: List<String>, updated: Map<String, String>, deleted: List<String>, duplicate: List<String>, byUser: Boolean) {
            this.added = added
            this.deleted = deleted
        }

        override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {}
    }

    private suspend fun update(ui: Ui? = null, respond: (String) -> String): List<String> {
        val agents = mutableListOf<String>()
        RawUpdater.updateFromLink(group, subscription, ui, byUser = ui != null, reconfigureUpdater = {}) { _, userAgent, _ ->
            agents += userAgent
            RawUpdater.FetchedResponse(respond(userAgent)) { "" }
        }
        return agents
    }

    /** Saves the group through the settings screen with [format] chosen, as the user would. */
    private fun saveFormat(format: Int) = save { DataStore.subscriptionFormat = format }

    /** Saves the group through the settings screen after [edit] changes its settings. */
    private fun save(edit: () -> Unit) {
        val activity = Robolectric.buildActivity(GroupSettingsActivity::class.java).get()
        ConfigBuilderTestEnv.io {
            val stored = SagerDatabase.groupDao.getById(group.id)!!
            with(activity) {
                stored.init()
                edit()
                stored.serialize()
            }
            SagerDatabase.groupDao.updateGroup(stored)
            group = stored
            subscription = stored.subscription!!
        }
    }

    private fun rows() = SagerDatabase.proxyDao.getByGroup(group.id)

    @Test
    fun changingTheSettingWithoutUpdatingThenEnablingAuto_keepsTheRequestTheProfilesCameFrom() = runTest {
        withContext(Dispatchers.IO) { assertEquals(listOf(USER_AGENT), update { clash("5.0 GB") }) }
        assertEquals(DEFAULT, subscription.negotiatedFormat)
        val before = withContext(Dispatchers.IO) { rows().map { it.id } }

        saveFormat(LINKS)
        saveFormat(AUTO)
        assertEquals(AUTO, subscription.outputFormat)
        assertEquals(DEFAULT, subscription.negotiatedFormat)

        // Auto asks for what the profiles came from, the default request, and nothing else.
        withContext(Dispatchers.IO) {
            assertEquals(listOf(USER_AGENT), update { if (it == USER_AGENT) clash("4.2 GB") else links("4.2 GB") })
            assertEquals(before, rows().map { it.id })
        }
        assertEquals(CLASH, subscription.negotiatedFormat)

        // Choosing Auto again keeps the remembered request.
        saveFormat(AUTO)
        assertEquals(CLASH, subscription.negotiatedFormat)
    }

    @Test
    fun aRememberedFormatThatStopsWorking_neverReplacesProfilesSilently() = runTest {
        saveFormat(AUTO)
        // The first Auto update compares the formats; only the Clash request answers.
        withContext(Dispatchers.IO) {
            update { if (it == USER_AGENT) clash("5.0 GB") else throw Exception("HTTP 404 Not Found") }
            for (entity in rows()) {
                SagerDatabase.proxyDao.updateProxy(
                    entity.apply {
                        lifetimeTx = 1000L + id
                        putBean(requireBean().apply { customOutboundJson = """{"marker":"$id"}""" })
                    },
                )
            }
        }
        assertEquals(CLASH, subscription.negotiatedFormat)
        val stored = withContext(Dispatchers.IO) { rows() }
        val bytes = stored.map { it.id to it.lifetimeTx }
        // Clash describes these nodes with XUDP and a list of ALPN values.
        assertEquals(2, (stored.first().requireBean() as VMessBean).packetEncoding)

        // Now the Clash request fails and share links answer, with new remarks.
        val failingClash: (String) -> String = {
            when (it) {
                USER_AGENT -> throw Exception("HTTP 500 Internal Server Error")
                linksAgent -> links("4.2 GB")
                else -> throw Exception("HTTP 404 Not Found")
            }
        }
        val notice = app.getString(R.string.subscription_request_changed_kept, 3)
        withContext(Dispatchers.IO) {
            // In the background, and when the user declines, every profile stays as it was.
            assertEquals(notice, runCatching { update(respond = failingClash) }.exceptionOrNull()?.message)
            val declined = Ui(answer = false)
            assertEquals(notice, runCatching { update(declined, failingClash) }.exceptionOrNull()?.message)
            assertEquals(1, declined.questions.size)
            assertEquals(bytes, rows().map { it.id to it.lifetimeTx })
            assertEquals(stored.map { it.requireBean().customOutboundJson }, rows().map { it.requireBean().customOutboundJson })
        }
        assertEquals(notice, subscription.importWarning)
        assertEquals(CLASH, subscription.negotiatedFormat)

        // Once the user agrees, profiles in use stay; the others are replaced and reported so.
        withContext(Dispatchers.IO) {
            DataStore.selectedProxy = stored[0].id
            SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "via relay", domains = "example.com", outbound = stored[1].id)))
            val accepted = Ui(answer = true)
            update(accepted, failingClash)
            assertTrue(accepted.questions.single().contains("1"))
            val after = rows()
            assertEquals(listOf(stored[0].id, stored[1].id), after.filter { it.lifetimeTx > 0 }.map { it.id })
            assertEquals(listOf("spare | 5.0 GB"), accepted.deleted)
            assertEquals(3, accepted.added.size)
            assertTrue(after.filter { it.lifetimeTx == 0L }.all { it.requireBean().customOutboundJson == "" })
        }
        assertEquals(LINKS, subscription.negotiatedFormat)

        // The links request is now the remembered one and asked for first.
        withContext(Dispatchers.IO) {
            val ui = Ui(answer = false)
            assertEquals(linksAgent, update(ui) { links("4.0 GB") }.first())
            assertTrue(ui.questions.isEmpty())
        }
    }

    @Test
    fun anExplicitFormatChangeAlsoAsksBeforeReplacingProfiles() = runTest {
        withContext(Dispatchers.IO) { update { clash("5.0 GB") } }
        val before = withContext(Dispatchers.IO) { rows().map { it.id } }
        saveFormat(LINKS)
        withContext(Dispatchers.IO) {
            val failure = runCatching { update { if (it == linksAgent) links("4.2 GB") else clash("4.2 GB") } }.exceptionOrNull()
            assertEquals(app.getString(R.string.subscription_request_changed_kept, 3), failure?.message)
            assertEquals(before, rows().map { it.id })
        }
        assertEquals(DEFAULT, subscription.negotiatedFormat)
    }

    @Test
    fun clearingALegacyCustomUserAgent_marksTheRequestChangedUntilAnUpdateIsAccepted() = runTest {
        withContext(Dispatchers.IO) {
            // Profiles stored before requests were recorded, by a custom User-Agent.
            subscription.customUserAgent = "custom/1.0"
            RawUpdater.updateFromContent(group, subscription, clash("5.0 GB"))
        }
        assertEquals(DEFAULT, subscription.negotiatedFormat)
        val before = withContext(Dispatchers.IO) { rows().map { it.id to it.displayName() } }
        save { DataStore.subscriptionUserAgent = "" }
        assertEquals(SubscriptionFormat.CHANGED, subscription.negotiatedFormat)
        // Choosing Auto afterwards keeps the marker; Auto compares the formats.
        saveFormat(AUTO)
        assertEquals(SubscriptionFormat.CHANGED, subscription.negotiatedFormat)

        val respond: (String) -> String = { if (it == linksAgent) links("4.2 GB") else clash("4.2 GB") }
        val notice = app.getString(R.string.subscription_request_changed_kept, 3)
        withContext(Dispatchers.IO) {
            assertEquals(notice, runCatching { update(respond = respond) }.exceptionOrNull()?.message)
            assertEquals(notice, runCatching { update(Ui(answer = false), respond) }.exceptionOrNull()?.message)
            // Cancelled while asking: nothing is stored, the marker included.
            val cancelled = launch { update(Ui(answer = true) { currentCoroutineContext().cancel() }, respond) }
            cancelled.join()
            assertTrue(cancelled.isCancelled)
            assertEquals(before, rows().map { it.id to it.displayName() })
            assertEquals(SubscriptionFormat.CHANGED, SagerDatabase.groupDao.getById(group.id)!!.subscription!!.negotiatedFormat)
        }
        assertEquals(SubscriptionFormat.CHANGED, subscription.negotiatedFormat)

        // The first accepted replacement records the request it came from.
        withContext(Dispatchers.IO) {
            val accepted = Ui(answer = true)
            update(accepted, respond)
            assertEquals(1, accepted.questions.size)
            assertEquals(3, accepted.deleted.size)
        }
        assertEquals(LINKS, subscription.negotiatedFormat)
    }

    @Test
    fun anotherCustomUserAgentOrAFileLinkBecomingAUrl_isAlsoAskedAbout() = runTest {
        withContext(Dispatchers.IO) {
            subscription.customUserAgent = "custom/1.0"
            update { clash("5.0 GB") }
        }
        assertEquals(UNKNOWN, subscription.negotiatedFormat)
        save { DataStore.subscriptionUserAgent = "custom/2.0" }
        assertEquals(SubscriptionFormat.CHANGED, subscription.negotiatedFormat)
        withContext(Dispatchers.IO) {
            assertEquals(app.getString(R.string.subscription_request_changed_kept, 3), runCatching { update { links("4.2 GB") } }.exceptionOrNull()?.message)
        }

        // A file subscription stored before requests were recorded, then given a link.
        withContext(Dispatchers.IO) {
            subscription.customUserAgent = ""
            subscription.link = "content://files/list.txt"
            subscription.negotiatedFormat = DEFAULT
            SagerDatabase.groupDao.updateGroup(group)
        }
        save { DataStore.subscriptionLink = "https://subscription.example/list" }
        assertEquals(SubscriptionFormat.CHANGED, subscription.negotiatedFormat)
        // An unchanged save keeps the marker, and a link to another link is no change of kind.
        save { DataStore.subscriptionLink = "https://moved.example/list" }
        assertEquals(SubscriptionFormat.CHANGED, subscription.negotiatedFormat)
    }

    @Test
    fun aCancelledUpdateStoresNothingEvenWhenTheFetchReturns() = runTest {
        withContext(Dispatchers.IO) { update { clash("5.0 GB") } }
        val stored = withContext(Dispatchers.IO) { KryoConverters.serialize(SagerDatabase.groupDao.getById(group.id)!!.subscription) }
        val before = withContext(Dispatchers.IO) { rows().map { it.id to it.displayName() } }
        subscription.backupLinks = "https://backup.example/list"
        var attempts = 0
        val job = launch(Dispatchers.IO) {
            RawUpdater.updateFromLink(group, subscription, null, byUser = false, reconfigureUpdater = {}) { _, _, _ ->
                attempts++
                currentCoroutineContext().cancel()
                RawUpdater.FetchedResponse(clash("4.0 GB")) { "" }
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        // No backup was tried, and neither the profiles nor the recorded request changed.
        assertEquals(1, attempts)
        withContext(Dispatchers.IO) {
            assertEquals(before, rows().map { it.id to it.displayName() })
            assertArrayEquals(stored, KryoConverters.serialize(SagerDatabase.groupDao.getById(group.id)!!.subscription))
        }
    }

    @Test
    fun customUserAgentsAndFilesAreNoFormatsRequest() = runTest {
        withContext(Dispatchers.IO) {
            subscription.customUserAgent = "custom/1.0"
            assertEquals(listOf("custom/1.0"), update { clash("5.0 GB") })
            assertEquals(UNKNOWN, subscription.negotiatedFormat)

            // Leaving the custom User-Agent is a change of request, even back to the default one.
            subscription.customUserAgent = ""
            val failure = runCatching { update { links("4.2 GB") } }.exceptionOrNull()
            assertEquals(app.getString(R.string.subscription_request_changed_kept, 3), failure?.message)
            assertEquals(UNKNOWN, subscription.negotiatedFormat)

            RawUpdater.updateFromContent(group, subscription, clash("5.0 GB"), requestFormat = null)
            assertEquals(UNKNOWN, subscription.negotiatedFormat)
            assertNotEquals(0, SagerDatabase.proxyDao.countByGroup(group.id))
        }
    }
}
