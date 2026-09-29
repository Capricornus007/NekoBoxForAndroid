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
     */
    class Profile(val id: Long, var name: String, var content: JSONObject, val source: String = "") {
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

    /** Replace the live rules and profile settings with [content]. Keys absent in it return to defaults. */
    suspend fun applyLive(content: JSONObject) {
        val rules = BackupFormatV2.decodeRules(content.optJSONArray("rules") ?: JSONArray())
        val settings = settingValues(content.optJSONArray("settings") ?: JSONArray())
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
        if (list().none { it.id == id }) return
        val content = captureLive()
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
            applyLive(target.content)
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

    /** Reads export JSON or an [LINK_PREFIX] link into an unsaved profile (id 0), or null. */
    fun parse(text: String, source: String = ""): Profile? {
        val trimmed = text.trim()
        val jsonText = if (trimmed.startsWith(LINK_PREFIX)) {
            runCatching { String(Util.b64Decode(trimmed.removePrefix(LINK_PREFIX)), Charsets.UTF_8) }.getOrNull() ?: return null
        } else {
            trimmed
        }
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        if (json.optInt("routingProfile", 0) != FORMAT) return null
        val content = json.optJSONObject("content") ?: return null
        // Decode here, typed values included, so malformed content is refused before it can replace a
        // stored profile or the live rules and settings.
        val decodes = runCatching {
            BackupFormatV2.decodeRules(content.getJSONArray("rules"))
            settingValues(content.getJSONArray("settings"))
        }.isSuccess
        if (!decodes) return null
        return Profile(0L, json.optString("name").ifBlank { "Imported" }, content, source)
    }

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
        if (refreshedActive) applyLive(stored.content)
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
