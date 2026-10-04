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

// Login over DERP plus exit node selection; a phone on a slow network needs several seconds.
const val TAILSCALE_READY_TIMEOUT_MS = 20_000

// Short JNI rounds bound cancellation latency without closing a box while it is initializing.
internal suspend fun awaitTailscaleReady(box: libcore.BoxInstance, tag: String, waitForExitNode: Boolean) {
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

    suspend fun doTest(): Int = runProbe {
        for (endpoint in config.tailscaleEndpoints.values) {
            awaitTailscaleReady(box, endpoint.tag, endpoint.waitForExitNode)
        }
        currentCoroutineContext().ensureActive()
        Libcore.urlTest(box, link, timeout)
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
