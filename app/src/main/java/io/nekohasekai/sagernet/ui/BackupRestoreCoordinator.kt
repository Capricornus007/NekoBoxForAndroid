package io.nekohasekai.sagernet.ui

import android.os.Parcel
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ParcelizeBridge
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.tailscale.acquireTailscaleState
import io.nekohasekai.sagernet.fmt.tailscale.recoverTailscaleRestore
import io.nekohasekai.sagernet.fmt.tailscale.retainsTailscaleIdentity
import io.nekohasekai.sagernet.fmt.tailscale.stageTailscaleRestore
import io.nekohasekai.sagernet.fmt.tailscale.tailscaleStateFile
import io.nekohasekai.sagernet.fmt.tailscale.tailscaleStateIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.UUID

internal interface BackupRestoreOperations {
    suspend fun replaceProfiles(profiles: List<ProxyEntity>, groups: List<ProxyGroup>)

    suspend fun replaceRules(rules: List<RuleEntity>)

    suspend fun replaceSettings(settings: List<KeyValuePair>)
}

internal object DatabaseBackupRestoreOperations : BackupRestoreOperations {
    override suspend fun replaceProfiles(profiles: List<ProxyEntity>, groups: List<ProxyGroup>) {
        // Stopping is still busy. A timeout must fail before *any* selected section mutates.
        check(
            withTimeoutOrNull(10_000) {
                while (DataStore.serviceState.ownsTailscaleState) delay(100)
                true
            } == true,
        ) { "Service is still stopping. Wait for it to stop and retry the restore." }
        var lease: Closeable? = null
        val staged = ArrayList<Long>()
        var orphans = emptySet<Long>()
        try {
            try {
                SagerDatabase.instance.runInTransaction {
                    val previous = SagerDatabase.proxyDao.getEntities(
                        SagerDatabase.proxyDao.getIdsByType(ProxyEntity.TYPE_TAILSCALE),
                    )
                        .associateBy { it.id }
                    val restored = profiles.filter { it.type == ProxyEntity.TYPE_TAILSCALE }
                    val ids = previous.keys + restored.map { it.id } + tailscaleStateIds()
                    // Keep locks through commit and filesystem completion, including rollback.
                    lease = acquireTailscaleState(ids)
                    val kept = restored.filter { retainsTailscaleIdentity(previous[it.id], it) }.map { it.id }.toSet()
                    restored.filter { it.id !in kept }.forEach { it.uuid = UUID.randomUUID().toString() }
                    val replacements = restored.associateBy { it.id }
                    orphans = ids - previous.keys - replacements.keys
                    for (id in ids - kept - orphans) {
                        staged += id
                        stageTailscaleRestore(id, previous[id]?.uuid, replacements[id]?.uuid)
                    }
                    SagerDatabase.proxyDao.reset()
                    SagerDatabase.proxyDao.insert(profiles)
                    SagerDatabase.groupDao.reset()
                    SagerDatabase.groupDao.insert(groups)
                }
            } catch (failure: Throwable) {
                // Room has rolled back. Recover every directory, even if one rename fails.
                var recoveryFailure: Throwable? = null
                for (id in staged.asReversed()) {
                    try {
                        recoverTailscaleRestore(id)
                    } catch (e: Throwable) {
                        if (recoveryFailure == null) recoveryFailure = e else recoveryFailure.addSuppressed(e)
                    }
                }
                if (recoveryFailure != null) {
                    recoveryFailure.addSuppressed(failure)
                    throw recoveryFailure
                }
                throw failure
            }
            // Only committed markers authorize retirement; a crash here is recovered on acquisition.
            staged.forEach { recoverTailscaleRestore(it) }
            // No original or replacement Tailscale row exists for these IDs. Keep orphan files
            // untouched on SQL failure; after commit they cannot be exposed to a foreign node.
            orphans.forEach {
                check(
                    tailscaleStateFile(it).deleteRecursively(),
                ) { "Cannot remove orphan Tailscale identity" }
            }
        } finally {
            lease?.close()
        }
    }

    override suspend fun replaceRules(rules: List<RuleEntity>) {
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(rules)
        }
    }

    override suspend fun replaceSettings(settings: List<KeyValuePair>) {
        // Drain earlier write-through work, then replace settings through the store's ordered
        // durable path. This keeps approval merges and restore on one serialization boundary.
        DataStore.configurationStore.awaitWrites()
        DataStore.configurationStore.replaceAllDurable(settings)
    }
}

internal suspend fun restoreBackup(
    content: JSONObject,
    profile: Boolean,
    rule: Boolean,
    setting: Boolean,
    operations: BackupRestoreOperations,
) {
    // Validate-then-commit: decode every selected section before the first destructive write.
    val version = content.optInt("version", 1)
    if (version != 1 && version != BackupFormatV2.VERSION) {
        error("Unsupported backup version: $version")
    }

    val importConfigs = profile && content.has("profiles")
    val profiles = if (importConfigs) {
        when (version) {
            BackupFormatV2.VERSION -> BackupFormatV2.decodeProfiles(content.getJSONArray("profiles"))
            else -> decodeArray(content.getJSONArray("profiles")) { ProxyEntity.CREATOR.createFromParcel(it) }
        }
    } else {
        null
    }
    val groups = if (importConfigs) {
        when (version) {
            BackupFormatV2.VERSION -> BackupFormatV2.decodeGroups(content.getJSONArray("groups"))
            else -> decodeArray(content.getJSONArray("groups")) { ProxyGroup.CREATOR.createFromParcel(it) }
        }
    } else {
        null
    }
    val rules = if (rule && content.has("rules")) {
        when (version) {
            BackupFormatV2.VERSION -> BackupFormatV2.decodeRules(content.getJSONArray("rules"))
            else -> decodeArray(content.getJSONArray("rules")) { ParcelizeBridge.createRule(it) }
        }
    } else {
        null
    }
    val settings = if (setting && content.has("settings")) {
        // Local plugin signer approvals are never accepted from imported material.
        BackupFormatV2.sanitizeSettings(
            when (version) {
                BackupFormatV2.VERSION -> BackupFormatV2.decodeSettings(content.getJSONArray("settings"))

                else -> decodeArray(content.getJSONArray("settings")) {
                    KeyValuePair.CREATOR.createFromParcel(it)
                }
            },
        )
    } else {
        null
    }

    if (profiles != null && groups != null) {
        operations.replaceProfiles(profiles, groups)
    }
    rules?.let { operations.replaceRules(it) }
    settings?.let { operations.replaceSettings(it) }
}

/**
 * Decodes each legacy Parcel entry independently so every Parcel is recycled even when an entry
 * is malformed. Version 1 is the only caller; version 2 uses the explicit JSON schema.
 */
private fun <T> decodeArray(array: JSONArray, create: (Parcel) -> T): List<T> {
    val out = ArrayList<T>(array.length())
    for (i in 0 until array.length()) {
        val data = Util.b64Decode(array[i] as String)
        val parcel = Parcel.obtain()
        try {
            parcel.unmarshall(data, 0, data.size)
            parcel.setDataPosition(0)
            out.add(create(parcel))
        } finally {
            parcel.recycle()
        }
    }
    return out
}
