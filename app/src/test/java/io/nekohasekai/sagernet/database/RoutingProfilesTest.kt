package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RoutingProfilesTest {

    @Before
    fun setUp() {
        ConfigBuilderTestEnv.reset()
        DataStore.configurationStore.remove(Key.ROUTING_PROFILES)
        DataStore.configurationStore.remove(Key.ROUTING_PROFILE_ACTIVE)
    }

    @Test
    fun switchingRestoresRulesAndSettingsAndKeepsEditsMadeWhileActive() = offMain {
        DataStore.remoteDns = "https://a.example/dns-query"
        DataStore.enableFakeDns = true
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "a-rule", domains = "a.example", outbound = -1)))
        val a = RoutingProfiles.saveLiveAs("A")
        assertEquals(a.id, RoutingProfiles.activeId)

        // Edit while A is active, then branch off into B.
        DataStore.remoteDns = "https://b.example/dns-query"
        DataStore.enableFakeDns = false
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "b-rule", domains = "b.example", outbound = -2)))
        val b = RoutingProfiles.saveLiveAs("B")

        RoutingProfiles.switchTo(a.id)
        assertEquals("https://a.example/dns-query", DataStore.remoteDns)
        assertEquals(true, DataStore.enableFakeDns)
        assertEquals(listOf("a-rule"), SagerDatabase.rulesDao.allRules().map { it.name })

        // Edits made while A is active are carried into A on the next switch.
        DataStore.remoteDns = "https://a2.example/dns-query"
        RoutingProfiles.switchTo(b.id)
        assertEquals("https://b.example/dns-query", DataStore.remoteDns)
        assertEquals(false, DataStore.enableFakeDns)
        assertEquals(listOf("a-rule", "b-rule"), SagerDatabase.rulesDao.allRules().map { it.name })

        RoutingProfiles.switchTo(a.id)
        assertEquals("https://a2.example/dns-query", DataStore.remoteDns)
        assertEquals(listOf("a-rule"), SagerDatabase.rulesDao.allRules().map { it.name })
    }

    @Test
    fun exportRoundTripsAndRejectsForeignJson() = offMain {
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "x", domains = "x.example")))
        val original = RoutingProfiles.saveLiveAs("Original")

        val renamed = original.toExportJson().put("name", "Copy").toString()
        val imported = RoutingProfiles.import(renamed)
        assertNotNull(imported)
        assertEquals("Copy", imported!!.name)
        assertEquals(1, imported.ruleCount)
        assertEquals(2, RoutingProfiles.list().size)

        assertNull(RoutingProfiles.import("""{"rules": []}"""))
        assertNull(RoutingProfiles.import("not json"))
        // Malformed content is refused whole rather than read as empty rules or settings.
        assertNull(RoutingProfiles.import("""{"routingProfile": 1, "name": "Copy", "content": {}}"""))
        assertNull(RoutingProfiles.import("""{"routingProfile": 1, "name": "Copy", "content": {"rules": "x", "settings": []}}"""))
        assertNull(RoutingProfiles.import("""{"routingProfile": 1, "name": "Copy", "content": {"rules": [{"id": 1}], "settings": []}}"""))
        // A setting whose bytes do not fit its type would only fail while being applied, after earlier keys were written.
        val truncated = """{"key": "${Key.ENABLE_FAKEDNS}", "valueType": 1, "value": ""}"""
        assertNull(RoutingProfiles.import("""{"routingProfile": 1, "name": "Copy", "content": {"rules": [], "settings": [$truncated]}}"""))
        assertEquals(1, RoutingProfiles.list().first { it.id == imported.id }.ruleCount)
        assertNull(RoutingProfiles.import(RoutingProfiles.LINK_PREFIX + "!!!"))

        RoutingProfiles.delete(original.id)
        assertEquals(0L, RoutingProfiles.activeId)
        assertEquals(listOf(imported.id), RoutingProfiles.list().map { it.id })

        // Already stored content without the arrays applies as an empty profile.
        RoutingProfiles.applyLive(JSONObject())
        assertTrue(SagerDatabase.rulesDao.allRules().isEmpty())
    }

    @Test
    fun linkImportRefreshesSameNameAndFollowsLiveWhenActive() = offMain {
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "old", domains = "old.example")))
        val active = RoutingProfiles.saveLiveAs("Provider")
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "new", domains = "new.example")))
        val newer = RoutingProfiles.captureLive()
        // Reset live state so applying the refreshed content is observable.
        RoutingProfiles.applyLive(active.content)
        assertEquals(listOf("old"), SagerDatabase.rulesDao.allRules().map { it.name })

        val link = RoutingProfiles.Profile(0L, "Provider", newer).toLink()
        assertEquals("Provider", RoutingProfiles.parse(link)!!.name)
        val refreshed = RoutingProfiles.import(link)!!
        assertEquals(active.id, refreshed.id)
        assertEquals(1, RoutingProfiles.list().size)
        assertEquals(2, RoutingProfiles.list().single().ruleCount)
        // The refreshed profile is the active one, so the live rules follow it.
        assertEquals(listOf("old", "new"), SagerDatabase.rulesDao.allRules().map { it.name })

        // A different name is a new profile, not activated.
        val other = RoutingProfiles.import(RoutingProfiles.Profile(0L, "Other", active.content).toLink())!!
        assertEquals(2, RoutingProfiles.list().size)
        assertEquals(active.id, RoutingProfiles.activeId)
        assertEquals(listOf("old", "new"), SagerDatabase.rulesDao.allRules().map { it.name })
        assertEquals(1, other.ruleCount)

        // A subscription only refreshes the profile it delivered, never a user's profile of that name.
        // Allocate IDs so earlier group-deletion tests cannot tombstone these sources.
        val source = RoutingProfiles.subscriptionSource(SagerDatabase.groupDao.createGroup(ProxyGroup()))
        val provided = RoutingProfiles.import(RoutingProfiles.Profile(0L, "Provider", active.content).toLink(), source)!!
        assertTrue("Subscription source was already deleted: $source", provided.id > 0L)
        assertEquals(3, RoutingProfiles.list().size)
        assertEquals(source, RoutingProfiles.list().first { it.id == provided.id }.source)
        assertEquals(2, RoutingProfiles.list().first { it.id == active.id }.ruleCount)
        assertEquals(provided.id, RoutingProfiles.import(RoutingProfiles.Profile(0L, "Provider", newer).toLink(), source)!!.id)
        assertEquals(3, RoutingProfiles.list().size)
        assertEquals(2, RoutingProfiles.list().first { it.id == provided.id }.ruleCount)
        // A renamed delivery still refreshes the same profile; another subscription gets its own.
        assertEquals(provided.id, RoutingProfiles.import(RoutingProfiles.Profile(0L, "Renamed", active.content).toLink(), source)!!.id)
        assertEquals("Renamed", RoutingProfiles.list().first { it.id == provided.id }.name)
        val otherSource = RoutingProfiles.subscriptionSource(SagerDatabase.groupDao.createGroup(ProxyGroup()))
        RoutingProfiles.import(RoutingProfiles.Profile(0L, "Renamed", active.content).toLink(), otherSource)
        assertEquals(4, RoutingProfiles.list().size)
        // Replacement lookup mirrors store: user imports by name, subscriptions by source.
        assertEquals(active.id, RoutingProfiles.replacementFor(RoutingProfiles.Profile(0L, "Provider", newer))!!.id)
        assertEquals(provided.id, RoutingProfiles.replacementFor(RoutingProfiles.Profile(0L, "Whatever", newer, source))!!.id)
        assertNull(RoutingProfiles.replacementFor(RoutingProfiles.Profile(0L, "Unknown", newer)))
        // A deleted subscription takes its profiles along; the user's stay.
        RoutingProfiles.deleteBySource(source)
        assertEquals(3, RoutingProfiles.list().size)
        assertTrue(RoutingProfiles.list().none { it.source == source })
        assertTrue(RoutingProfiles.list().any { it.id == active.id } && RoutingProfiles.list().any { it.id == other.id })
        // A late store from the deleted subscription's update is dropped.
        RoutingProfiles.import(RoutingProfiles.Profile(0L, "Late", newer).toLink(), source)
        assertEquals(3, RoutingProfiles.list().size)

        // Exporting the active profile carries edits made since the last switch; others export as stored.
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "newest", domains = "newest.example")))
        assertEquals(3, RoutingProfiles.exportable(active.id)!!.ruleCount)
        assertEquals(3, RoutingProfiles.list().first { it.id == active.id }.ruleCount)
        assertEquals(1, RoutingProfiles.exportable(other.id)!!.ruleCount)
        assertNull(RoutingProfiles.exportable(999L))
    }

    // Room rejects main-thread access in debug builds; Robolectric runs tests on it.
    private fun offMain(block: suspend () -> Unit) = runBlocking { withContext(Dispatchers.IO) { block() } }
}
