package io.nekohasekai.sagernet.database

import android.util.AtomicFile
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import moe.matsuri.nb4a.utils.JavaUtil.gson
import java.io.File
import io.nekohasekai.sagernet.ktx.readBytesLimited
import io.nekohasekai.sagernet.ktx.Logs

/** One private, atomic rollback point; profiles are not copied. */
object NetworkSettingsHistory {
    private val file get() = AtomicFile(File(SagerNet.application.noBackupFilesDir, "network-settings-previous.json"))
    private val keys = setOf(
        "networkProfilesEnabled", "networkProfiles", Key.REMOTE_DNS, Key.DIRECT_DNS, Key.ENABLE_DNS_ROUTING, Key.ENABLE_FAKEDNS,
        Key.IPV6_MODE, Key.MTU, Key.TUN_IMPLEMENTATION, Key.STRICT_ROUTE,
        Key.BYPASS_LAN, Key.BYPASS_LAN_IN_CORE, Key.TRAFFIC_SNIFFING, Key.RESOLVE_DESTINATION,
        Key.CONCURRENT_DIAL, Key.DIRECT_FALLBACK, Key.DIRECT_FALLBACK_URL, Key.DIRECT_FALLBACK_INTERVAL,
        Key.GLOBAL_CUSTOM_CONFIG, "directFallbackRequired", "directFallbackFailures", "directFallbackRecoveries"
    )
    private data class Snapshot(val version: Int = 1, val settings: List<KeyValuePair>, val rules: List<RuleEntity>)

    @Synchronized fun available() = runCatching { file.openRead().use { }; true }.getOrDefault(false)

    @Synchronized fun capture(): Boolean = try {
        val snapshot = Snapshot(settings = PublicDatabase.kvPairDao.all().filter { it.key in keys }, rules = SagerDatabase.rulesDao.allRules())
        val content = gson.toJson(snapshot).toByteArray(Charsets.UTF_8)
        require(content.size <= 8 * 1024 * 1024) { "Network settings snapshot exceeds 8 MiB" }
        val target = file
        val output = target.startWrite()
        try { output.write(content); target.finishWrite(output) }
        catch (e: Exception) { target.failWrite(output); throw e }
        true
    } catch (e: Exception) {
        Logs.d("Cannot save network settings rollback", e)
        false
    }

    @Synchronized fun restore() {
        val target = file
        val content = target.openRead().use { input ->
            val bytes = input.readBytesLimited(8 * 1024 * 1024)
            bytes.toString(Charsets.UTF_8)
        }
        val snapshot = gson.fromJson(content, Snapshot::class.java)
        require(snapshot.version == 1 && snapshot.settings.all { it.key in keys }) { "Invalid network settings snapshot" }
        val profileIds = SagerDatabase.proxyDao.getAll().map { it.id }.toSet()
        require(snapshot.rules.all { (it.outbound <= 0 || it.outbound in profileIds) && (it.fallbackMode != 3 || it.fallbackProfile in profileIds) }) { "A profile used by the previous routes was deleted" }
        BackupRestore.commit(snapshot.settings, keys) {
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(snapshot.rules)
        }
        target.delete()
    }
}
