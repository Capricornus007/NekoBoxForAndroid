package com.boristul.zybcvpn.bg.proto

import com.boristul.zybcvpn.BuildConfig
import com.boristul.zybcvpn.bg.GuardedProcessPool
import com.boristul.zybcvpn.database.ProxyEntity
import com.boristul.zybcvpn.fmt.buildConfig
import com.boristul.zybcvpn.ktx.Logs
import com.boristul.zybcvpn.ktx.runOnDefaultDispatcher
import com.boristul.zybcvpn.ktx.tryResume
import com.boristul.zybcvpn.ktx.tryResumeWithException
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
                        init()
                        launch()
                        if (processes.processCount > 0) {
                            // wait for plugin start
                            delay(500)
                        }
                        c.tryResume(Libcore.urlTest(box, link, timeout))
                    } catch (e: Exception) {
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
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
