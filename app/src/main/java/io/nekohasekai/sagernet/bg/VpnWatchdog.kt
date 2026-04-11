package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import libcore.Libcore

class VpnWatchdog(private val service: BaseService.Interface) {

    companion object {
        private const val FAIL_THRESHOLD  = 2     // 2 сбоя = рестарт (быстрая реакция)
        private const val HTTP_TIMEOUT_MS = 3_000 // Ждем ответа всего 3 секунды

        @Volatile
        var testModeRequested = false
    }

    private var job: Job? = null
    private var consecutiveFailures = 0
    private var lastRestartAt = 0L

    fun start(scope: CoroutineScope) {
        if (!DataStore.vpnWatchdogEnabled) return
        job?.cancel()
        consecutiveFailures = 0
        testModeRequested = false

        // ЧИТАЕМ ИНТЕРВАЛ ИЗ НАСТРОЕК (по дефолту 7 сек)
        var intervalSec = DataStore.vpnWatchdogInterval
        if (intervalSec < 3) intervalSec = 3 // Защита от дурака (не меньше 3 сек)
        
        val checkIntervalMs = intervalSec * 1000L
        val minRestartIntervalMs = checkIntervalMs * 3 // Антипетля (3 интервала)

        job = scope.launch(Dispatchers.IO) {
            Logs.d("VpnWatchdog: запущен (интервал ${intervalSec}с)")
            delay(checkIntervalMs)
            while (isActive) {
                runCatching { check(minRestartIntervalMs) }
                    .onFailure { Logs.w("VpnWatchdog error", it) }
                delay(checkIntervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        consecutiveFailures = 0
        testModeRequested = false
        Logs.d("VpnWatchdog: остановлен")
    }

    private suspend fun check(minRestartIntervalMs: Long) {
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
                Libcore.urlTest(box, url, HTTP_TIMEOUT_MS) > 0
            } catch (e: Exception) {
                false
            }
        }

        if (reachable) {
            consecutiveFailures = 0
            return
        }

        // Если мы здесь - значит либо реальный лаг, либо мы его имитируем
        consecutiveFailures++
        
        // Показываем пользователю, что счетчик тикает (чтобы ты видел работу)
        showToast("Watchdog: обнаружен лаг ($consecutiveFailures/$FAIL_THRESHOLD)...")
        Logs.w("Watchdog: Потеря связи или Тест (#$consecutiveFailures)")

        if (consecutiveFailures >= FAIL_THRESHOLD) {
            val now = System.currentTimeMillis()
            
            // Проверка антипетли (в режиме теста игнорируем её, чтобы сработало сразу)
            if (!testModeRequested && (now - lastRestartAt < minRestartIntervalMs)) {
                return 
            }

            lastRestartAt = now
            consecutiveFailures = 0
            
            // Если это был тест, выключаем его после первого успешного срабатывания
            testModeRequested = false 
            
            Logs.w("Watchdog: ▶ ВЫПОЛНЯЮ АВТО-ВОССТАНОВЛЕНИЕ")
            showToast("⚠️ Связь восстановлена автоматически!")
            
            Libcore.resetAllConnections(true)
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