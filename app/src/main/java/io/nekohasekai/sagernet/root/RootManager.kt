package io.nekohasekai.sagernet.root

import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Detects whether the device grants root (su) access.
 */
object RootManager {

    enum class Status {
        // su 二進位起不來：這台設備沒有 root
        UNAVAILABLE,

        // su 回了 uid 0（含已設成自動同意／永久放行的情況）
        GRANTED,

        // su 起來了但一直不回：多半卡在授權視窗等人按允許
        WAITING,

        // su 明確回非 0 / 拿到的不是 uid 0：已被拒絕
        DENIED,
    }

    @Volatile
    private var cached: Status? = null

    // 提示閘值與等待上限。這是「我方願意等多久」，不是任何一家 su 的授權倒數
    // （各家預設值未實測，寫死會變成憑空假設）；超過提示閘值就假定為「有人在等窗」。
    private const val NOTIFY_AFTER_SECONDS = 3L
    private const val GRANT_TIMEOUT_SECONDS = 30L

    fun cachedRoot(): Boolean = cached == Status.GRANTED

    fun cachedStatus(): Status? = cached

    suspend fun refresh(): Boolean = refreshDetailed() == Status.GRANTED

    suspend fun refreshDetailed(
        onWaitingGrant: (() -> Unit)? = null,
    ): Status = withContext(Dispatchers.IO) {
        probe(onWaitingGrant = onWaitingGrant).also { cached = it }
    }

    fun isRootAvailable(forceRefresh: Boolean = false): Boolean {
        if (!forceRefresh) {
            cached?.let { return it == Status.GRANTED }
        }
        return probe().also { cached = it } == Status.GRANTED
    }

    /**
     * 一次探測跑完，不重試命令：Magisk／KernelSU／APatch 的授權視窗會擋在 su 進程裡，
     * 重起一條只會再排一次隊。所以「先快探、卡住才提示並繼續等同一個進程」。
     *
     * [onWaitingGrant] 只在超過提示閘值仍沒回覆時觸發一次。已設成自動同意的設備會
     * 秒回，永遠不會走到這條，因此不會被多餘的提示打擾。
     */
    private fun probe(
        notifyAfterSeconds: Long = NOTIFY_AFTER_SECONDS,
        timeoutSeconds: Long = GRANT_TIMEOUT_SECONDS,
        onWaitingGrant: (() -> Unit)? = null,
    ): Status {
        val process = try {
            ProcessBuilder("su", "-c", "id -u")
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            Logs.w("RootManager: no su binary (${e.message})")
            return Status.UNAVAILABLE
        }
        val startedAt = System.nanoTime()
        var notified = false
        var finished = false
        while (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt) < timeoutSeconds) {
            try {
                process.exitValue()
                finished = true
                break
            } catch (_: IllegalThreadStateException) {
                Thread.sleep(50)
            }
            if (!notified && onWaitingGrant != null &&
                TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedAt) >= notifyAfterSeconds
            ) {
                notified = true
                onWaitingGrant()
            }
        }
        if (!finished) {
            process.destroy()
            Logs.w("RootManager: su did not answer within ${timeoutSeconds}s")
            return Status.WAITING
        }
        val output = runCatching {
            process.inputStream.bufferedReader().use { it.readText() }
        }.getOrDefault("")
        val uid = output.trim().lineSequence().lastOrNull()?.trim()
        if (process.exitValue() != 0) {
            Logs.w("RootManager: su exited ${process.exitValue()}: ${output.trim()}")
            return Status.DENIED
        }
        if (uid != "0") {
            Logs.w("RootManager: su returned uid=$uid, not 0")
            return Status.DENIED
        }
        Logs.i("RootManager: root granted")
        return Status.GRANTED
    }
}
