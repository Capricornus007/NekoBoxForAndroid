package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.BackupFormatV2
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject

/**
 * Named routing profiles: DNS settings, app rules and domain/IP rules as one switchable unit.
 *
 * The live state stays where it always was (the rules table and the settings keys below), so
 * nothing about config building changes. A profile is a snapshot of that state stored as JSON in
 * the settings store. Switching saves the live state into the active profile first, then loads
 * the target, so edits made while a profile is active are kept. Settings backups include the
 * store, so they carry profiles too.
 */
object RoutingProfiles {

    const val FORMAT = 1

    /** Deep link prefix; the rest is the export JSON in base64 (either alphabet). */
    const val LINK_PREFIX = "sn://routing/"

    /** Settings keys that belong to a routing profile. Everything else stays global. */
    val SETTING_KEYS = listOf(
        Key.REMOTE_DNS,
        Key.DIRECT_DNS,
        "domain_strategy_for_remote",
        "domain_strategy_for_direct",
        "domain_strategy_for_server",
        Key.ENABLE_DNS_ROUTING,
        Key.ENABLE_FAKEDNS,
        Key.DNS_HOSTS,
        Key.PROXY_APPS,
        Key.BYPASS_MODE,
        Key.INDIVIDUAL,
        Key.BYPASS_LAN,
        Key.BYPASS_LAN_IN_CORE,
        Key.TRAFFIC_SNIFFING,
        Key.RESOLVE_DESTINATION,
        Key.IPV6_MODE,
        Key.RULES_PROVIDER,
        Key.RULES_GEOSITE_URL,
        Key.RULES_GEOIP_URL,
    )

    /**
     * [source] marks who owns a profile: "" for the user's own, or a subscription tag for profiles
     * delivered through the `routing` subscription key, so a provider only ever refreshes its own.
     * [notes] lists what an imported provider profile asked for that it does not apply; they are
     * reported on import and not stored.
     */
    class Profile(
        val id: Long,
        var name: String,
        var content: JSONObject,
        val source: String = "",
        val notes: List<String> = emptyList(),
    ) {
        val ruleCount: Int get() = content.optJSONArray("rules")?.length() ?: 0

        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("content", content).put("source", source)

        /** Export form: self-describing so a file can be told apart from other JSON. */
        fun toExportJson(): JSONObject = JSONObject()
            .put("routingProfile", FORMAT)
            .put("name", name)
            .put("content", content)

        fun toLink(): String = LINK_PREFIX + Util.b64EncodeUrlSafe(toExportJson().toString())

        companion object {
            fun fromJson(json: JSONObject) = Profile(
                json.getLong("id"),
                json.getString("name"),
                json.getJSONObject("content"),
                json.optString("source"),
            )
        }
    }

    var activeId: Long
        get() = DataStore.configurationStore.getLong(Key.ROUTING_PROFILE_ACTIVE) ?: 0L
        private set(value) = DataStore.configurationStore.putLong(Key.ROUTING_PROFILE_ACTIVE, value)

    fun list(): List<Profile> {
        val raw = DataStore.configurationStore.getString(Key.ROUTING_PROFILES) ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            runCatching { Profile.fromJson(array.getJSONObject(index)) }.getOrNull()
        }
    }

    private fun save(profiles: List<Profile>) {
        DataStore.configurationStore.putString(
            Key.ROUTING_PROFILES,
            JSONArray().apply { profiles.forEach { put(it.toJson()) } }.toString(),
        )
    }

    // Every mutation is a read-modify-write of the whole list. Concurrent subscription updates
    // and a subscription delete racing one of them go through this lock; the main-thread
    // rename/delete below are the only writers outside it and never run concurrently with each
    // other.
    private val listLock = Any()

    // Serializes every capture or apply of the live state (switch, save, sync, refresh of the
    // active profile), so a subscription refresh landing during a switch cannot mark one profile
    // active with another's rules or overwrite saved content with a half-applied live state.
    private val applyLock = Mutex()

    private fun <T> mutate(block: (MutableList<Profile>) -> T): T = synchronized(listLock) {
        val profiles = list().toMutableList()
        val result = block(profiles)
        save(profiles)
        result
    }

    /** Snapshot of the live rules and profile settings. */
    suspend fun captureLive(): JSONObject {
        DataStore.configurationStore.awaitWrites()
        val settings = PublicDatabase.kvPairDao.all().filter { it.key in SETTING_KEYS }
        return JSONObject()
            .put("rules", BackupFormatV2.encodeRules(SagerDatabase.rulesDao.allRules()))
            .put("settings", BackupFormatV2.encodeSettings(settings))
    }

    /**
     * Typed value of every profile setting in [settings], in [SETTING_KEYS] order; null for a key that is
     * absent or of an unknown type. A truncated value throws here, so callers see it before any write.
     */
    private fun settingValues(settings: JSONArray): List<Pair<String, Any?>> {
        val pairs = BackupFormatV2.decodeSettings(settings).associateBy { it.key }
        return SETTING_KEYS.map { key -> key to pairs[key]?.let { it.boolean ?: it.float ?: it.long ?: it.string ?: it.stringSet } }
    }

    /**
     * Replace the live rules and profile settings with [content]. Keys absent in its settings return
     * to defaults. Without [includeSettings], or without a settings array, the settings stay as they
     * are and only the rules change: settings belong to the user, never to a provider.
     */
    suspend fun applyLive(content: JSONObject, includeSettings: Boolean = true) {
        val rules = BackupFormatV2.decodeRules(content.optJSONArray("rules") ?: JSONArray())
        val settings = content.optJSONArray("settings")?.takeIf { includeSettings }?.let(::settingValues).orEmpty()
        val store = DataStore.configurationStore
        for ((key, value) in settings) {
            when (value) {
                null -> store.remove(key)
                is Boolean -> store.putBoolean(key, value)
                is Float -> store.putFloat(key, value)
                is Long -> store.putLong(key, value)
                is String -> store.putString(key, value)
                is Set<*> -> store.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
            }
        }
        store.awaitWrites()
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(rules.map { it.apply { id = 0L } })
        }
        // The route list and the running service re-read the rules table on this callback.
        ProfileManager.ruleIterator { onCleared() }
    }

    /** Store the live state as a new profile and make it the active one. */
    suspend fun saveLiveAs(name: String): Profile = applyLock.withLock {
        val content = captureLive()
        val profile = mutate { profiles ->
            Profile((profiles.maxOfOrNull { it.id } ?: 0L) + 1, name, content).also { profiles += it }
        }
        activeId = profile.id
        profile
    }

    /** Persist the live state into the active profile, if there is one. */
    suspend fun syncActive() = applyLock.withLock { syncActiveLocked() }

    private suspend fun syncActiveLocked() {
        val id = activeId
        val active = list().firstOrNull { it.id == id } ?: return
        val content = captureLive()
        // A provider's or rules-only profile never takes the settings along, so switching back to
        // it later cannot restore DNS or other settings over the user's.
        if (active.source.isNotEmpty() || !active.content.has("settings")) content.remove("settings")
        mutate { profiles -> profiles.firstOrNull { it.id == id }?.content = content }
    }

    /** The profile as the user sees it: the active one carries the live edits made since the last switch. */
    suspend fun exportable(id: Long): Profile? {
        if (id == activeId) syncActive()
        return list().firstOrNull { it.id == id }
    }

    suspend fun switchTo(id: Long) {
        applyLock.withLock {
            val target = list().firstOrNull { it.id == id } ?: return
            if (target.id == activeId) return
            syncActiveLocked()
            applyLive(target.content, includeSettings = target.source.isEmpty())
            activeId = target.id
        }
    }

    fun rename(id: Long, name: String) {
        mutate { profiles -> profiles.forEach { if (it.id == id) it.name = name } }
    }

    fun delete(id: Long) {
        mutate { profiles -> profiles.removeAll { it.id == id } }
        if (activeId == id) activeId = 0L
    }

    fun subscriptionSource(groupId: Long) = "subscription:$groupId"

    // Routing links: this app's sn://routing/, Happ's and INCY's <scheme>://routing/add|onadd|off,
    // and INCY's scheme-less ://routing/ header form.
    private val ROUTING_LINK = Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*)?://routing/")
    private val PROVIDER_LINK = Regex("^(?:[A-Za-z][A-Za-z0-9+.-]*)?://routing/(add|onadd)/(\\S+)$")

    fun isRoutingLink(text: String) = ROUTING_LINK.containsMatchIn(text.trim())

    /** A provider's request to turn routing off (`routing: off`, `<scheme>://routing/off`), which is not applied. */
    fun isDisableRequest(text: String) = text.trim().let { it.equals("off", ignoreCase = true) || (isRoutingLink(it) && it.endsWith("://routing/off")) }

    /**
     * Reads export JSON, an [LINK_PREFIX] link, or a Happ/INCY routing profile (link, base64 or
     * JSON) into an unsaved profile (id 0), or null.
     */
    fun parse(text: String, source: String = ""): Profile? {
        val trimmed = text.trim()
        PROVIDER_LINK.find(trimmed)?.let { match ->
            val json = decodeJsonObject(match.groupValues[2]) ?: return null
            return ProviderRouting.translate(json, source, activationRequested = match.groupValues[1] == "onadd")
        }
        val json = if (trimmed.startsWith(LINK_PREFIX)) {
            decodeJsonObject(trimmed.removePrefix(LINK_PREFIX))
        } else {
            runCatching { JSONObject(trimmed) }.getOrNull() ?: decodeJsonObject(trimmed)
        } ?: return null
        if (!json.has("routingProfile") && ProviderRouting.isProviderProfile(json)) {
            return ProviderRouting.translate(json, source, activationRequested = false)
        }
        if (json.optInt("routingProfile", 0) != FORMAT) return null
        val content = json.optJSONObject("content") ?: return null
        // Decode here, typed values included, so malformed content is refused before it can replace a
        // stored profile or the live rules and settings. Settings may be absent (a rules-only profile).
        val decodes = runCatching {
            BackupFormatV2.decodeRules(content.getJSONArray("rules"))
            if (content.has("settings")) settingValues(content.getJSONArray("settings"))
        }.isSuccess
        if (!decodes) return null
        val name = json.optString("name").ifBlank { "Imported" }
        // A subscription delivers rules only; DNS, apps and the other settings stay the user's.
        if (source.isNotEmpty() && content.has("settings")) {
            val keys = content.optJSONArray("settings")?.let { settings -> (0 until settings.length()).mapNotNull { settings.optJSONObject(it)?.optString("key")?.ifEmpty { null } } }
            val rules = JSONObject().put("rules", content.getJSONArray("rules"))
            return Profile(0L, name, rules, source, listOf(keys?.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "settings"))
        }
        return Profile(0L, name, content, source)
    }

    private fun decodeJsonObject(base64: String): JSONObject? = runCatching {
        JSONObject(String(Util.b64Decode(base64), Charsets.UTF_8))
    }.getOrNull()

    /**
     * Store an exported profile or link. A user's import refreshes the user's profile of the same
     * name; a subscription owns at most one profile and refreshes it whatever it is called, so a
     * link delivered repeatedly never piles up copies. When the refreshed profile is the active
     * one the live rules follow: activating a provider's profile is the consent to track it, and
     * the next switch would otherwise overwrite the refreshed content with the stale live state.
     * Nothing is activated here. Returns null when [text] is not a profile.
     */
    suspend fun import(text: String, source: String = ""): Profile? = parse(text, source)?.let { store(it) }

    /** The stored profile [candidate] would refresh, or null when it would be added. */
    fun replacementFor(candidate: Profile): Profile? = list().firstOrNull {
        it.source == candidate.source && (candidate.source.isNotEmpty() || it.name == candidate.name)
    }

    // Sources whose subscription is gone; a store that finishes after the delete is dropped instead
    // of leaving an orphan. Group ids are never reused, so this grows by one entry per deletion.
    private val deletedSources = HashSet<String>()

    suspend fun store(candidate: Profile): Profile = applyLock.withLock {
        val (stored, refreshedActive) = mutate { profiles ->
            if (candidate.source.isNotEmpty() && candidate.source in deletedSources) return@mutate candidate to false
            val existing = profiles.firstOrNull {
                it.source == candidate.source && (candidate.source.isNotEmpty() || it.name == candidate.name)
            }
            if (existing == null) {
                val profile = Profile((profiles.maxOfOrNull { it.id } ?: 0L) + 1, candidate.name, candidate.content, candidate.source)
                profiles += profile
                profile to false
            } else {
                existing.name = candidate.name
                existing.content = candidate.content
                existing to (existing.id == activeId)
            }
        }
        if (refreshedActive) applyLive(stored.content, includeSettings = stored.source.isEmpty())
        stored
    }

    /** Removes the profiles a deleted subscription delivered; an active one is deactivated, live state stays. */
    fun deleteBySource(source: String) {
        if (source.isEmpty()) return
        val removed = mutate { profiles ->
            deletedSources += source
            val removed = profiles.filter { it.source == source }
            profiles.removeAll(removed)
            removed
        }
        if (removed.any { it.id == activeId }) activeId = 0L
    }
}

/**
 * Translates a Happ or INCY routing profile into rules of this app. Only the domain and IP lists
 * carry over, as block, proxy and direct rules in that order of precedence. DNS servers and hosts,
 * FakeDNS, the domain strategy, geo file URLs, GlobalProxy=false and an activation request are not
 * applied: they stay with the user's own settings and are reported as notes, together with list
 * entries the rules cannot express.
 */
internal object ProviderRouting {
    private class RuleList(val field: String, val ip: Boolean, val outbound: Long)

    private val LISTS = listOf(
        RuleList("BlockSites", ip = false, outbound = -2L),
        RuleList("BlockIp", ip = true, outbound = -2L),
        RuleList("ProxySites", ip = false, outbound = 0L),
        RuleList("ProxyIp", ip = true, outbound = 0L),
        RuleList("DirectSites", ip = false, outbound = -1L),
        RuleList("DirectIp", ip = true, outbound = -1L),
    )

    /** Fields that describe the profile or the provider's geo file updates. */
    private val DESCRIPTIVE = setOf("Name", "LastUpdated", "useChunkFiles")
    private val DOMAIN_PREFIXES = listOf("domain:", "full:", "regexp:", "keyword:")
    private val IP = Regex("^[0-9A-Fa-f:.]+(/[0-9]{1,3})?$")

    fun isProviderProfile(json: JSONObject) = json.has("GlobalProxy") || LISTS.any { json.has(it.field) }

    /** The rules of [json] as a rules-only profile, or null when none of its entries translate. */
    fun translate(json: JSONObject, source: String, activationRequested: Boolean): RoutingProfiles.Profile? {
        val notes = mutableListOf<String>()
        val rules = JSONArray()
        for (list in LISTS) {
            val values = json.optJSONArray(list.field) ?: continue
            val entries = (0 until values.length()).map { values.optString(it).trim() }.filter { it.isNotEmpty() }
            val translated = entries.mapNotNull { if (list.ip) ipEntry(it) else domainEntry(it) }
            if (translated.size < entries.size) notes += "${list.field} (${entries.size - translated.size})"
            if (translated.isEmpty()) continue
            val rule = RuleEntity(name = list.field, userOrder = rules.length() + 1L, enabled = true, outbound = list.outbound)
            if (list.ip) rule.ip = translated.joinToString("\n") else rule.domains = translated.joinToString("\n")
            rules.put(BackupFormatV2.encodeRule(rule))
        }
        for (key in json.keys()) {
            if (key in DESCRIPTIVE || LISTS.any { it.field == key }) continue
            // Unmatched traffic goes through the proxy here anyway.
            if (key == "GlobalProxy" && json.optString(key).equals("true", ignoreCase = true)) continue
            notes += key
        }
        if (activationRequested) notes += "onadd"
        if (rules.length() == 0) return null
        val name = json.optString("Name").trim().ifEmpty { "Imported" }
        return RoutingProfiles.Profile(0L, name, JSONObject().put("rules", rules), source, notes)
    }

    // Xray matches a bare domain value anywhere in the name; here a bare value is a suffix.
    private fun domainEntry(value: String): String? = when {
        value.startsWith("geosite:") -> value.takeUnless { it.contains('@') || it.contains('!') }
        DOMAIN_PREFIXES.any { value.startsWith(it) } -> value
        value.contains(':') -> null
        else -> "keyword:$value"
    }

    private fun ipEntry(value: String): String? = when {
        value.startsWith("geoip:") -> value.takeUnless { it.contains('!') }
        IP.matches(value) -> value
        else -> null
    }
}
