package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.util.concurrent.atomic.AtomicBoolean

// Login over DERP plus exit node selection; a phone on a slow network needs several seconds.
const val TAILSCALE_READY_TIMEOUT_MS = 20_000

// Short JNI rounds bound cancellation latency without closing a box while it is initializing.
internal suspend fun awaitTailscaleReady(
    box: libcore.BoxInstance,
    tag: String,
    waitForExitNode: Boolean,
) {
    val deadline = SystemClock.elapsedRealtime() + TAILSCALE_READY_TIMEOUT_MS
    while (true) {
        currentCoroutineContext().ensureActive()
        try {
            Libcore.tailscaleWaitReady(box, tag, waitForExitNode, 1_000)
            return
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline || e.readableMessage.contains("Tailscale needs login:")) throw e
        }
        delay(50)
    }
}

class TestInstance(
    profile: ProxyEntity,
    val link: String,
    private val timeout: Int,
    private val preparedConfig: ConfigBuildResult? = null,
) : BoxInstance(profile) {

    protected override val enableOlcrtcRecovery = false

    // close() can be reached from two paths that may overlap on cancellation: runProbe's
    // guaranteed cleanup and the `use { }` block's exit. BoxInstance.close() is not safe to run
    // twice (native box.close()), so guard it to run exactly once.
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.getAndSet(true)) return
        super.close()
    }

    suspend fun doTest(): Int = runProbe {
        for (endpoint in config.tailscaleEndpoints.values) {
            awaitTailscaleReady(box, endpoint.tag, endpoint.waitForExitNode)
        }
        currentCoroutineContext().ensureActive()
        Logs.d(
            "URLTest ${profile.displayName()}: calling Libcore.urlTest(box, link=$link, timeout=${timeout}ms)",
        )
        try {
            val result = Libcore.urlTest(box, link, timeout)
            Logs.d("URLTest ${profile.displayName()}: result latency=${result}ms")
            result
        } catch (e: Exception) {
            Logs.d("URLTest ${profile.displayName()}: failed: ${e.readableMessage}")
            throw e
        }
    }

    override fun buildConfig() {
        config = preparedConfig ?: buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(safeConfigDiagnostics(config, pluginConfigs.size))
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }
}
