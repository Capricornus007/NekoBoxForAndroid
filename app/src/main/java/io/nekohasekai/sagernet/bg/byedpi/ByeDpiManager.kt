package io.nekohasekai.sagernet.bg.byedpi

import android.util.Log
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread
import java.util.concurrent.atomic.AtomicBoolean

object ByeDpiManager : EmbeddedBackend<ByeDpiConfig, ByeDpiStatus> {

    private const val TAG = "ByeDpiManager"
    private const val STARTUP_TIMEOUT_MS = 8000L
    private const val STARTUP_RETRY_DELAY_MS = 50L

    private val nativeProxy = ByeDpiNativeProxy()
    private val mutableStatus = MutableStateFlow(ByeDpiStatus.IDLE)
    private var workerThread: Thread? = null
    private var lastConfig: ByeDpiConfig? = null
    private var lastError: String? = null

    override val status: StateFlow<ByeDpiStatus> = mutableStatus.asStateFlow()

    @Synchronized
    override fun start(config: ByeDpiConfig): Boolean {
        if (mutableStatus.value == ByeDpiStatus.STARTING || mutableStatus.value == ByeDpiStatus.RUNNING) {
            Logs.w("ByeDPI[start]: already active status=${mutableStatus.value}")
            Log.w(TAG, "start ignored: status=${mutableStatus.value}")
            return false
        }

        if (canConnect(config.listenAddress, config.listenPort)) {
            lastError = "local proxy port ${config.listenAddress}:${config.listenPort} is already in use"
            mutableStatus.value = ByeDpiStatus.FAILED
            Logs.e("ByeDPI[start]: error=$lastError")
            Log.e(TAG, "start failed: $lastError")
            return false
        }

        lastConfig = config
        lastError = null
        mutableStatus.value = ByeDpiStatus.STARTING
        val startedAt = System.currentTimeMillis()
        workerThread = thread(name = "ByeDPI", isDaemon = true) {
            val args = config.toCommandLine()
            try {
                Logs.i("ByeDPI[start]: listen=${config.listenAddress}:${config.listenPort}, args=${args.joinToString(" ")}")
                Log.i(TAG, "start requested on ${config.listenAddress}:${config.listenPort} args=${args.joinToString(" ")}")
                val result = nativeProxy.jniStartProxy(args)
                synchronized(this) {
                    workerThread = null
                    mutableStatus.value = if (mutableStatus.value == ByeDpiStatus.STOPPING || result == 0) {
                        ByeDpiStatus.IDLE
                    } else {
                        ByeDpiStatus.FAILED
                    }
                }
                if (result != 0 && mutableStatus.value == ByeDpiStatus.FAILED) {
                    lastError = "ByeDPI exited with result=$result"
                }
                Logs.i("ByeDPI[exit]: result=$result, status=${mutableStatus.value}")
                Log.i(TAG, "native exit: result=$result status=${mutableStatus.value} error=${lastError ?: "none"}")
            } catch (t: Throwable) {
                synchronized(this) {
                    workerThread = null
                    mutableStatus.value = ByeDpiStatus.FAILED
                }
                lastError = t.message ?: t.javaClass.simpleName
                Logs.e("ByeDPI[start]: exception", t)
                Log.e(TAG, "startup exception: ${lastError}", t)
            }
        }

        val ready = awaitReady(config)
        if (ready) {
            mutableStatus.value = ByeDpiStatus.RUNNING
            Logs.i("ByeDPI[start]: ready listen=${config.listenAddress}:${config.listenPort}, elapsedMs=${System.currentTimeMillis() - startedAt}")
            Log.i(TAG, "ready on ${config.listenAddress}:${config.listenPort} elapsedMs=${System.currentTimeMillis() - startedAt}")
            return true
        }

        val failure = lastError
            ?: "local proxy did not become ready on ${config.listenAddress}:${config.listenPort} within ${STARTUP_TIMEOUT_MS}ms"
        Logs.e("ByeDPI[start]: readiness failed error=$failure")
        Log.e(TAG, "readiness failed: $failure")
        stop(force = true)
        mutableStatus.value = ByeDpiStatus.FAILED
        lastError = failure
        return false
    }

    @Synchronized
    override fun stop(force: Boolean): Boolean {
        val currentStatus = mutableStatus.value
        if (currentStatus != ByeDpiStatus.STARTING &&
            currentStatus != ByeDpiStatus.RUNNING &&
            currentStatus != ByeDpiStatus.FAILED
        ) {
            Logs.w("ByeDPI[stop]: ignored status=$currentStatus")
            Log.w(TAG, "stop ignored: status=$currentStatus")
            return false
        }

        Logs.i("ByeDPI[stop]: force=$force, status=$currentStatus")
        Log.i(TAG, "stop requested force=$force status=$currentStatus")
        mutableStatus.value = ByeDpiStatus.STOPPING
        val result = if (force) {
            nativeProxy.jniForceClose()
        } else {
            nativeProxy.jniStopProxy()
        }

        if (result != 0) {
            mutableStatus.value = ByeDpiStatus.FAILED
            lastError = "failed to stop ByeDPI result=$result"
            Logs.e("ByeDPI[stop]: failed result=$result force=$force")
            Log.e(TAG, "stop failed result=$result force=$force")
            return false
        }

        Logs.i("ByeDPI[stop]: signal sent force=$force")
        Log.i(TAG, "stop signal sent force=$force")
        return true
    }

    fun currentConfig(): ByeDpiConfig? = lastConfig
    fun lastError(): String? = lastError

    private fun awaitReady(config: ByeDpiConfig): Boolean {
        val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (mutableStatus.value == ByeDpiStatus.FAILED || workerThread == null) {
                return false
            }
            if (canConnect(config.listenAddress, config.listenPort)) {
                return true
            }
            Thread.sleep(STARTUP_RETRY_DELAY_MS)
        }
        return false
    }

    private fun canConnect(host: String, port: Int): Boolean {
        val connected = AtomicBoolean(false)
        val probe = thread(name = "ByeDPI-Probe", isDaemon = true) {
            val result = runCatching {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress(host, port), STARTUP_RETRY_DELAY_MS.toInt())
                }
                true
            }.getOrDefault(false)
            connected.set(result)
        }
        probe.join(STARTUP_RETRY_DELAY_MS * 4)
        return connected.get()
    }
}
