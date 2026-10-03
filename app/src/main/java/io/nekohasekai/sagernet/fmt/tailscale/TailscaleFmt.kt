package io.nekohasekai.sagernet.fmt.tailscale

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import moe.matsuri.nb4a.SingBoxOptions
import java.io.File

// Per-profile node identity. Relative to the core working directory (no_backup),
// keyed by profile id so renames keep the node and "reset identity" can delete it.
fun tailscaleStateDirectory(profileId: Long) = "tailscale/$profileId"

// The core runs with no_backup as its working directory (libcore InitCore).
fun tailscaleStateFile(profileId: Long) = File(SagerNet.application.noBackupFilesDir, tailscaleStateDirectory(profileId))

// Remove node identities whose profile is gone, or, when [keep] is given, every identity not
// listed in it. Called after profiles are deleted and when the service stops. Skipped while the
// service runs: a node whose profile was just deleted may still be writing its state, and the
// stop prunes again.
fun pruneTailscaleState(keep: Set<Long>? = null) {
    if (DataStore.serviceState.started) return
    val directories = File(SagerNet.application.noBackupFilesDir, "tailscale").listFiles() ?: return
    val live = keep ?: SagerDatabase.proxyDao.getIdsByType(ProxyEntity.TYPE_TAILSCALE).toSet()
    directories.filter { it.name.toLongOrNull() !in live }.forEach { it.deleteRecursively() }
}

fun buildSingBoxEndpointTailscaleBean(bean: TailscaleBean, profileId: Long): SingBoxOptions.Endpoint_TailscaleOptions = SingBoxOptions.Endpoint_TailscaleOptions().apply {
    type = "tailscale"
    state_directory = tailscaleStateDirectory(profileId)
    if (bean.authKey!!.isNotBlank()) auth_key = bean.authKey
    if (bean.controlUrl!!.isNotBlank()) control_url = bean.controlUrl
    if (bean.hostname!!.isNotBlank()) hostname = bean.hostname
    if (bean.exitNode!!.isNotBlank()) {
        exit_node = bean.exitNode
        if (bean.exitNodeAllowLanAccess == true) exit_node_allow_lan_access = true
    }
    if (bean.acceptRoutes == true) accept_routes = true
    if (bean.onlyTcp443 == true) only_tcp_443 = true
}
