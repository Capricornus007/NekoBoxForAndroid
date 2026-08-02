package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.suspendCoroutine

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    suspend fun doTest(): Int {
        return suspendCoroutine { c ->
            processes = GuardedProcessPool {
                Logs.w(it)
                c.tryResumeWithException(it)
            }
            runOnDefaultDispatcher {
                use {
                    try {
                        Logs.d("URLTest ${profile.displayName()}: init()")
                        init()
                        Logs.d("URLTest ${profile.displayName()}: launch()")
                        launch()
                        if (processes.processCount > 0) {
                            // wait for plugin start
                            Logs.d("URLTest ${profile.displayName()}: waiting ${processes.processCount} plugin(s) start, delay 500ms")
                            delay(500)
                        }
                        Logs.d("URLTest ${profile.displayName()}: calling Libcore.urlTest(box, link=$link, timeout=${timeout}ms)")
                        val latency = Libcore.urlTest(box, link, timeout)
                        Logs.d("URLTest ${profile.displayName()}: result latency=${latency}ms")
                        c.tryResume(latency)
                    } catch (e: Exception) {
                        Logs.d("URLTest ${profile.displayName()}: failed: ${e.readableMessage}")
                        c.tryResumeWithException(e)
                    }
                }
            }
        }
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        if (BuildConfig.DEBUG) Logs.d(config.config)
        // 测速实例用 NewTestSingBoxInstance：不注册 PlatformLogWriter，
        // 官方内核不再强制创建 CacheFile/ClashServer（见 libcore/box.go 批注）。
        box = Libcore.newTestSingBoxInstance(config.config, LocalResolverImpl)
    }

}
