package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        val imported = RoutingProfiles.import(original.toExportJson().toString())
        assertNotNull(imported)
        assertEquals("Original", imported!!.name)
        assertEquals(1, imported.ruleCount)
        assertEquals(2, RoutingProfiles.list().size)

        assertNull(RoutingProfiles.import("""{"rules": []}"""))
        assertNull(RoutingProfiles.import("not json"))

        RoutingProfiles.delete(original.id)
        assertEquals(0L, RoutingProfiles.activeId)
        assertEquals(listOf(imported.id), RoutingProfiles.list().map { it.id })
    }

    // Room rejects main-thread access in debug builds; Robolectric runs tests on it.
    private fun offMain(block: suspend () -> Unit) = runBlocking { withContext(Dispatchers.IO) { block() } }
}
