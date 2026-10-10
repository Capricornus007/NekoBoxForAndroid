package io.nekohasekai.sagernet.api

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import androidx.work.Configuration
import androidx.work.WorkManager
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAppTask

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class AppApiTest {
    private lateinit var service: ApiServiceFixture
    private lateinit var runtime: ApiRuntime
    private lateinit var api: AppApi
    private val logSink = Logs.sink

    @Before
    fun setup() {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Stopped
        val context = RuntimeEnvironment.getApplication()
        Logs.sink = {}
        runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManager.initialize(context, Configuration.Builder().setDefaultProcessName(context.packageName).build())
            WorkManager.getInstance(context)
        }
        service = ApiServiceFixture(context)
        runtime = ApiRuntime(service.context)
        runtime.connect()
        api = AppApi(context, runtime)
    }

    @After
    fun cleanup() {
        if (::runtime.isInitialized) runtime.close()
        Logs.sink = logSink
    }

    private fun response(command: String, params: JSONObject = JSONObject()) = runBlocking {
        withContext(Dispatchers.IO) { api.response(command, params.toString()) }
    }
    private fun result(command: String, params: JSONObject = JSONObject()): Any {
        val response = response(command, params)
        assertFalse("$command: $response", response.has("error"))
        return response.get("result")
    }
    private fun objectResult(command: String, params: JSONObject = JSONObject()) = result(command, params) as JSONObject
    private fun params(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
    private fun group() = objectResult("groups.create", params("name" to "API fixture")).getLong("id")
    private fun profile(groupId: Long) = objectResult("profiles.create", params("groupId" to groupId, "type" to 0, "configuration" to params("name" to "fixture", "serverAddress" to "192.0.2.1", "serverPort" to 1080)))
    private fun error(code: String, command: String, params: JSONObject) {
        assertEquals(command, code, response(command, params).getJSONObject("error").getString("code"))
    }

    @Test
    fun catalogRejectsUnknownAndMissingParametersForEveryCommand() {
        val catalog = objectResult("api.describe").getJSONObject("commands")
        assertTrue(catalog.length() >= 60)
        catalog.keysSet().forEach { command ->
            val definition = catalog.getJSONObject(command)
            val properties = definition.getJSONObject("parameters").getJSONObject("properties")
            val example = JSONObject()
            val required = definition.getJSONArray("required")
            for (index in 0 until required.length()) {
                val key = required.getString(index)
                example.put(
                    key,
                    when (properties.getJSONObject(key).getString("type")) {
                        "integer" -> 1
                        "boolean" -> false
                        "object" -> JSONObject()
                        "array" -> JSONArray()
                        else -> "example"
                    },
                )
            }
            properties.keysSet().forEach { key ->
                error("invalid_parameters", command, JSONObject(example.toString()).put(key, JSONObject.NULL))
            }
            error("invalid_parameters", command, params("unexpected" to true))
            if (catalog.getJSONObject(command).getJSONArray("required").length() > 0) {
                error("invalid_parameters", command, JSONObject())
            }
        }
        val openApi = objectResult("api.openapi")
        assertEquals("3.1.0", openApi.getString("openapi"))
        val paths = openApi.getJSONObject("paths")
        assertEquals(catalog.keysSet().map { "/v1/$it" }.toSet() + "/v1", paths.keysSet())
        catalog.keysSet().forEach { name ->
            val schema = paths.getJSONObject("/v1/$name").getJSONObject("post").getJSONObject("requestBody")
                .getJSONObject("content").getJSONObject("application/json").getJSONObject("schema")
            assertFalse(schema.getBoolean("additionalProperties"))
            assertEquals(catalog.getJSONObject(name).getJSONObject("parameters").toString(), schema.toString())
        }
        error("unknown_operation", "unknown.command", JSONObject())
        val malformed = runBlocking { withContext(Dispatchers.IO) { api.response("profiles.get", "{") } }
        assertEquals("invalid_parameters", malformed.getJSONObject("error").getString("code"))
    }

    @Test
    fun profileCrudPreservesCountersAndRequiresExplicitSecretExport() {
        val group = group()
        val id = profile(group).getLong("id")
        objectResult("profiles.update", params("id" to id, "configuration" to params("password" to "fictional-secret", "name" to "updated")))
        val safe = objectResult("profiles.get", params("id" to id))
        assertEquals("updated", safe.getString("name"))
        assertFalse(safe.has("configuration"))
        assertFalse(objectResult("profiles.list").toString().contains("fictional-secret"))
        val full = objectResult("profiles.get", params("id" to id, "includeSecrets" to true))
        assertEquals("fictional-secret", full.getJSONObject("configuration").getString("password"))
        error("secrets_required", "profiles.export", params("id" to id, "includeSecrets" to false))
        assertTrue(objectResult("profiles.export", params("id" to id, "includeSecrets" to true)).getString("text").isNotBlank())
        error("invalid_parameters", "profiles.update", params("id" to id, "configuration" to params("serverPort" to "1080")))
        error("invalid_parameters", "profiles.update", params("id" to id, "configuration" to params("serverPort" to 65536)))
        error("invalid_parameters", "profiles.update", params("id" to id, "configuration" to params("name" to "must-not-commit", "finalAddress" to "198.51.100.1")))
        assertEquals("updated", objectResult("profiles.get", params("id" to id)).getString("name"))
        objectResult("profiles.select", params("id" to id))
        assertEquals(id, DataStore.selectedProxy)
        error("confirmation_required", "profiles.delete", params("id" to id, "confirm" to false))
        objectResult("profiles.delete", params("id" to id, "confirm" to true))
        error("not_found", "profiles.get", params("id" to id))
        assertEquals(0L, DataStore.selectedProxy)
    }

    @Test
    fun allRegisteredProtocolsHaveTypedConfigurationAndRoundTripDefaults() {
        val types = result("profiles.types") as JSONArray
        assertEquals(ProtocolRegistry.all.size, types.length())
        ProtocolRegistry.all.forEach { descriptor ->
            val bean = descriptor.beanClass.getDeclaredConstructor().newInstance().apply { initializeDefaultValues() }
            val json = ApiBean.encode(bean)
            val copy = descriptor.beanClass.getDeclaredConstructor().newInstance().apply { initializeDefaultValues() }
            try {
                ApiBean.patch(copy, json)
            } catch (failure: Exception) {
                throw AssertionError(descriptor.beanClass.simpleName, failure)
            }
            assertEquals(descriptor.beanClass.simpleName, json.toString(), ApiBean.encode(copy).toString())
            assertEquals(json.keysSet(), ApiBean.describe(bean).keysSet())
            assertFalse(json.has("finalAddress"))
            assertFalse(json.has("finalPort"))
        }
    }

    @Test
    fun cloningImportAndReorderDoNotOverwriteOriginalProfiles() {
        val group = group()
        val first = profile(group).getLong("id")
        val second = objectResult("profiles.clone", params("id" to first, "groupId" to group, "name" to "copy")).getLong("id")
        assertNotEquals(first, second)
        objectResult("profiles.reorder", params("groupId" to group, "ids" to JSONArray(listOf(second, first))))
        assertEquals(second, objectResult("profiles.list", params("groupId" to group)).getJSONArray("items").getJSONObject(0).getLong("id"))
        error("invalid_parameters", "profiles.reorder", params("groupId" to group, "ids" to JSONArray(listOf(first, first))))
        error("invalid_parameters", "profiles.list", params("offset" to -1))
        error("invalid_parameters", "profiles.list", params("limit" to 1001))
        objectResult("profiles.import", params("groupId" to group, "text" to "socks5://192.0.2.2:1080#Imported"))
        assertEquals(3, objectResult("profiles.list", params("groupId" to group)).getInt("total"))
    }

    @Test
    fun rulesProtectReferencedProfilesAndValidatePatches() {
        val group = group()
        val profile = profile(group).getLong("id")
        val rule = objectResult("rules.create", params("values" to params("domains" to "full:example.invalid", "outbound" to profile))).getLong("id")
        error("referenced", "profiles.delete", params("id" to profile, "confirm" to true))
        error("referenced", "groups.delete", params("id" to group, "confirm" to true))
        error("invalid_parameters", "rules.update", params("id" to rule, "values" to params("enabled" to "false")))
        objectResult("rules.update", params("id" to rule, "values" to params("enabled" to false, "packages" to JSONArray(listOf("org.example.client")))))
        val rules = result("rules.list") as JSONArray
        assertFalse(rules.getJSONObject(0).getBoolean("enabled"))
        objectResult("rules.reorder", params("ids" to JSONArray(listOf(rule))))
        objectResult("rules.delete", params("id" to rule, "confirm" to true))
        objectResult("groups.delete", params("id" to group, "confirm" to true))
        assertEquals(0, objectResult("profiles.list").getInt("total"))
    }

    @Test
    fun groupSchemasAndUpdatesKeepSubscriptionsPrivate() {
        val basic = group()
        val subscription = objectResult("groups.create", params("name" to "subscription", "subscription" to params("link" to "https://example.invalid/subscription/fictional-token")))
        val id = subscription.getLong("id")
        assertFalse(subscription.has("subscription"))
        assertFalse((result("groups.list") as JSONArray).toString().contains("fictional-token"))
        objectResult("groups.update", params("id" to id, "values" to params("subscription" to params("sendDeviceId" to false, "outputFormat" to 3))))
        val full = objectResult("groups.get", params("id" to id, "includeSecrets" to true)).getJSONObject("subscription")
        assertEquals(3, full.getInt("outputFormat"))
        assertFalse(full.getBoolean("sendDeviceId"))
        error("invalid_parameters", "groups.update", params("id" to id, "values" to params("subscription" to params("offeredLink" to "https://example.invalid/other"))))
        error("invalid_parameters", "groups.update", params("id" to id, "values" to params("order" to 3)))
        objectResult("groups.reorder", params("ids" to JSONArray(listOf(id, basic))))
        assertEquals(id, (result("groups.list") as JSONArray).getJSONObject(0).getLong("id"))
        assertTrue(objectResult("subscriptions.describe").has("outputFormat"))
    }

    @Test
    fun settingsValidateWholePatchBeforeWritingAndProtectInternalKeys() {
        val before = objectResult("settings.get").getString(Key.MIXED_PORT)
        error("invalid_parameters", "settings.set", params("values" to params(Key.MIXED_PORT to "2088", Key.SERVICE_MODE to "invalid")))
        assertEquals(before, objectResult("settings.get").getString(Key.MIXED_PORT))
        error("invalid_parameters", "settings.set", params("values" to params(Key.PLUGIN_SIGNER_APPROVALS to JSONArray())))
        error("invalid_parameters", "settings.set", params("values" to params(Key.MIXED_PORT to "65536")))
        error("invalid_parameters", "settings.set", params("values" to params(Key.LOG_LEVEL to 2)))
        error("confirmation_required", "settings.set", params("values" to params(Key.ALLOW_ACCESS to true)))
        objectResult("settings.set", params("values" to params(Key.MIXED_PORT to "2088", Key.BYPASS_MODE to false)))
        assertEquals("2088", objectResult("settings.get").getString(Key.MIXED_PORT))
        assertFalse(DataStore.bypass)
        objectResult("settings.set", params("values" to params("webdavPassword" to "fictional-password")))
        assertTrue(objectResult("settings.get").isNull("webdavPassword"))
        assertEquals("fictional-password", objectResult("settings.get", params("includeSecrets" to true)).getString("webdavPassword"))
    }

    @Test
    fun booleanSettingRemainsTypedAndPersistsAcrossApiHandlers() {
        // A plain boolean preference exposed by the settings API: typed, defaulted, writable and
        // rejected when given the wrong type. bypassMode is a real DataStore boolean, so the same
        // handler round trip is exercised as before.
        val setting = objectResult("settings.describe").getJSONObject(Key.BYPASS_MODE)
        assertEquals("boolean", setting.getString("type"))
        assertTrue(setting.getBoolean("default"))
        assertTrue(objectResult("settings.get").getBoolean(Key.BYPASS_MODE))

        DataStore.serviceState = BaseService.State.Connected
        objectResult("settings.set", params("values" to params(Key.BYPASS_MODE to false)))
        assertFalse(DataStore.bypass)
        error("invalid_parameters", "settings.set", params("values" to params(Key.BYPASS_MODE to "true")))
        api = AppApi(RuntimeEnvironment.getApplication(), runtime)
        assertFalse(objectResult("settings.get").getBoolean(Key.BYPASS_MODE))
        assertEquals(BaseService.State.Connected, DataStore.serviceState)
    }

    @Test
    fun routingProfilesCanBeSavedExportedImportedAndDeleted() {
        objectResult("rules.create", params("values" to params("name" to "rule", "domains" to "full:example.invalid")))
        val first = objectResult("routing.save", params("name" to "first")).getLong("id")
        val exported = objectResult("routing.export", params("id" to first))
        error("confirmation_required", "routing.import", params("text" to exported.getString("link")))
        val imported = objectResult("routing.import", params("text" to exported.getString("link"), "confirm" to true))
        assertEquals(first, imported.getLong("id"))
        val second = objectResult("routing.save", params("name" to "second")).getLong("id")
        objectResult("routing.select", params("id" to first))
        objectResult("routing.rename", params("id" to first, "name" to "renamed"))
        assertEquals(first, objectResult("routing.list").getLong("activeId"))
        objectResult("routing.delete", params("id" to second, "confirm" to true))
        assertEquals(1, objectResult("routing.list").getJSONArray("items").length())
    }

    @Test
    fun malformedAutomationCannotReplaceSavedRules() {
        val id = profile(group()).getLong("id")
        val rule = params("kind" to "WIFI", "action" to "CONNECT", "profileId" to id)
        objectResult("automation.set", params("rules" to JSONArray().put(rule)))
        error("invalid_parameters", "automation.set", params("rules" to JSONArray().put(rule).put(rule)))
        assertEquals(1, objectResult("automation.get").getJSONArray("rules").length())
        error("referenced", "profiles.delete", params("id" to id, "confirm" to true))
    }

    @Test
    fun serviceAndTailscaleRequestsRejectUnavailableStateWithoutSideEffects() {
        val id = profile(group()).getLong("id")
        error("service_stopped", "service.reload", JSONObject())
        runtime.onServiceDisconnected()
        error("service_unavailable", "service.connections", JSONObject())
        error("not_found", "tailscale.status", params("sessionId" to 1))
        error("not_found", "tailscale.result", params("requestId" to 1))
        error("invalid_parameters", "tailscale.ping", params("sessionId" to 1, "peerId" to "peer", "timeoutMs" to 0))
        error("confirmation_required", "tailscale.exit", params("sessionId" to 1, "peerId" to "peer", "expectedSavedSelection" to "", "confirm" to false))
        error("not_found", "jobs.get", params("id" to "missing"))
        runtime.onServiceConnected(service.api)
        DataStore.serviceState = BaseService.State.Connected
        error("service_running", "profiles.delete", params("id" to id, "confirm" to true))
        error("service_running", "service.start", params("id" to id))
        assertEquals(id, objectResult("profiles.get", params("id" to id)).getLong("id"))
    }

    @Test
    fun backupExportAndInspectionRequireAnExplicitSecretRead() {
        profile(group())
        error("secrets_required", "backup.export", params("includeSecrets" to false))
        val content = objectResult("backup.export", params("includeSecrets" to true))
        assertFalse(content.toString().contains("local-api"))
        val inspect = objectResult("backup.inspect", params("content" to content))
        assertEquals(1, inspect.getInt("profiles"))
        assertEquals(1, inspect.getInt("groups"))
        error("confirmation_required", "backup.restore", params("content" to content, "profiles" to true, "rules" to false, "settings" to false, "confirm" to false))
        error("invalid_parameters", "backup.restore", params("content" to params("version" to 2), "profiles" to true, "rules" to false, "settings" to false, "confirm" to true))
        assertEquals(1, objectResult("profiles.list").getInt("total"))
    }

    @Test
    fun unknownAndStoppingServiceStatesCannotPermitDestructiveOperationsOrModeChanges() {
        val id = profile(group()).getLong("id")
        DataStore.serviceState = BaseService.State.Idle
        error("service_unavailable", "profiles.delete", params("id" to id, "confirm" to true))
        error("service_unavailable", "service.start", params("id" to id))
        DataStore.serviceState = BaseService.State.Stopping
        error("service_running", "service.start", params("id" to id))
        error("service_running", "settings.set", params("values" to params(Key.SERVICE_MODE to Key.MODE_VPN)))
        assertEquals(Key.MODE_PROXY, DataStore.serviceMode)
    }

    @Test
    fun tailscaleConfigurationSavesPreserveTheNodeIdentityAndProtectExitSelection() {
        val id = objectResult("profiles.create", params("groupId" to group(), "type" to ProxyEntity.TYPE_TAILSCALE, "configuration" to params("name" to "node"))).getLong("id")
        val identity = ConfigBuilderTestEnv.io { ProfileManager.getProfile(id)!!.uuid }
        objectResult("profiles.update", params("id" to id, "configuration" to params("hostname" to "fixture-node")))
        assertEquals(identity, ConfigBuilderTestEnv.io { ProfileManager.getProfile(id)!!.uuid })
        error("invalid_parameters", "profiles.update", params("id" to id, "configuration" to params("exitNode" to "100.64.0.1")))
        DataStore.serviceState = BaseService.State.Idle
        error("service_unavailable", "profiles.update", params("id" to id, "configuration" to params("hostname" to "blocked-change")))
    }

    @Test
    fun permissionsRemainReadableWithoutARunningVpn() {
        val permissions = objectResult("permissions.get")
        assertEquals(35, permissions.getInt("androidSdk"))
        assertTrue(permissions.has("alwaysOn"))
        assertTrue(permissions.has("lockdown"))
        assertEquals(BaseService.State.Stopped, DataStore.serviceState)
    }

    @Test
    fun backupRestoreKeepsAVerifiedRecoveryCopy() {
        val group = group()
        val id = profile(group).getLong("id")
        val content = objectResult("backup.export", params("includeSecrets" to true))
        objectResult("profiles.update", params("id" to id, "configuration" to params("name" to "before restore")))
        val restored = objectResult("backup.restore", params("content" to content, "profiles" to true, "rules" to true, "settings" to false, "confirm" to true))
        assertTrue(restored.getBoolean("restored"))
        assertEquals("fixture", objectResult("profiles.get", params("id" to id)).getString("name"))
        val recoveryId = restored.getString("recoveryId")
        val saved = objectResult("backup.recovery", params("id" to recoveryId, "includeSecrets" to true))
        assertEquals(1, saved.getJSONArray("profiles").length())
        assertEquals(1, (result("backup.recoveries") as JSONArray).length())
        error("secrets_required", "backup.recovery", params("id" to recoveryId, "includeSecrets" to false))
        error("invalid_parameters", "backup.recovery", params("id" to "../configuration.db", "includeSecrets" to true))
    }

    @Test
    fun chainValidationLoadsSharedDescendantsOnceAndStillRejectsCycles() {
        val graph = mutableMapOf<Long, ProxyEntity>()
        graph[1L] = ProxyEntity(id = 1).putBean(SOCKSBean().apply { initializeDefaultValues() })
        for (id in 2L..32L) {
            graph[id] = ProxyEntity(id = id).putBean(
                ChainBean().apply {
                    proxies = if (id == 2L) mutableListOf(1L) else mutableListOf(id - 1, id - 2)
                    initializeDefaultValues()
                },
            )
        }
        val root = ChainBean().apply {
            proxies = mutableListOf(32L)
            initializeDefaultValues()
        }
        val visits = mutableMapOf<Long, Int>()
        api.validateBean(root, lookup = { id ->
            visits[id] = visits.getOrDefault(id, 0) + 1
            graph.getValue(id)
        })
        assertEquals(graph.keys, visits.keys)
        assertTrue(visits.values.all { it == 1 })
        graph.getValue(2L).chainBean!!.proxies = mutableListOf(32L)
        assertEquals("invalid_parameters", (runCatching { api.validateBean(root, lookup = graph::getValue) }.exceptionOrNull() as ApiFailure).code)
    }

    @Test
    fun chainMutationsRejectFullConfigsButAllowCustomOutbounds() {
        val group = group()
        val hop = profile(group).getLong("id")
        val full = objectResult("profiles.create", params("groupId" to group, "type" to ProxyEntity.TYPE_CONFIG, "configuration" to params("type" to 0, "config" to "{}"))).getLong("id")
        error("invalid_parameters", "profiles.create", params("groupId" to group, "type" to ProxyEntity.TYPE_CHAIN, "configuration" to params("proxies" to JSONArray(listOf(full)))))
        val chain = objectResult("profiles.create", params("groupId" to group, "type" to ProxyEntity.TYPE_CHAIN, "configuration" to params("proxies" to JSONArray(listOf(hop))))).getLong("id")
        error("invalid_parameters", "profiles.update", params("id" to chain, "configuration" to params("proxies" to JSONArray(listOf(full)))))
        assertEquals(listOf(hop), ConfigBuilderTestEnv.io { ProfileManager.getProfile(chain)!!.chainBean!!.proxies })
        val outbound = objectResult("profiles.create", params("groupId" to group, "type" to ProxyEntity.TYPE_CONFIG, "configuration" to params("type" to 1, "config" to """{"type":"direct"}"""))).getLong("id")
        objectResult("profiles.update", params("id" to chain, "configuration" to params("proxies" to JSONArray(listOf(outbound)))))
    }

    @Test
    fun chainValidationRejectsEmptyNestedChains() {
        val empty = ProxyEntity(id = 1L).putBean(ChainBean().apply { initializeDefaultValues() })
        val root = ChainBean().apply {
            proxies = listOf(1L)
            initializeDefaultValues()
        }
        assertEquals("invalid_parameters", (runCatching { api.validateBean(root, lookup = { empty }) }.exceptionOrNull() as ApiFailure).code)
    }

    @Test
    fun replacementHandlerCannotOverlapAnAcceptedMutation() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val listener = object : GroupManager.Listener {
            override suspend fun groupAdd(group: ProxyGroup) {
                if (group.name == "paused") {
                    entered.complete(Unit)
                    release.await()
                }
            }
            override suspend fun groupUpdated(group: ProxyGroup) = Unit
            override suspend fun groupRemoved(groupId: Long) = Unit
            override suspend fun groupUpdated(groupId: Long) = Unit
        }
        GroupManager.addListener(listener)
        try {
            val accepted = async(Dispatchers.IO) { api.response("groups.create", params("name" to "paused").toString()) }
            withContext(Dispatchers.IO) { withTimeout(5000) { entered.await() } }
            val replacement = AppApi(RuntimeEnvironment.getApplication(), runtime)
            val rejected = withContext(Dispatchers.IO) { replacement.response("groups.create", params("name" to "overlap").toString()) }
            assertEquals("busy", rejected.getJSONObject("error").getString("code"))
            assertEquals(1, ConfigBuilderTestEnv.io { SagerDatabase.groupDao.allGroups().size })
            release.complete(Unit)
            assertTrue(accepted.await().has("result"))
            val next = withContext(Dispatchers.IO) { replacement.response("groups.create", params("name" to "after").toString()) }
            assertTrue(next.has("result"))
        } finally {
            release.complete(Unit)
            GroupManager.removeListener(listener)
        }
    }

    @Test
    fun recentAppsSettingAppliesBothValuesWithoutAnActivityResume() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val context = RuntimeEnvironment.getApplication()
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = listOf(ShadowAppTask.newInstance(), ShadowAppTask.newInstance())
            shadowOf(manager).setAppTasks(tasks)
            for (hide in listOf(true, false)) {
                val response = withContext(Dispatchers.IO) {
                    api.response("settings.set", params("values" to params(Key.HIDE_FROM_RECENT_APPS to hide)).toString())
                }
                assertTrue(response.toString(), response.has("result"))
                assertEquals(hide, DataStore.hideFromRecentApps)
                tasks.forEach { assertEquals(hide, shadowOf(it).isExcludedFromRecents) }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun unknownIdentifiersAndInvalidTypesReturnStableErrors() {
        listOf("profiles.get", "groups.get", "profiles.select").forEach { command ->
            error("invalid_parameters", command, params("id" to "1"))
            error("invalid_parameters", command, params("id" to 1.5))
            error("invalid_parameters", command, params("id" to -1))
            error("not_found", command, params("id" to 98765))
        }
    }
}
