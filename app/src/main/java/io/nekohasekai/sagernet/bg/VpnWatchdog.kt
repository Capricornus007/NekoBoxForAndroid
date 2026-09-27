package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.Protocols

class VpnWatchdog(private val service: BaseService.Interface) {

    companion object {
        private const val FAIL_THRESHOLD = 3 // 连续 3 次 urlTest 失败才断开（配合默认数秒间隔，避免网络抖动误断）
        private const val HTTP_TIMEOUT_MS = 3_000 // Ждем ответа всего 3 секунды

        @Volatile
        var testModeRequested = false
    }

    private var job: Job? = null
    private var consecutiveFailures = 0
    private var lastFailure: String? = null

    fun start(scope: CoroutineScope) {
        // 斷開守護常駐生效（用戶要求：連續失敗即斷開、以後不再空轉），不再依賴 vpnWatchdogEnabled 開關。
        job?.cancel()
        consecutiveFailures = 0
        lastFailure = null
        testModeRequested = false

        var intervalSec = DataStore.vpnWatchdogInterval
        if (intervalSec < 3) intervalSec = 3 // 下限 3 秒

        val checkIntervalMs = intervalSec * 1000L

        job = scope.launch(Dispatchers.IO) {
            Logs.d("VpnWatchdog: 已启动（每 ${intervalSec}s 检测；连续 $FAIL_THRESHOLD 次失败即断开）")
            delay(checkIntervalMs)
            while (isActive) {
                try {
                    check()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Logs.w("VpnWatchdog error", error)
                }
                delay(checkIntervalMs)
            }
        }
    }

    suspend fun stop() {
        // A native urlTest is blocking and does not observe coroutine cancellation until it
        // returns. Join before BaseService closes the box, otherwise the test can still be using
        // the native handle concurrently with box.Close().
        val watchdogJob = job
        job = null
        watchdogJob?.cancelAndJoin()
        consecutiveFailures = 0
        lastFailure = null
        testModeRequested = false
        Logs.d("VpnWatchdog: остановлен")
    }

    private suspend fun check() {
        if (service.data.state != BaseService.State.Connected) {
            consecutiveFailures = 0
            return
        }

        if (SagerNet.underlyingNetwork == null) {
            consecutiveFailures = 0
            return
        }

        val url = DataStore.connectionTestURL
        val box = service.data.proxy?.box ?: return

        // --- МОДИФИЦИРОВАННАЯ ЛОГИКА ТЕСТА ---
        val reachable = if (testModeRequested) {
            // Если мы нажали кнопку теста, МЫ ВРЕМ (имитируем лаг)
            Logs.w("Watchdog: 🧪 ИМИТАЦИЯ ЗАВИСАНИЯ (Тестовый режим)")
            false
        } else {
            // Обычная проверка
            try {
                val result = Libcore.urlTest(box, url, HTTP_TIMEOUT_MS)
                // urlTest is a blocking native call. Honor a stop request immediately after it
                // returns, before producing UI/reset side effects against a tearing-down box.
                currentCoroutineContext().ensureActive()
                result > 0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e.readableMessage
                false
            }
        }

        if (reachable) {
            consecutiveFailures = 0
            lastFailure = null
            return
        }

        // 這裡說明：要么真的断了，要么是测试模式
        consecutiveFailures++

        // 只在计数的日志里体现，不再每次失败都弹 toast（避免抖动时刷屏）
        Logs.w("Watchdog: 连接检测失败 ($consecutiveFailures/$FAIL_THRESHOLD)")

        if (consecutiveFailures >= FAIL_THRESHOLD) {
            // 保留最後一次的失敗原因再清計數：用戶看到的「連不上」原本只有一句
            // 斷開提示，分不清是節點掛了還是直連 DNS 查不到節點域名（後者會讓整組
            // 節點一起失敗，跟機場無關）。
            val reason = lastFailure?.let { Protocols.genFriendlyMsg(it) }
            consecutiveFailures = 0
            testModeRequested = false
            lastFailure = null

            Logs.w("Watchdog: 連續 $FAIL_THRESHOLD 次連接失敗 → 斷開服務（不自動重連）")
            showToast(
                if (reason == null) {
                    "⚠️ 偵測到出口連續連接失敗，已自動斷開，請手動重連"
                } else {
                    "⚠️ 偵測到出口連續連接失敗，已自動斷開：$reason"
                },
            )

            service.stopRunner(false, "Watchdog: connection repeatedly failed")
        }
    }

    private suspend fun showToast(msg: String) {
        withContext(Dispatchers.Main) {
            try {
                android.widget.Toast.makeText(SagerNet.application, msg, android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) {}
        }
    }
}
