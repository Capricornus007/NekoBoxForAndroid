package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.widget.Toast
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import java.io.File

/**
 * Recovery helper for the VLESS "tls: illegal parameter" state.
 *
 * We intentionally reset only sing-box persistent state (cache*.db*), then restart the app process.
 * This keeps behavior focused on the root cause instead of wiping the whole app cache.
 */
fun recoverVlessTlsStateAndRestart(activity: Activity) {
    runOnDefaultDispatcher {
        try {
            awaitServiceStopped(activity)

            clearSingBoxPersistentCacheDb()

            onMainDispatcher {
                Toast.makeText(
                    activity,
                    R.string.clear_cache_success,
                    Toast.LENGTH_SHORT,
                ).show()
                ProcessPhoenix.triggerRebirth(activity)
            }
        } catch (e: Exception) {
            onMainDispatcher {
                Toast.makeText(
                    activity,
                    activity.getString(R.string.clear_cache_failed, e.message),
                    Toast.LENGTH_SHORT,
                ).show()
                activity.finish()
            }
        }
    }
}

private suspend fun awaitServiceStopped(activity: Activity) {
    val ready = kotlinx.coroutines.CompletableDeferred<io.nekohasekai.sagernet.aidl.ISagerNetService>()
    val connection = io.nekohasekai.sagernet.bg.SagerConnection(io.nekohasekai.sagernet.bg.SagerConnection.CONNECTION_ID_SHORTCUT, true)
    val callback = object : io.nekohasekai.sagernet.bg.SagerConnection.Callback {
        override fun onServiceConnected(service: io.nekohasekai.sagernet.aidl.ISagerNetService) { ready.complete(service) }
        override fun stateChanged(state: io.nekohasekai.sagernet.bg.BaseService.State, profileName: String?, msg: String?) {}
        override fun onBinderDied() { ready.completeExceptionally(IllegalStateException("Service disconnected during recovery")) }
    }
    try {
        onMainDispatcher { connection.connect(activity, callback) }
        val service = kotlinx.coroutines.withTimeout(10_000) { ready.await() }
        SagerNet.stopService()
        kotlinx.coroutines.withTimeout(15_000) {
            while (service.state != io.nekohasekai.sagernet.bg.BaseService.State.Stopped.ordinal) {
                kotlinx.coroutines.delay(100)
            }
        }
    } finally {
        kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable + kotlinx.coroutines.Dispatchers.Main.immediate) {
            connection.disconnect(activity)
        }
    }
}

private fun clearSingBoxPersistentCacheDb() {
    val app = SagerNet.application
    val candidateDirs = listOfNotNull(
        app.cacheDir,
        app.cacheDir.parentFile?.let { File(it, "cache") },
        File(app.cacheDir, "cache"),
        File(app.filesDir, "cache"),
        File(app.noBackupFilesDir, "cache"),
    ).distinctBy { it.absolutePath }

    val cacheDbCandidates = buildList {
        candidateDirs.forEach { dir ->
            val files = dir.listFiles() ?: return@forEach
            files.forEach { file ->
                if (file.isFile && CACHE_DB_BASENAME.matches(file.name)) {
                    add(file)
                }
            }
        }
    }

    cacheDbCandidates.forEach { base ->
        deleteCacheDbFamily(base)
    }
}

private val CACHE_DB_BASENAME = Regex("^cache(?:_\\d+)?\\.db$")

private fun deleteCacheDbFamily(base: File) {
    listOf(base, File(base.path + "-wal"), File(base.path + "-shm")).forEach { file ->
        check(!file.exists() || file.delete()) { "Could not delete ${file.name}" }
    }
}
