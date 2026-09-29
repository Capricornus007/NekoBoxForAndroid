package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ui.BackupFormatV2
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

    class Profile(val id: Long, var name: String, var content: JSONObject) {
        val ruleCount: Int get() = content.optJSONArray("rules")?.length() ?: 0

        fun toJson(): JSONObject = JSONObject().put("id", id).put("name", name).put("content", content)

        /** Export form: self-describing so a file can be told apart from other JSON. */
        fun toExportJson(): JSONObject = JSONObject()
            .put("routingProfile", FORMAT)
            .put("name", name)
            .put("content", content)

        companion object {
            fun fromJson(json: JSONObject) = Profile(
                json.getLong("id"),
                json.getString("name"),
                json.getJSONObject("content"),
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

    /** Snapshot of the live rules and profile settings. */
    suspend fun captureLive(): JSONObject {
        DataStore.configurationStore.awaitWrites()
        val settings = PublicDatabase.kvPairDao.all().filter { it.key in SETTING_KEYS }
        return JSONObject()
            .put("rules", BackupFormatV2.encodeRules(SagerDatabase.rulesDao.allRules()))
            .put("settings", BackupFormatV2.encodeSettings(settings))
    }

    /** Replace the live rules and profile settings with [content]. Keys absent in it return to defaults. */
    suspend fun applyLive(content: JSONObject) {
        val rules = BackupFormatV2.decodeRules(content.optJSONArray("rules") ?: JSONArray())
        val settings = BackupFormatV2.decodeSettings(content.optJSONArray("settings") ?: JSONArray())
            .filter { it.key in SETTING_KEYS }
            .associateBy { it.key }
        val store = DataStore.configurationStore
        for (key in SETTING_KEYS) {
            val pair = settings[key]
            when {
                pair == null -> store.remove(key)
                pair.valueType == KeyValuePair.TYPE_BOOLEAN -> store.putBoolean(key, pair.boolean!!)
                pair.valueType == KeyValuePair.TYPE_STRING -> store.putString(key, pair.string)
                pair.valueType == KeyValuePair.TYPE_STRING_SET -> store.putStringSet(key, pair.stringSet?.toMutableSet())
                pair.valueType == KeyValuePair.TYPE_FLOAT -> store.putFloat(key, pair.float!!)
                else -> pair.long?.let { store.putLong(key, it) } ?: store.remove(key)
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
    suspend fun saveLiveAs(name: String): Profile {
        val profiles = list()
        val profile = Profile((profiles.maxOfOrNull { it.id } ?: 0L) + 1, name, captureLive())
        save(profiles + profile)
        activeId = profile.id
        return profile
    }

    /** Persist the live state into the active profile, if there is one. */
    suspend fun syncActive() {
        val profiles = list()
        val active = profiles.firstOrNull { it.id == activeId } ?: return
        active.content = captureLive()
        save(profiles)
    }

    suspend fun switchTo(id: Long) {
        val target = list().firstOrNull { it.id == id } ?: return
        if (target.id == activeId) return
        syncActive()
        applyLive(target.content)
        activeId = target.id
    }

    fun rename(id: Long, name: String) {
        save(list().onEach { if (it.id == id) it.name = name })
    }

    fun delete(id: Long) {
        save(list().filterNot { it.id == id })
        if (activeId == id) activeId = 0L
    }

    /** Add an exported profile without applying it. Returns null when the JSON is not a profile. */
    fun import(text: String): Profile? {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return null
        if (json.optInt("routingProfile", 0) != FORMAT) return null
        val content = json.optJSONObject("content") ?: return null
        val profiles = list()
        val profile = Profile(
            (profiles.maxOfOrNull { it.id } ?: 0L) + 1,
            json.optString("name").ifBlank { "Imported" },
            content,
        )
        save(profiles + profile)
        return profile
    }
}
