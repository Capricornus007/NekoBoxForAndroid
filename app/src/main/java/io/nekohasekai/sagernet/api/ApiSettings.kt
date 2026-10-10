package io.nekohasekai.sagernet.api

import android.content.Context
import android.content.Intent
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.LocationSpoofing
import io.nekohasekai.sagernet.bg.LocationTurnOffReceiver
import io.nekohasekai.sagernet.bg.NetworkAutomation
import io.nekohasekai.sagernet.bg.parseMockCoordinates
import io.nekohasekai.sagernet.database.DEFAULT_HTTP_PROXY_BYPASS
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ui.applyHideFromRecentApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser

internal class ApiSettings(private val context: Context, private val serviceState: () -> BaseService.State) {
    private data class Setting(val type: String, val default: Any, val values: List<String> = emptyList())

    private val settings by lazy {
        val result = linkedMapOf<String, Setting>()
        context.resources.getXml(R.xml.global_preferences).use { xml ->
            while (xml.next() != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType != XmlPullParser.START_TAG) continue
                val namespace = "http://schemas.android.com/apk/res-auto"
                val key = xml.getAttributeValue(namespace, "key") ?: continue
                val tag = xml.name.substringAfterLast('.')
                if (tag in setOf("Preference", "PreferenceScreen", "PreferenceCategory")) continue
                val default = xml.getAttributeValue(namespace, "defaultValue")
                val type = when (tag) {
                    "SwitchPreference" -> "boolean"
                    "ThemePickerPreference" -> "integer"
                    else -> "string"
                }
                val valuesId = xml.getAttributeResourceValue(namespace, "entryValues", 0)
                val values = if (valuesId != 0 && tag != "MTUPreference") context.resources.getStringArray(valuesId).toList() else emptyList()
                result[key] = Setting(
                    type,
                    when (type) {
                        "boolean" -> default == "true"
                        "integer" -> 0
                        else -> default.orEmpty()
                    },
                    values,
                )
            }
        }
        // These controls live outside the global preference screen.
        result[Key.ENABLE_DNS_ROUTING] = Setting("boolean", false)
        result[Key.MIXED_PORT] = Setting("string", DataStore.mixedPort.toString())
        result[Key.HTTP_PROXY_BYPASS] = Setting("string", DEFAULT_HTTP_PROXY_BYPASS)
        result[Key.BYPASS_MODE] = Setting("boolean", true)
        result[Key.INDIVIDUAL] = Setting("string", "")
        result[Key.GLOBAL_MODE] = Setting("boolean", false)
        result[Key.GROUP_LAYOUT_MODE] = Setting("string", "0", listOf("0", "1"))
        result[Key.CONNECTION_TEST_TIMEOUT] = Setting("integer", 3000)
        result["connectionTestConcurrent"] = Setting("integer", 5)
        result[Key.LOG_BUF_SIZE] = Setting("integer", 0)
        result["webdavServer"] = Setting("string", "")
        result["webdavUsername"] = Setting("string", "")
        result["webdavPassword"] = Setting("string", "")
        result["webdavPath"] = Setting("string", "NekoBox")
        result["yacdURL"] = Setting("string", "http://127.0.0.1:9090/ui")
        result
    }

    fun describe() = JSONObject().apply {
        settings.forEach { (key, setting) ->
            put(key, JSONObject().put("type", setting.type).put("default", setting.default).put("values", JSONArray(setting.values)))
        }
    }

    fun get(includeSecrets: Boolean) = JSONObject().apply {
        val store = DataStore.configurationStore
        settings.forEach { (key, setting) ->
            if (!includeSecrets && key.startsWith("webdav")) {
                put(key, JSONObject.NULL)
            } else {
                put(
                    key,
                    when (setting.type) {
                        "boolean" -> store.getBoolean(key, setting.default as Boolean)
                        "integer" -> store.getInt(key, setting.default as Int)
                        else -> store.getString(key, setting.default as String)
                    },
                )
            }
        }
    }

    suspend fun patch(changes: JSONObject, confirm: Boolean) {
        requireApi(changes.length() > 0, "values must not be empty")
        changes.keysSet().forEach { key -> validate(key, changes.get(key), confirm) }
        if (changes.has(Key.SERVICE_MODE)) {
            val state = serviceState()
            if (state == BaseService.State.Idle) reject("service_unavailable", "The service connection is not ready")
            if (state != BaseService.State.Stopped) reject("service_running", "Stop the service before changing serviceMode")
        }
        val store = DataStore.configurationStore
        changes.keysSet().forEach { key ->
            when (settings.getValue(key).type) {
                "boolean" -> store.putBoolean(key, changes.getBoolean(key))
                "integer" -> store.putInt(key, changes.getInt(key))
                else -> store.putString(key, changes.getString(key))
            }
        }
        store.awaitWrites()
        if (changes.has(Key.HIDE_FROM_RECENT_APPS)) {
            withContext(Dispatchers.Main) { context.applyHideFromRecentApps(changes.getBoolean(Key.HIDE_FROM_RECENT_APPS)) }
        }
        if (changes.has(Key.NETWORK_AUTOMATION)) NetworkAutomation.onSwitched(context, changes.getBoolean(Key.NETWORK_AUTOMATION))
        if (changes.has(Key.PERSIST_ACROSS_REBOOT)) BootReceiver.enabled = BootReceiver.wanted
        if (changes.has(Key.GPS_SPOOFING) && !changes.getBoolean(Key.GPS_SPOOFING)) {
            context.sendBroadcast(Intent(context, LocationTurnOffReceiver::class.java))
        }
        store.awaitWrites()
    }

    private fun validate(key: String, value: Any, confirm: Boolean) {
        val setting = settings[key] ?: reject("invalid_parameters", "Unknown or protected setting")
        when (setting.type) {
            "boolean" -> requireApi(value is Boolean, "$key must be a boolean")
            "integer" -> requireApi(value is Int, "$key must be a 32-bit integer")
            else -> requireApi(value is String && value.length <= 1_048_576, "$key must be a string")
        }
        if (setting.values.isNotEmpty()) requireApi(value in setting.values, "$key is not an allowed choice")
        val unsafeEnables = setOf(Key.ALLOW_ACCESS, Key.APPEND_HTTP_PROXY, Key.GLOBAL_ALLOW_INSECURE, Key.ALLOW_INSECURE_ON_REQUEST, Key.GPS_SPOOFING)
        if (key in unsafeEnables && value == true && !confirm) reject("confirmation_required", "Enabling $key requires confirm=true")
        when (key) {
            Key.MIXED_PORT -> requireApi((value as String).toIntOrNull() in 1024..65535, "mixedPort must be 1024..65535")

            Key.MTU -> requireApi((value as String).toIntOrNull() in 576..65535, "mtu must be 576..65535")

            Key.APP_THEME -> requireApi(value as Int in 0..30, "Unknown theme")

            Key.CONNECTION_TEST_TIMEOUT -> requireApi(value as Int in 100..60_000, "connectionTestTimeout must be 100..60000")

            "connectionTestConcurrent" -> requireApi(value as Int in 1..32, "connectionTestConcurrent must be 1..32")

            Key.LOG_BUF_SIZE -> requireApi(value as Int in 0..16_384, "logBufSize must be 0..16384")

            Key.RULES_UPDATE_INTERVAL -> requireApi((value as String).toIntOrNull() in 0..8760, "rulesUpdateInterval must be 0..8760")

            Key.GPS_COORDINATES -> requireApi(runCatching { parseMockCoordinates(value as String) }.isSuccess, "Invalid coordinates")

            Key.GPS_LOOKUP_URL -> {
                val url = (value as String).toHttpUrlOrNull()
                requireApi(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() && url.fragment == null, "A credential-free HTTPS URL is required")
            }

            Key.GPS_SPOOFING -> if (value == true) {
                if (!LocationSpoofing.supported(context) || !LocationSpoofing.mockLocationAllowed(context) ||
                    !LocationSpoofing.notificationsAllowed(context) || LocationSpoofing.mayRemain(context)
                ) {
                    reject("permission_required", "Mock-location authorization and notifications are required, with no stale simulated location")
                }
            }

            Key.GLOBAL_CUSTOM_CONFIG -> if ((value as String).isNotBlank()) {
                requireApi(runCatching { JSONObject(value) }.isSuccess, "globalCustomConfig must contain a JSON object")
            }
        }
    }
}
