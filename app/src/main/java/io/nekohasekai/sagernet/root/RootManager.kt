package io.nekohasekai.sagernet.root

import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Detects whether the device grants root (su) access.
 */
object RootManager {

    @Volatile
    private var cached: Boolean? = null

    fun cachedRoot(): Boolean = cached ?: false

    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) {
        probe()
    }

    fun isRootAvailable(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) cached?.let { return it }
        val result = probe()
        cached = result
        return result
    }

    private fun probe(): Boolean {
        return try {
            val process = ProcessBuilder("su", "-c", "id -u")
                .redirectErrorStream(true)
                .start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var finished = false
            while (System.nanoTime() < deadline) {
                try {
                    process.exitValue()
                    finished = true
                    break
                } catch (_: IllegalThreadStateException) {
                    Thread.sleep(50)
                }
            }
            if (!finished) {
                process.destroy()
                Logs.w("RootManager: su probe timed out")
                return false
            }
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val isRoot = process.exitValue() == 0 && output.lineSequence().lastOrNull()?.trim() == "0"
            Logs.i("RootManager: root available = $isRoot")
            isRoot
        } catch (e: Exception) {
            Logs.w("RootManager: no root access (${e.message})")
            false
        }
    }
}
