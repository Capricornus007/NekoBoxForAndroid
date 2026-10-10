package io.nekohasekai.sagernet.api

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.LocationSpoofing
import io.nekohasekai.sagernet.bg.NetworkAutomation
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.internal.isUsableChainHop
import io.nekohasekai.sagernet.fmt.tailscale.profilesForBackup
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readTextBounded
import io.nekohasekai.sagernet.ui.BackupFormatV2
import io.nekohasekai.sagernet.ui.BackupRestoreOperations
import io.nekohasekai.sagernet.ui.DatabaseBackupRestoreOperations
import io.nekohasekai.sagernet.ui.restoreBackup
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import libcore.Libcore
import libcore.LocalAPIHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal class AppApi(private val context: Context, private val runtime: ApiRuntime) : LocalAPIHandler {
    private companion object {
        // Listener replacement must not let a new token overlap an accepted command.
        val commandGate = Mutex()
    }

    private class Command(
        val required: Set<String>,
        val optional: Set<String>,
        val description: String,
        val run: suspend (JSONObject) -> Any,
    )
    private val commands = linkedMapOf<String, Command>()
    private val settings = ApiSettings(context, runtime::serviceState)

    private fun command(name: String, description: String, required: String = "", optional: String = "", run: suspend (JSONObject) -> Any) {
        fun keys(value: String) = value.split(' ').filter { it.isNotEmpty() }.toSet()
        check(commands.put(name, Command(keys(required), keys(optional), description, run)) == null)
    }

    override fun call(operation: String, parameters: String): String = runBlocking(runtime.scope.coroutineContext) {
        response(operation, parameters).toString()
    }

    suspend fun response(operation: String, parameters: String): JSONObject {
        if (!commandGate.tryLock()) return JSONObject().put("error", JSONObject().put("code", "busy").put("message", "Another command is executing"))
        return try {
            val command = commands[operation] ?: reject("unknown_operation", "Unknown operation")
            val params = try {
                JSONObject(parameters)
            } catch (_: Exception) {
                reject("invalid_parameters", "A JSON object is required")
            }
            requireApi(params.keysSet().containsAll(command.required), "Missing required parameters")
            requireApi(params.keysSet().all { it in command.required || it in command.optional }, "Unknown parameters")
            val properties = parameterSchema(operation, command).getJSONObject("properties")
            params.keysSet().forEach { name ->
                val value = params.get(name)
                val type = properties.getJSONObject(name).getString("type")
                val valid = when (type) {
                    "integer" -> value is Int || value is Long
                    "boolean" -> value is Boolean
                    "string" -> value is String
                    "object" -> value is JSONObject
                    "array" -> value is JSONArray
                    else -> false
                }
                requireApi(valid, "$name must be $type")
            }
            if (runtime.busy && operation !in setOf("api.describe", "api.openapi", "app.status", "permissions.get", "jobs.get", "service.stop", "tailscale.cancel", "tailscale.close", "ui.status")) {
                reject("busy", "Wait for the background operation to finish")
            }
            JSONObject().put("result", withTimeout(90_000) { command.run(params) })
        } catch (e: ApiFailure) {
            JSONObject().put("error", JSONObject().put("code", e.code).put("message", e.message))
        } catch (_: CancellationException) {
            JSONObject().put("error", JSONObject().put("code", "cancelled"))
        } catch (_: Exception) {
            // Parser and native errors may contain credentials from the supplied configuration.
            JSONObject().put("error", JSONObject().put("code", "operation_failed"))
        } finally {
            commandGate.unlock()
        }
    }

    init {
        command("api.describe", "List commands and their accepted parameters") {
            JSONObject().put("version", 1).put(
                "commands",
                JSONObject().apply {
                    commands.forEach { (name, command) ->
                        put(
                            name,
                            JSONObject().put("description", command.description)
                                .put("required", JSONArray(command.required.toList())).put("optional", JSONArray(command.optional.toList()))
                                .put("parameters", parameterSchema(name, command)),
                        )
                    }
                },
            )
        }
        command("api.openapi", "Read the OpenAPI 3.1 description generated from the command registry") { openApi() }
        command("app.status", "Read application and VPN state without changing it") {
            JSONObject().put("package", context.packageName).put("version", BuildConfig.VERSION_NAME)
                .put("apiVersion", 1).put("debug", BuildConfig.DEBUG).put("serviceState", runtime.serviceState().name)
                .put("selectedProfileId", DataStore.selectedProxy).put("currentProfileId", DataStore.currentProfile)
                .put("serviceMode", DataStore.serviceMode).put("busy", runtime.busy)
                .put("lastError", runtime.lastError ?: JSONObject.NULL)
        }
        command("permissions.get", "Report Android permissions and unattended-start blockers") { permissions() }
        command("app.restart", "Restart the app to apply process-level settings; requires a stopped service", "confirm") {
            it.confirm()
            requireStopped()
            DataStore.configurationStore.awaitWrites()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ io.nekohasekai.sagernet.ui.triggerFullRestart(context) }, 500)
            JSONObject().put("accepted", true)
        }
        command("location.clear", "Remove simulated location through the existing cleanup receiver", "confirm") {
            it.confirm()
            if (!LocationSpoofing.mockLocationAllowed(context)) reject("permission_required", "Mock-location authorization is required")
            withTimeout(10_000) {
                suspendCancellableCoroutine { continuation ->
                    val result = object : android.content.BroadcastReceiver() {
                        override fun onReceive(context: Context, intent: Intent) {
                            if (continuation.isActive) continuation.resumeWith(Result.success(JSONObject().put("removed", resultCode == android.app.Activity.RESULT_OK)))
                        }
                    }
                    context.sendOrderedBroadcast(LocationSpoofing.confirmedRemoval(context), null, result, android.os.Handler(android.os.Looper.getMainLooper()), android.app.Activity.RESULT_CANCELED, null, null)
                }
            }
        }
        command("jobs.get", "Poll a background operation, retained until 16 newer jobs exist", "id") { runtime.jobStatus(it.text("id")) }
        profileCommands()
        groupCommands()
        ruleCommands()
        routingCommands()
        serviceCommands()
        command("settings.describe", "Read writable setting names, types, defaults and choices") { settings.describe() }
        command("settings.get", "Read settings, with WebDAV credentials omitted by default", optional = "includeSecrets") { settings.get(it.flag("includeSecrets")) }
        command("settings.set", "Validate and save a settings patch; reload or restart is explicit", "values", "confirm") {
            val changes = it.objectValue("values")
            settings.patch(changes, it.flag("confirm"))
            JSONObject().put("changed", JSONArray(changes.keysSet().toList())).put("reloadRequired", true)
                .put("restartRequired", changes.keysSet().any { key -> key in setOf(Key.APP_THEME, Key.NIGHT_THEME, Key.APP_LANGUAGE, Key.LOG_LEVEL, Key.LOG_BUF_SIZE) })
        }
        automationCommands()
        tailscaleCommands()
        fileCommands()
        command("ui.status", "Report available app screens and foreground state") { ApiUi.status() }
        command("ui.navigate", "Navigate to a named app screen without opening a browser", "screen") { ApiUi.navigate(it.text("screen")) }
        command("ui.recreate", "Apply saved appearance settings and recreate the current screen") { ApiUi.recreate() }
        val webdav = ApiWebDav()
        command("webdav.list", "List backups on the configured HTTPS WebDAV server") { runtime.job { webdav.list() } }
        command("webdav.upload", "Upload a full backup to the configured WebDAV server", "includeSecrets confirm") {
            it.secrets()
            it.confirm()
            runtime.job { webdav.upload(backup()) }
        }
        command("webdav.download", "Download backup content without restoring it", "name includeSecrets") {
            it.secrets()
            val name = it.text("name")
            runtime.job { webdav.download(name) }
        }
        command("plugins.inspect", "Read installed rejected plugin identities and signing fingerprints", "name") {
            val name = it.text("name")
            requireApi(io.nekohasekai.sagernet.fmt.PluginEntry.find(name) != null, "Unknown plugin")
            jsonArray(
                moe.matsuri.nb4a.plugin.Plugins.inspectRejectedPlugins(name).map { rejected ->
                    JSONObject().put("package", rejected.packageName).put("fingerprints", rejected.currentFingerprints?.let { JSONArray(it.sorted()) } ?: JSONObject.NULL)
                },
            )
        }
        command("plugins.approve", "Approve the exact current signer of one unambiguous installed plugin", "name package fingerprints confirm") {
            it.confirm()
            val name = it.text("name")
            requireApi(io.nekohasekai.sagernet.fmt.PluginEntry.find(name) != null, "Unknown plugin")
            val packageName = it.text("package")
            val array = it.arrayValue("fingerprints")
            val fingerprints = (0 until array.length()).map { index ->
                array.get(index) as? String ?: reject("invalid_parameters", "Fingerprints must be strings")
            }.toSet()
            val current = moe.matsuri.nb4a.plugin.Plugins.inspectRejectedPlugins(name).singleOrNull()
                ?: reject("conflict", "The plugin is absent, already approved or ambiguous")
            requireApi(current.packageName == packageName && current.currentFingerprints == fingerprints, "Plugin identity changed")
            val identity = io.nekohasekai.sagernet.plugin.PluginTrust.approvalIdentity(packageName, fingerprints)
                ?: reject("invalid_parameters", "Invalid plugin identity")
            DataStore.approvePluginSigner(identity)
            JSONObject().put("approved", true)
        }
        command("backup.export", "Export the existing version-2 backup format, without local API credentials", "includeSecrets") {
            it.secrets()
            backup()
        }
        command("backup.recoveries", "List private recovery backups created before API restores") {
            val directory = File(context.noBackupFilesDir, "api-recovery")
            jsonArray(
                directory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }.map { file ->
                    JSONObject().put("id", file.nameWithoutExtension).put("bytes", file.length()).put("modifiedAt", file.lastModified())
                },
            )
        }
        command("backup.recovery", "Read a private recovery backup without restoring it", "id includeSecrets") {
            it.secrets()
            val id = it.text("id")
            requireApi(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false), "Invalid recovery id")
            val file = File(File(context.noBackupFilesDir, "api-recovery"), "$id.json")
            if (!file.isFile) reject("not_found", "Recovery backup does not exist")
            JSONObject(file.inputStream().use { input -> input.readTextBounded(16L * 1024 * 1024) })
        }
        command("backup.restore", "Restore selected sections after saving a private recovery backup; requires a stopped service", "content profiles rules settings confirm") {
            it.confirm()
            requireStopped()
            val content = it.objectValue("content")
            requireApi(content.integer("version") == BackupFormatV2.VERSION.toLong(), "Only version-2 backups are supported")
            val profiles = it.flag("profiles")
            val rules = it.flag("rules")
            val settings = it.flag("settings")
            requireApi(profiles || rules || settings, "Select at least one backup section")
            requireApi((!profiles || (content.has("profiles") && content.has("groups"))) && (!rules || content.has("rules")) && (!settings || content.has("settings")), "A selected section is missing")
            val validate = object : BackupRestoreOperations {
                override suspend fun replaceProfiles(profiles: List<ProxyEntity>, groups: List<ProxyGroup>) {
                    requireApi(profiles.all { profile -> profile.id > 0 && groups.any { group -> group.id == profile.groupId } }, "Invalid profile/group references")
                    requireApi(profiles.map { it.id }.distinct().size == profiles.size && groups.map { it.id }.distinct().size == groups.size, "Duplicate backup identities")
                    profiles.forEach { profile -> KryoConverters.serialize(profile.requireBean()) }
                }
                override suspend fun replaceRules(rules: List<RuleEntity>) = Unit
                override suspend fun replaceSettings(settings: List<KeyValuePair>) {
                    settings.forEach { pair -> pair.boolean ?: pair.float ?: pair.long ?: pair.string ?: pair.stringSet }
                }
            }
            restoreBackup(content, profiles, rules, settings, validate)
            val directory = File(context.noBackupFilesDir, "api-recovery").apply { check(isDirectory || mkdirs()) }
            val recoveryId = UUID.randomUUID().toString()
            val recovery = File(directory, "$recoveryId.json")
            val bytes = backup().toString().toByteArray(Charsets.UTF_8)
            recovery.outputStream().use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(recovery.readBytes().contentEquals(bytes))
            try {
                restoreBackup(content, profiles, rules, settings, DatabaseBackupRestoreOperations)
            } catch (_: Exception) {
                reject("restore_failed", "Restore did not finish; read backup.recovery with id=$recoveryId before retrying")
            }
            ProfileManager.ruleIterator { onCleared() }
            SagerDatabase.groupDao.allGroups().forEach { group -> GroupManager.postReload(group.id) }
            JSONObject().put("restored", true).put("recoveryId", recoveryId).put("restartRequired", true)
        }
        command("backup.inspect", "Decode and validate backup sections without restoring or replacing data", "content") {
            val content = it.objectValue("content")
            requireApi(content.integer("version") == BackupFormatV2.VERSION.toLong(), "Only version-2 backups are supported")
            JSONObject().put("profiles", content.optJSONArray("profiles")?.let(BackupFormatV2::decodeProfiles)?.size ?: 0)
                .put("groups", content.optJSONArray("groups")?.let(BackupFormatV2::decodeGroups)?.size ?: 0)
                .put("rules", content.optJSONArray("rules")?.let(BackupFormatV2::decodeRules)?.size ?: 0)
                .put("settings", content.optJSONArray("settings")?.let(BackupFormatV2::decodeSettings)?.size ?: 0)
        }
    }

    private fun parameterSchema(operation: String, command: Command) = JSONObject()
        .put("type", "object").put("additionalProperties", false)
        .put("required", JSONArray(command.required.toList()))
        .put(
            "properties",
            JSONObject().apply {
                (command.required + command.optional).forEach { name ->
                    val type = when {
                        name == "id" && operation in setOf("jobs.get", "backup.recovery") -> "string"
                        name == "content" && operation == "assets.import" -> "string"
                        name == "rules" && operation == "backup.restore" -> "boolean"
                        name in setOf("id", "groupId", "type", "offset", "limit", "sessionId", "requestId", "timeoutMs", "maxBytes") -> "integer"
                        name in setOf("confirm", "includeSecrets", "apply", "includeClosed", "check", "profiles", "settings") -> "boolean"
                        name in setOf("ids", "rules", "fingerprints") -> "array"
                        name in setOf("values", "configuration", "content", "subscription") -> "object"
                        else -> "string"
                    }
                    put(
                        name,
                        JSONObject().put("type", type).apply {
                            if (type == "array") {
                                put(
                                    "items",
                                    JSONObject().put(
                                        "type",
                                        when (name) {
                                            "ids" -> "integer"
                                            "fingerprints" -> "string"
                                            else -> "object"
                                        },
                                    ),
                                )
                            }
                        },
                    )
                }
            },
        )

    private fun openApi() = JSONObject().put("openapi", "3.1.0")
        .put("info", JSONObject().put("title", "NekoBox local control API").put("version", "1"))
        .put("servers", JSONArray().put(JSONObject().put("url", "/")))
        .put("components", JSONObject().put("securitySchemes", JSONObject().put("bearer", JSONObject().put("type", "http").put("scheme", "bearer"))))
        .put("security", JSONArray().put(JSONObject().put("bearer", JSONArray())))
        .put(
            "paths",
            JSONObject().apply {
                put(
                    "/v1",
                    JSONObject().put(
                        "get",
                        JSONObject().put("operationId", "discover_api").put("summary", "Read the command catalog")
                            .put(
                                "responses",
                                JSONObject().put("200", JSONObject().put("description", "Command catalog envelope"))
                                    .put("401", JSONObject().put("description", "Missing or invalid bearer token")),
                            ),
                    ),
                )
                commands.forEach { (name, command) ->
                    put(
                        "/v1/$name",
                        JSONObject().put(
                            "post",
                            JSONObject().put("operationId", name.replace('.', '_')).put("summary", command.description)
                                .put("requestBody", JSONObject().put("required", true).put("content", JSONObject().put("application/json", JSONObject().put("schema", parameterSchema(name, command)))))
                                .put(
                                    "responses",
                                    JSONObject().put("200", JSONObject().put("description", "A result or application error envelope"))
                                        .put("401", JSONObject().put("description", "Missing or invalid bearer token"))
                                        .put("403", JSONObject().put("description", "Browser request or invalid Host"))
                                        .put("413", JSONObject().put("description", "Request exceeds 2 MiB"))
                                        .put("503", JSONObject().put("description", "Another command is executing")),
                                ),
                        ),
                    )
                }
            },
        )

    private fun profileCommands() {
        command("profiles.types", "Read configuration schemas and defaults for supported protocols") {
            jsonArray(
                ProtocolRegistry.all.map { descriptor ->
                    val bean = descriptor.beanClass.getDeclaredConstructor().newInstance().apply { initializeDefaultValues() }
                    JSONObject().put("type", descriptor.type).put("name", descriptor.beanClass.simpleName.removeSuffix("Bean"))
                        .put("fields", ApiBean.describe(bean))
                },
            )
        }
        command("profiles.list", "List profile metadata without credentials", optional = "groupId offset limit") {
            val profiles = if (it.has("groupId")) SagerDatabase.proxyDao.getByGroup(group(it.positiveId("groupId")).id) else SagerDatabase.proxyDao.getAll()
            val offset = if (it.has("offset")) it.integer("offset") else 0L
            val limit = if (it.has("limit")) it.integer("limit") else 200L
            requireApi(offset in 0..Int.MAX_VALUE && limit in 1..1000, "Invalid pagination")
            JSONObject().put("total", profiles.size).put("items", jsonArray(profiles.drop(offset.toInt()).take(limit.toInt()).map { profileJson(it, false) }))
        }
        command("profiles.get", "Read a profile; includeSecrets includes its protocol configuration", "id", "includeSecrets") { profileJson(profile(it.positiveId()), it.flag("includeSecrets")) }
        command("profiles.create", "Create a protocol profile in an existing basic group", "groupId type configuration") {
            val target = group(it.positiveId("groupId"))
            requireApi(target.type == GroupType.BASIC, "Import into a basic group")
            val type = it.integer("type")
            requireApi(type in 0..Int.MAX_VALUE, "Invalid protocol type")
            val descriptor = ProtocolRegistry.forType(type.toInt()) ?: reject("invalid_parameters", "Unknown protocol type")
            val bean = descriptor.beanClass.getDeclaredConstructor().newInstance().apply { initializeDefaultValues() }
            ApiBean.patch(bean, it.objectValue("configuration"))
            validateBean(bean)
            profileJson(ProfileManager.createProfile(target.id, bean), false)
        }
        command("profiles.update", "Patch protocol fields without replacing identity or traffic counters", "id configuration") {
            val profile = profile(it.positiveId())
            requireApi(profile.canBuild(), "Archived profiles are read-only")
            val bean = ApiBean.patch(profile.requireBean().clone(), it.objectValue("configuration"))
            validateBean(bean, profile.id)
            if (bean is io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean) {
                if (bean != profile.requireBean()) requireStopped()
                requireApi(bean.exitNode == profile.tailscaleBean?.exitNode, "Use tailscale.exit to change the saved exit node")
                TailscaleProfileStore.saveEditor(profile.id, profile.uuid, profile.tailscaleBean?.exitNode.orEmpty(), bean, false)
            } else {
                // Keep counters committed while the configuration was decoded.
                SagerDatabase.instance.runInTransaction {
                    val current = profile(profile.id)
                    requireApi(current.type == profile.type, "Profile type changed; read it again before updating")
                    current.putBean(bean)
                    SagerDatabase.proxyDao.updateProxy(current)
                }
            }
            ProfileManager.postUpdate(profile.id)
            profileJson(profile(profile.id), false)
        }
        command("profiles.clone", "Copy a profile into a basic group with a new identity", "id groupId", "name") {
            val source = profile(it.positiveId())
            requireApi(source.canBuild(), "Archived profiles cannot be cloned")
            val target = group(it.positiveId("groupId"))
            requireApi(target.type == GroupType.BASIC, "Copy into a basic group")
            val bean = source.requireBean().clone()
            if (it.has("name")) bean.name = it.text("name")
            profileJson(ProfileManager.createProfile(target.id, bean), false)
        }
        command("profiles.import", "Parse share links and import their profiles as one batch", "groupId text") {
            val target = group(it.positiveId("groupId"))
            requireApi(target.type == GroupType.BASIC, "Import into a basic group")
            val beans = parseProxies(it.text("text"))
            requireApi(beans.isNotEmpty() && beans.size <= 1000, "Import must contain 1..1000 profiles")
            beans.forEach { bean -> validateBean(bean) }
            ProfileManager.createProfiles(target.id, beans)
            JSONObject().put("created", beans.size).put("groupId", target.id)
        }
        command("profiles.export", "Export a profile share link or generated configuration", "id includeSecrets", "format") {
            it.secrets()
            val profile = profile(it.positiveId())
            when (if (it.has("format")) it.text("format") else "link") {
                "link" -> {
                    requireApi(profile.haveLink(), "This profile has no share link")
                    JSONObject().put("text", profile.toStdLink())
                }

                "config" -> profile.exportConfig().let { (content, name) -> JSONObject().put("text", content).put("name", name) }

                else -> reject("invalid_parameters", "format must be link or config")
            }
        }
        command("profiles.select", "Select a profile, optionally switching the running service", "id", "apply") {
            val profile = profile(it.positiveId())
            requireApi(profile.canBuild(), "Archived profiles cannot run")
            val old = DataStore.selectedProxy
            DataStore.selectedProxy = profile.id
            DataStore.configurationStore.awaitWrites()
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(profile.id, true)
            if (it.flag("apply") && runtime.serviceState().started) SagerNet.reloadService(profile.id)
            JSONObject().put("selectedProfileId", profile.id)
        }
        command("profiles.delete", "Delete one unreferenced profile while the service is stopped", "id confirm") {
            it.confirm()
            requireStopped()
            val profile = profile(it.positiveId())
            ensureUnreferenced(setOf(profile.id))
            ProfileManager.deleteProfile(profile.groupId, profile.id)
            JSONObject().put("deleted", true)
        }
        command("profiles.reorder", "Set a complete, duplicate-free ordering for a group's profiles", "groupId ids") {
            val group = group(it.positiveId("groupId"))
            val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
            val ids = orderedIds(it.arrayValue("ids"), profiles.map { profile -> profile.id })
            SagerDatabase.instance.runInTransaction {
                orderedIds(it.arrayValue("ids"), SagerDatabase.proxyDao.getIdsByGroup(group.id))
                ids.forEachIndexed { index, id -> check(SagerDatabase.proxyDao.updateOrder(id, index + 1L) == 1) }
            }
            GroupManager.postReload(group.id)
            JSONObject().put("updated", ids.size)
        }
    }

    private fun groupCommands() {
        command("groups.list", "List groups without subscription URLs or credentials", optional = "includeSecrets") {
            jsonArray(SagerDatabase.groupDao.allGroups().map { group -> groupJson(group, it.flag("includeSecrets")) })
        }
        command("groups.get", "Read a group's settings and optional subscription configuration", "id", "includeSecrets") { groupJson(group(it.positiveId()), it.flag("includeSecrets")) }
        command("groups.create", "Create a basic group or subscription group", "name", "subscription") {
            val group = ProxyGroup(name = it.text("name"))
            if (it.has("subscription")) {
                group.type = GroupType.SUBSCRIPTION
                group.subscription = subscription(null, it.objectValue("subscription"))
            }
            groupJson(GroupManager.createGroup(group), false)
        }
        command("groups.update", "Patch group behavior or subscription settings", "id values") {
            val group = group(it.positiveId())
            val values = it.objectValue("values")
            val allowed = setOf("name", "order", "isSelector", "autoSelect", "frontProxy", "landingProxy", "subscription")
            requireApi(values.keysSet().all { key -> key in allowed }, "Unknown group field")
            if (values.has("name")) group.name = values.text("name")
            if (values.has("order")) group.order = values.integer("order").also { order -> requireApi(order in 0..2, "Invalid group order") }.toInt()
            if (values.has("isSelector")) group.isSelector = values.flag("isSelector")
            if (values.has("autoSelect")) group.autoSelect = values.flag("autoSelect")
            for (key in listOf("frontProxy", "landingProxy")) {
                if (values.has(key)) {
                    val id = values.integer(key)
                    requireApi(id == -1L || (id > 0 && profile(id).groupId != group.id), "Group proxy must be outside this group")
                    if (key == "frontProxy") group.frontProxy = id else group.landingProxy = id
                }
            }
            if (values.has("subscription")) {
                requireApi(group.type == GroupType.SUBSCRIPTION, "Group is not a subscription")
                group.subscription = subscription(group.subscription, values.objectValue("subscription"))
            }
            GroupManager.updateGroup(group)
            groupJson(group, false)
        }
        command("groups.delete", "Delete a group and its unreferenced profiles while stopped", "id confirm") {
            it.confirm()
            requireStopped()
            val group = group(it.positiveId())
            requireApi(!group.ungrouped, "The default group cannot be deleted")
            ensureUnreferenced(SagerDatabase.proxyDao.getIdsByGroup(group.id).toSet(), group.id)
            GroupManager.deleteGroup(group.id)
            JSONObject().put("deleted", true)
        }
        command("groups.reorder", "Set a complete, duplicate-free ordering for groups", "ids") {
            val groups = SagerDatabase.groupDao.allGroups()
            val ids = orderedIds(it.arrayValue("ids"), groups.map { group -> group.id })
            SagerDatabase.instance.runInTransaction {
                orderedIds(it.arrayValue("ids"), SagerDatabase.groupDao.allGroups().map { group -> group.id })
                ids.forEachIndexed { index, id -> check(SagerDatabase.groupDao.updateOrder(id, index + 1L) == 1) }
            }
            groups.forEach { group -> GroupManager.postUpdate(group.id) }
            JSONObject().put("updated", ids.size)
        }
        command("subscriptions.describe", "Read subscription field types and defaults") { ApiBean.describe(SubscriptionBean().apply { initializeDefaultValues() }) }
        command("subscriptions.update", "Refresh a subscription as a polled job; confirm approves update warnings", "id", "confirm") {
            val group = group(it.positiveId())
            requireApi(group.type == GroupType.SUBSCRIPTION, "Group is not a subscription")
            val confirm = it.flag("confirm")
            runtime.job {
                var refused = false
                var failed = false
                val callbacks = object : GroupManager.Interface {
                    override suspend fun confirm(message: String): Boolean {
                        refused = !confirm
                        return confirm
                    }
                    override suspend fun alert(message: String) = Unit
                    override suspend fun onUpdateSuccess(group: ProxyGroup, changed: Int, added: List<String>, updated: Map<String, String>, deleted: List<String>, duplicate: List<String>, byUser: Boolean) = Unit
                    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
                        failed = true
                    }
                }
                val success = GroupUpdater.executeUpdate(group, true, callbacks)
                if (refused) reject("confirmation_required", "Subscription update requires confirm=true")
                if (!success || failed) reject("update_failed", "Subscription update failed")
                groupJson(group(group.id), false)
            }
        }
    }

    private fun ruleCommands() {
        command("rules.list", "Read routing rules in evaluation order") { BackupFormatV2.encodeRules(SagerDatabase.rulesDao.allRules()) }
        command("rules.create", "Create a validated routing rule", "values") {
            val rule = rulePatch(RuleEntity(enabled = true), it.objectValue("values"))
            BackupFormatV2.encodeRule(ProfileManager.createRule(rule))
        }
        command("rules.update", "Patch a routing rule without changing its identity", "id values") {
            val rule = SagerDatabase.rulesDao.getById(it.positiveId()) ?: reject("not_found", "Rule does not exist")
            val updated = rulePatch(rule, it.objectValue("values"))
            ProfileManager.updateRule(updated)
            BackupFormatV2.encodeRule(updated)
        }
        command("rules.delete", "Delete one routing rule", "id confirm") {
            it.confirm()
            val id = it.positiveId()
            if (SagerDatabase.rulesDao.getById(id) == null) reject("not_found", "Rule does not exist")
            ProfileManager.deleteRule(id)
            JSONObject().put("deleted", true)
        }
        command("rules.reorder", "Set a complete routing rule evaluation order", "ids") {
            val rules = SagerDatabase.rulesDao.allRules()
            val ids = orderedIds(it.arrayValue("ids"), rules.map { rule -> rule.id })
            SagerDatabase.instance.runInTransaction {
                orderedIds(it.arrayValue("ids"), SagerDatabase.rulesDao.allRules().map { rule -> rule.id })
                ids.forEachIndexed { index, id -> check(SagerDatabase.rulesDao.updateOrder(id, index + 1L) == 1) }
            }
            ProfileManager.ruleIterator { onCleared() }
            JSONObject().put("updated", ids.size)
        }
    }

    private fun routingCommands() {
        command("routing.list", "List saved routing profiles") { JSONObject().put("activeId", RoutingProfiles.activeId).put("items", jsonArray(RoutingProfiles.list().map { it.toJson() })) }
        command("routing.save", "Save live routing rules and settings as a named profile", "name") { RoutingProfiles.saveLiveAs(it.text("name")).toJson() }
        command("routing.select", "Apply a saved routing profile; reloading the VPN is explicit", "id") {
            val id = it.positiveId()
            requireApi(RoutingProfiles.list().any { profile -> profile.id == id }, "Routing profile does not exist")
            RoutingProfiles.switchTo(id)
            DataStore.configurationStore.awaitWrites()
            JSONObject().put("activeId", id).put("reloadRequired", true)
        }
        command("routing.rename", "Rename a saved routing profile", "id name") {
            val id = it.positiveId()
            requireApi(RoutingProfiles.list().any { profile -> profile.id == id }, "Routing profile does not exist")
            RoutingProfiles.rename(id, it.text("name"))
            DataStore.configurationStore.awaitWrites()
            JSONObject().put("updated", true)
        }
        command("routing.delete", "Delete a saved routing profile without clearing live rules", "id confirm") {
            it.confirm()
            val id = it.positiveId()
            requireApi(RoutingProfiles.list().any { profile -> profile.id == id }, "Routing profile does not exist")
            RoutingProfiles.delete(id)
            DataStore.configurationStore.awaitWrites()
            JSONObject().put("deleted", true)
        }
        command("routing.export", "Export a routing profile as JSON and a share link", "id") {
            val profile = RoutingProfiles.exportable(it.positiveId()) ?: reject("not_found", "Routing profile does not exist")
            JSONObject().put("content", profile.toExportJson()).put("link", profile.toLink())
        }
        command("routing.import", "Import routing JSON or a share link; confirm permits replacing a matching name", "text", "confirm") {
            val candidate = RoutingProfiles.parse(it.text("text")) ?: reject("invalid_parameters", "Invalid routing profile")
            if (RoutingProfiles.replacementFor(candidate) != null) it.confirm()
            val profile = RoutingProfiles.store(candidate)
            DataStore.configurationStore.awaitWrites()
            profile.toJson()
        }
    }

    private fun serviceCommands() {
        command("service.start", "Start the selected or specified profile if Android permits unattended start", optional = "id") {
            requireStopped()
            val profile = profile(if (it.has("id")) it.positiveId() else DataStore.selectedProxy)
            requireApi(profile.canBuild(), "Archived profiles cannot run")
            NetworkAutomation.unattendedStartBlocked()?.let { blocked -> reject("permission_required", blocked.name) }
            DataStore.selectedProxy = profile.id
            DataStore.configurationStore.awaitWrites()
            withContext(Dispatchers.Main) { SagerNet.startService(profile.id) }
            JSONObject().put("accepted", true)
        }
        command("service.stop", "Request VPN/proxy shutdown; poll app.status for completion") {
            withContext(Dispatchers.Main) { SagerNet.stopService() }
            JSONObject().put("accepted", true)
        }
        command("service.reload", "Apply saved configuration or switch a running profile", optional = "id") {
            val state = runtime.serviceState()
            if (state == BaseService.State.Idle) reject("service_unavailable", "The service connection is not ready")
            if (!state.canStop) reject("service_stopped", "Start the service before reloading it")
            val id = if (it.has("id")) profile(it.positiveId()).id else -1L
            DataStore.configurationStore.awaitWrites()
            withContext(Dispatchers.Main) { SagerNet.reloadService(id) }
            JSONObject().put("accepted", true)
        }
        command("service.traffic", "Read the latest traffic sample") { JSONObject(runtime.speed.toString()) }
        command("service.connections", "Read tracked core connections", optional = "includeClosed") { JSONArray(runtime.binder().connections(it.flag("includeClosed"))) }
        command("service.resetconnections", "Reset upstream core connections without wiping configuration", "confirm") {
            it.confirm()
            context.sendBroadcast(Intent(Action.RESET_UPSTREAM_CONNECTIONS).setPackage(context.packageName))
            JSONObject().put("accepted", true)
        }
        command("service.test", "Run a connection probe as a polled job", optional = "id") {
            val id = if (it.has("id")) profile(it.positiveId()).id else null
            runtime.job {
                val latency = if (id == null) runtime.binder().urlTest() else runtime.binder().urlTestProfile(id)
                JSONObject().put("latencyMs", latency)
            }
        }
        command("wireguard.status", "Read live WireGuard or AmneziaWG state for a profile", "id") { JSONObject(runtime.binder().wireguardStatus(profile(it.positiveId()).id)) }
    }

    private fun automationCommands() {
        command("automation.get", "Read network rules, enable state and pause state") {
            JSONObject().put("enabled", DataStore.networkAutomation).put("paused", DataStore.automationPaused)
                .put("blocked", DataStore.networkAutomationBlocked).put("rules", jsonArray(NetworkAutomation.rules().map { it.toJson() }))
        }
        command("automation.set", "Replace network rules after validating all entries", "rules") {
            val rules = it.arrayValue("rules").objects().map { json ->
                requireApi(json.keysSet().all { key -> key in setOf("kind", "ssid", "action", "profileId") }, "Unknown network rule field")
                val kind = NetworkAutomation.Kind.entries.firstOrNull { kind -> kind.name == json.text("kind") } ?: reject("invalid_parameters", "Unknown network kind")
                val action = NetworkAutomation.Action.entries.firstOrNull { action -> action.name == json.text("action") } ?: reject("invalid_parameters", "Unknown network action")
                val ssid = if (json.has("ssid")) json.text("ssid") else ""
                requireApi(kind != NetworkAutomation.Kind.SSID || ssid.isNotBlank(), "SSID rules need a name")
                val id = if (json.has("profileId")) json.integer("profileId") else -1L
                if (id > 0) profile(id)
                requireApi(id == -1L || id > 0, "Invalid automation profileId")
                NetworkAutomation.Rule(kind, ssid, action, id)
            }
            requireApi(rules.size <= 100 && rules.distinctBy { rule -> rule.kind to rule.ssid }.size == rules.size, "Too many or duplicate network rules")
            NetworkAutomation.saveRules(rules)
            DataStore.configurationStore.awaitWrites()
            NetworkAutomation.onRulesChanged(context)
            JSONObject().put("updated", rules.size)
        }
    }

    private fun tailscaleCommands() {
        command("tailscale.running", "List Tailscale profiles in the running configuration") { JSONArray(runtime.binder().runningTailscaleProfiles()) }
        command("tailscale.open", "Open a managed Tailscale observation or stopped-profile check", "id", "check") { runtime.openTailscale(profile(it.positiveId()), it.flag("check")) }
        command("tailscale.status", "Poll a managed Tailscale session", "sessionId") { runtime.tailscaleStatus(it.positiveId("sessionId")) }
        command("tailscale.close", "Close a session and release its temporary core", "sessionId") { runtime.closeTailscale(it.positiveId("sessionId")) }
        command("tailscale.ping", "Start a peer probe in a managed session", "sessionId peerId", "timeoutMs") {
            val timeout = if (it.has("timeoutMs")) it.integer("timeoutMs") else 5000L
            requireApi(timeout in 100..60_000, "timeoutMs must be 100..60000")
            runtime.tailscaleRequest(it.positiveId("sessionId"), it.text("peerId"), timeout.toInt(), null)
        }
        command("tailscale.exit", "Change the exit node using the expected saved selection as a concurrency guard", "sessionId peerId expectedSavedSelection confirm") {
            it.confirm()
            runtime.tailscaleRequest(it.positiveId("sessionId"), it.text("peerId"), null, it.text("expectedSavedSelection"))
        }
        command("tailscale.result", "Poll a peer probe or exit-node change", "requestId") { runtime.tailscaleResult(it.positiveId("requestId")) }
        command("tailscale.cancel", "Cancel a request without closing its session", "requestId") { runtime.cancelTailscale(it.positiveId("requestId")) }
    }

    private fun fileCommands() {
        val files = ApiFiles(context)
        command("assets.list", "List installed routing assets") { files.list() }
        command("assets.import", "Install a small base64-encoded routing asset with atomic replacement", "name content", "confirm") {
            files.import(it.text("name"), it.text("content"), it.flag("confirm"))
        }
        command("assets.download", "Download an HTTPS routing asset and verify its checksum before replacement", "name url sha256", "confirm") {
            val name = it.text("name")
            val url = it.text("url")
            val hash = it.text("sha256")
            val confirm = it.flag("confirm")
            runtime.job { files.download(name, url, hash, confirm) }
        }
        command("assets.delete", "Delete a custom routing asset", "name confirm") {
            it.confirm()
            files.delete(it.text("name"))
        }
        command("logs.read", "Read a bounded tail of the core log; it may contain private configuration", "includeSecrets", "maxBytes") {
            it.secrets()
            files.log(if (it.has("maxBytes")) it.integer("maxBytes") else 50L * 1024)
        }
        command("logs.clear", "Clear the app's core log", "confirm") {
            it.confirm()
            Libcore.nekoLogClear()
            JSONObject().put("cleared", true)
        }
        command("profiles.test", "Test a saved profile with the existing isolated probe lifecycle", "id") {
            val profile = profile(it.positiveId())
            requireApi(profile.canBuild(), "Archived profiles cannot run")
            runtime.job { JSONObject().put("latencyMs", UrlTest { runCatching { runtime.binder() }.getOrNull() }.doTest(profile)) }
        }
        command("apps.list", "List installed packages for per-app routing") {
            @Suppress("DEPRECATION")
            val apps = context.packageManager.getInstalledApplications(0)
            jsonArray(apps.sortedBy { it.packageName }.map { app -> JSONObject().put("package", app.packageName).put("label", context.packageManager.getApplicationLabel(app).toString()) })
        }
        command("sharing.get", "Read proxy sharing state and optional credentials", optional = "includeSecrets") {
            JSONObject().put("enabled", DataStore.allowAccess).put("port", DataStore.mixedPort).apply {
                if (it.flag("includeSecrets")) put("username", Key.SHARE_USERNAME).put("password", DataStore.shareSecret)
            }
        }
        command("sharing.rotate", "Replace the proxy sharing credential; reload is explicit", "confirm") {
            it.confirm()
            DataStore.regenerateShareSecret()
            DataStore.configurationStore.awaitWrites()
            JSONObject().put("reloadRequired", true)
        }
        command("diagnostics.stun", "Run the built-in STUN diagnostic as a polled job", "server") {
            val server = it.text("server")
            requireApi(server.isNotBlank() && server.length <= 1024, "Invalid STUN server")
            runtime.job {
                val result = Libcore.stunTest(server)
                JSONObject().put("success", result.success).put("text", result.text)
            }
        }
        command("diagnostics.icmp", "Run a bounded ICMP probe as a polled job", "address", "timeoutMs") {
            val address = it.text("address")
            val timeout = if (it.has("timeoutMs")) it.integer("timeoutMs") else 3000L
            requireApi(address.isNotBlank() && address.length <= 253 && timeout in 100..30_000, "Invalid probe parameters")
            runtime.job { JSONObject().put("latencyMs", Libcore.icmpPing(address, timeout.toInt())) }
        }
    }

    private fun permissions(): JSONObject = JSONObject().apply {
        put("unattendedStartBlocked", NetworkAutomation.unattendedStartBlocked()?.name ?: JSONObject.NULL)
        put("fineLocation", ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        put("notifications", LocationSpoofing.notificationsAllowed(context))
        put("mockLocation", LocationSpoofing.mockLocationAllowed(context))
        put("simulatedLocationMayRemain", LocationSpoofing.mayRemain(context))
        put("androidSdk", Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT >= 29) {
            // A refused system query must not hide the other permission states.
            put("alwaysOn", runCatching { android.net.VpnService().isAlwaysOn }.getOrNull() ?: JSONObject.NULL)
            put("lockdown", runCatching { android.net.VpnService().isLockdownEnabled }.getOrNull() ?: JSONObject.NULL)
        }
    }

    private fun profile(id: Long) = ProfileManager.getProfile(id) ?: reject("not_found", "Profile does not exist")
    private fun group(id: Long) = SagerDatabase.groupDao.getById(id) ?: reject("not_found", "Group does not exist")
    private fun requireStopped() {
        val state = runtime.serviceState()
        if (state == BaseService.State.Idle) reject("service_unavailable", "Wait for the service connection before changing its state")
        if (state != BaseService.State.Stopped) reject("service_running", "Stop the service and close active checks first")
    }
    private fun profileJson(profile: ProxyEntity, secrets: Boolean) = JSONObject().apply {
        put("id", profile.id)
        put("groupId", profile.groupId)
        put("type", profile.type)
        put("name", profile.displayName())
        put("protocol", profile.displayType())
        put("userOrder", profile.userOrder)
        put("status", profile.status)
        put("ping", profile.ping)
        put("rx", profile.rx)
        put("tx", profile.tx)
        put("lifetimeRx", profile.lifetimeRx)
        put("lifetimeTx", profile.lifetimeTx)
        put("canBuild", profile.canBuild())
        if (secrets && profile.canBuild()) put("configuration", ApiBean.encode(profile.requireBean()))
    }
    private fun groupJson(group: ProxyGroup, secrets: Boolean) = JSONObject().apply {
        put("id", group.id)
        put("name", group.displayName())
        put("type", group.type)
        put("userOrder", group.userOrder)
        put("ungrouped", group.ungrouped)
        put("order", group.order)
        put("isSelector", group.isSelector)
        put("autoSelect", group.autoSelect)
        put("frontProxy", group.frontProxy)
        put("landingProxy", group.landingProxy)
        if (secrets) group.subscription?.let { put("subscription", ApiBean.encode(it)) }
    }
    internal fun validateBean(bean: AbstractBean, editingId: Long = 0L, lookup: (Long) -> ProxyEntity = ::profile) {
        requireApi(bean.serverPort in 1..65535, "serverPort must be 1..65535")
        requireApi(!bean.serverAddress.isNullOrBlank(), "serverAddress is required")
        if (bean is ChainBean) {
            val ids = bean.proxies.orEmpty()
            requireApi(ids.isNotEmpty() && ids.size <= 100 && ids.distinct().size == ids.size && editingId !in ids, "Invalid proxy chain")
            val visiting = mutableSetOf<Long>()
            val completed = mutableSetOf<Long>()
            fun check(id: Long) {
                if (id in completed) return
                requireApi(id != editingId && visiting.add(id), "Proxy chain contains a cycle")
                val member = lookup(id)
                requireApi(member.isUsableChainHop(), "A chain member cannot be used as a hop")
                val nested = member.requireBean() as? ChainBean
                if (nested != null) requireApi(!nested.proxies.isNullOrEmpty(), "A nested chain has no hops")
                nested?.proxies?.forEach(::check)
                visiting.remove(id)
                completed.add(id)
            }
            ids.forEach(::check)
        }
        // Exercise the persisted format before accepting a bean, without starting a core.
        KryoConverters.serialize(bean)
    }
    private fun subscription(existing: SubscriptionBean?, changes: JSONObject): SubscriptionBean {
        val bean = existing?.let { KryoConverters.subscriptionDeserialize(KryoConverters.serialize(it)) }
            ?: SubscriptionBean().apply { initializeDefaultValues() }
        val editable = setOf("link", "forceResolve", "deduplication", "updateWhenConnectedOnly", "customUserAgent", "autoUpdate", "autoUpdateDelay", "filterMode", "filterRegex", "customDnsResolver", "sendDeviceId", "outputFormat", "backupLinks")
        requireApi(changes.keysSet().all { it in editable }, "Unknown or read-only subscription field")
        ApiBean.patch(bean, changes)
        val links = listOf(bean.link.orEmpty()) + bean.backupLinks.orEmpty().lines().filter { it.isNotBlank() }
        requireApi(links.all { link -> link.startsWith("https://") || link.startsWith("http://") }, "Subscription URLs must use HTTP or HTTPS")
        requireApi(bean.autoUpdateDelay in 15..525600 && bean.filterMode in 0..2 && bean.outputFormat in 0..4, "Invalid subscription setting")
        requireApi(bean.filterRegex.isNullOrEmpty() || runCatching { Regex(bean.filterRegex!!) }.isSuccess, "Invalid filter expression")
        return bean
    }
    private fun rulePatch(rule: RuleEntity, values: JSONObject): RuleEntity {
        val json = BackupFormatV2.encodeRule(rule)
        requireApi(values.keysSet().all { it in json.keysSet() && it != "id" && it != "userOrder" }, "Unknown or read-only rule field")
        values.keysSet().forEach { key ->
            val value = values.get(key)
            when (json.get(key)) {
                is String -> values.text(key)
                is Boolean -> values.flag(key)
                is Number -> values.integer(key)
                is JSONArray -> requireApi(value is JSONArray && (0 until value.length()).all { value.get(it) is String }, "$key must contain strings")
            }
            json.put(key, value)
        }
        val result = BackupFormatV2.decodeRule(json)
        requireApi(result.outbound >= -2, "Invalid outbound")
        if (result.outbound > 0) profile(result.outbound)
        if (result.config.isNotBlank()) requireApi(runCatching { JSONObject(result.config) }.isSuccess, "config must be a JSON object")
        return result
    }
    private fun orderedIds(array: JSONArray, expected: List<Long>): List<Long> {
        val ids = (0 until array.length()).map { index -> JSONObject().put("id", array.get(index)).positiveId() }
        requireApi(ids.size == expected.size && ids.toSet().size == ids.size && ids.toSet() == expected.toSet(), "ids must be a permutation of the current records")
        return ids
    }
    private fun ensureUnreferenced(ids: Set<Long>, deletingGroup: Long? = null) {
        val referenced = SagerDatabase.proxyDao.getAll().any { it.id !in ids && it.chainBean?.proxies.orEmpty().any { id -> id in ids } } ||
            SagerDatabase.groupDao.allGroups().any { it.id != deletingGroup && (it.frontProxy in ids || it.landingProxy in ids) } ||
            SagerDatabase.rulesDao.allRules().any { it.outbound in ids } || NetworkAutomation.rules().any { it.profileId in ids } ||
            RoutingProfiles.list().any { saved ->
                saved.source != deletingGroup?.let(RoutingProfiles::subscriptionSource) &&
                    saved.content.optJSONArray("rules")?.objects().orEmpty().any { it.optLong("outbound") in ids }
            }
        if (referenced) reject("referenced", "Remove references from chains, groups, routing or automation before deleting")
    }
    private suspend fun backup(): JSONObject {
        DataStore.configurationStore.awaitWrites()
        return JSONObject().put("version", BackupFormatV2.VERSION)
            .put("profiles", BackupFormatV2.encodeProfiles(profilesForBackup()))
            .put("groups", BackupFormatV2.encodeGroups(SagerDatabase.groupDao.allGroups()))
            .put("rules", BackupFormatV2.encodeRules(SagerDatabase.rulesDao.allRules()))
            .put("settings", BackupFormatV2.encodeSettings(PublicDatabase.kvPairDao.all()))
    }
}
