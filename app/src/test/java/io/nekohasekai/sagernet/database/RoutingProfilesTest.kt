package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.ui.BackupFormatV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

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

    @Test
    fun providerRoutingProfilesTranslateRulesOnlyAndReportWhatTheyCannotApply() = offMain {
        val happ = JSONObject()
            .put("Name", "RU direct")
            .put("GlobalProxy", "false")
            .put("RemoteDNSType", "DoH")
            .put("RemoteDNSDomain", "https://dns.example/dns-query")
            .put("FakeDNS", "true")
            .put("DirectSites", JSONArray(listOf("geosite:ru", "domain:example.ru", "yandex", "ext:custom.dat:ru", "geosite:google@cn")))
            .put("DirectIp", JSONArray(listOf("geoip:ru", "10.0.0.0/8", "geoip:!ru")))
            .put("ProxySites", JSONArray(listOf("full:blocked.example")))
            .put("BlockSites", JSONArray(listOf("geosite:category-ads-all")))
            .put("BlockIp", JSONArray())
            .put("LastUpdated", "1700000000")
        val encoded = Base64.getEncoder().encodeToString(happ.toString().toByteArray())

        val profile = RoutingProfiles.parse("happ://routing/onadd/$encoded")!!
        assertEquals("RU direct", profile.name)
        assertFalse(profile.content.has("settings"))
        val rules = BackupFormatV2.decodeRules(profile.content.getJSONArray("rules"))
        // Block first, then the provider's proxy exceptions, then direct traffic.
        assertEquals(listOf("BlockSites", "ProxySites", "DirectSites", "DirectIp"), rules.map { it.name })
        assertEquals(listOf(-2L, 0L, -1L, -1L), rules.map { it.outbound })
        assertTrue(rules.all { it.enabled })
        // A bare Xray domain matches anywhere in the name, as a keyword does here.
        assertEquals("geosite:ru\ndomain:example.ru\nkeyword:yandex", rules[2].domains)
        assertEquals("geoip:ru\n10.0.0.0/8", rules[3].ip)
        assertEquals(listOf("DirectSites (2)", "DirectIp (1)", "GlobalProxy", "RemoteDNSType", "RemoteDNSDomain", "FakeDNS", "onadd"), profile.notes)

        // INCY's header forms: bare base64 and a link without scheme. Only onadd asks for activation.
        assertEquals(profile.content.toString(), RoutingProfiles.parse(encoded)!!.content.toString())
        assertEquals(profile.content.toString(), RoutingProfiles.parse("://routing/add/$encoded")!!.content.toString())
        assertFalse("onadd" in RoutingProfiles.parse("incy://routing/add/$encoded")!!.notes)
        assertTrue(RoutingProfiles.isDisableRequest("happ://routing/off"))
        assertTrue(RoutingProfiles.isDisableRequest(" OFF "))
        assertFalse(RoutingProfiles.isDisableRequest("happ://routing/add/$encoded"))
        assertNull(RoutingProfiles.parse("happ://routing/off"))
        val untranslatable = JSONObject().put("Name", "empty").put("DirectSites", JSONArray(listOf("ext:x.dat:y")))
        assertNull(RoutingProfiles.parse("happ://routing/add/" + Base64.getEncoder().encodeToString(untranslatable.toString().toByteArray())))

        // Stored, it is never activated; switched to, it replaces rules and leaves DNS settings alone.
        DataStore.remoteDns = "https://mine.example/dns-query"
        DataStore.enableFakeDns = false
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "mine", domains = "mine.example")))
        val mine = RoutingProfiles.saveLiveAs("Mine")
        val source = RoutingProfiles.subscriptionSource(SagerDatabase.groupDao.createGroup(ProxyGroup()))
        val provided = RoutingProfiles.import("happ://routing/onadd/$encoded", source)!!
        assertEquals(mine.id, RoutingProfiles.activeId)
        assertEquals(listOf("mine"), SagerDatabase.rulesDao.allRules().map { it.name })
        RoutingProfiles.switchTo(provided.id)
        assertEquals(listOf("BlockSites", "ProxySites", "DirectSites", "DirectIp"), SagerDatabase.rulesDao.allRules().map { it.name })
        assertEquals("https://mine.example/dns-query", DataStore.remoteDns)
        assertEquals(false, DataStore.enableFakeDns)

        // It stays rules-only through sync, export, switching away and back, and provider refreshes.
        DataStore.remoteDns = "https://edited.example/dns-query"
        assertFalse(RoutingProfiles.exportable(provided.id)!!.content.has("settings"))
        RoutingProfiles.switchTo(mine.id)
        assertEquals(listOf("mine"), SagerDatabase.rulesDao.allRules().map { it.name })
        assertEquals("https://mine.example/dns-query", DataStore.remoteDns)
        DataStore.remoteDns = "https://later.example/dns-query"
        DataStore.enableFakeDns = true
        RoutingProfiles.switchTo(provided.id)
        assertEquals(4, SagerDatabase.rulesDao.allRules().size)
        assertEquals("https://later.example/dns-query", DataStore.remoteDns)
        assertEquals(true, DataStore.enableFakeDns)
        val refreshed = Base64.getEncoder().encodeToString(happ.put("ProxySites", JSONArray(listOf("full:new.example"))).toString().toByteArray())
        RoutingProfiles.import("happ://routing/add/$refreshed", source)
        assertEquals("full:new.example", SagerDatabase.rulesDao.allRules().single { it.name == "ProxySites" }.domains)
        assertEquals("https://later.example/dns-query", DataStore.remoteDns)
        assertFalse(RoutingProfiles.list().single { it.id == provided.id }.content.has("settings"))
        // Back on the user's profile, its own settings apply, including the edits made while it was active.
        RoutingProfiles.switchTo(mine.id)
        assertFalse(RoutingProfiles.list().single { it.id == provided.id }.content.has("settings"))
        assertEquals("https://later.example/dns-query", DataStore.remoteDns)
        assertEquals(listOf("mine"), SagerDatabase.rulesDao.allRules().map { it.name })
    }

    @Test
    fun providerProfilesNeverChangeSettingsEvenWhenTheyCarryThem() = offMain {
        DataStore.remoteDns = "https://mine.example/dns-query"
        DataStore.enableFakeDns = false
        DataStore.proxyApps = false
        val geosite = DataStore.rulesGeositeUrl
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(name = "mine", domains = "mine.example")))
        val mine = RoutingProfiles.saveLiveAs("Mine")
        val source = RoutingProfiles.subscriptionSource(SagerDatabase.groupDao.createGroup(ProxyGroup()))
        val happ = JSONObject().put("Name", "Provider").put("DirectSites", JSONArray(listOf("geosite:cn")))
        val provided = RoutingProfiles.import("happ://routing/add/" + Base64.getEncoder().encodeToString(happ.toString().toByteArray()), source)!!
        RoutingProfiles.switchTo(provided.id)
        assertEquals("https://mine.example/dns-query", DataStore.remoteDns)

        val rules = BackupFormatV2.encodeRules(listOf(RuleEntity(name = "provider-rule", domains = "provider.example", enabled = true)))
        fun sn(settings: JSONArray) = RoutingProfiles.Profile(0L, "Provider", JSONObject().put("rules", rules).put("settings", settings)).toLink()

        // The same subscription then sends this app's own format with settings:[], which would reset every setting.
        val reset = RoutingProfiles.parse(sn(JSONArray()), source)!!
        assertFalse(reset.content.has("settings"))
        assertEquals(listOf("settings"), reset.notes)
        RoutingProfiles.store(reset)
        assertEquals("https://mine.example/dns-query", DataStore.remoteDns)
        assertEquals(false, DataStore.enableFakeDns)
        assertEquals(listOf("provider-rule"), SagerDatabase.rulesDao.allRules().map { it.name })

        // And with DNS, app and geo file overrides.
        val overrides = BackupFormatV2.encodeSettings(
            listOf(
                KeyValuePair(Key.REMOTE_DNS).put("https://provider.example/dns-query"),
                KeyValuePair(Key.PROXY_APPS).put(true),
                KeyValuePair(Key.RULES_GEOSITE_URL).put("https://provider.example/geosite.db"),
            ),
        )
        assertEquals(listOf("${Key.REMOTE_DNS}, ${Key.PROXY_APPS}, ${Key.RULES_GEOSITE_URL}"), RoutingProfiles.parse(sn(overrides), source)!!.notes)
        RoutingProfiles.import(sn(overrides), source)
        assertEquals("https://mine.example/dns-query", DataStore.remoteDns)
        assertEquals(false, DataStore.proxyApps)
        assertEquals(geosite, DataStore.rulesGeositeUrl)
        assertFalse(RoutingProfiles.list().single { it.id == provided.id }.content.has("settings"))
        assertEquals(provided.id, RoutingProfiles.activeId)

        // A provider profile stored with settings before this rule existed applies its rules only.
        RoutingProfiles.switchTo(mine.id)
        assertEquals(listOf("mine"), SagerDatabase.rulesDao.allRules().map { it.name })
        val otherSource = RoutingProfiles.subscriptionSource(SagerDatabase.groupDao.createGroup(ProxyGroup()))
        val legacy = RoutingProfiles.store(RoutingProfiles.Profile(0L, "Legacy", JSONObject().put("rules", rules).put("settings", overrides), otherSource))
        RoutingProfiles.switchTo(legacy.id)
        assertEquals("https://mine.example/dns-query", DataStore.remoteDns)
        assertEquals(false, DataStore.proxyApps)
        assertEquals(listOf("provider-rule"), SagerDatabase.rulesDao.allRules().map { it.name })

        // A user's own import still restores the whole snapshot.
        RoutingProfiles.switchTo(mine.id)
        val own = RoutingProfiles.import(sn(overrides))!!
        RoutingProfiles.switchTo(own.id)
        assertEquals("https://provider.example/dns-query", DataStore.remoteDns)
        assertEquals(true, DataStore.proxyApps)
    }

    // Room rejects main-thread access in debug builds; Robolectric runs tests on it.
    private fun offMain(block: suspend () -> Unit) = runBlocking { withContext(Dispatchers.IO) { block() } }
}
