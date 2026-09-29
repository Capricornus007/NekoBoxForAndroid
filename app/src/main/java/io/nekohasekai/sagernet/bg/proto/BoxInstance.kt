package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildHysteria1Config
import io.nekohasekai.sagernet.fmt.mieru.MieruBean
import io.nekohasekai.sagernet.fmt.mieru.buildMieruConfig
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import kotlinx.coroutines.*
import libcore.BoxInstance
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

abstract class BoxInstance(val profile: ProxyEntity) : Closeable {
    lateinit var config: ConfigBuildResult
    lateinit var box: BoxInstance
    val pluginPath = hashMapOf<String, PluginManager.InitResult>()
    val pluginConfigs = hashMapOf<Int, String>()
    open lateinit var processes: GuardedProcessPool
    private val cacheFiles = ArrayList<File>()

    fun isInitialized() = ::config.isInitialized && ::box.isInitialized

    protected fun initPlugin(name: String) = pluginPath.getOrPut(name) { PluginManager.init(name)!! }

    protected open fun buildConfig() {
        config = buildConfig(profile)
        DataStore.mixedInboundAuthed = DataStore.mixedInboundNeedsAuth
    }

    protected open suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    open suspend fun init() {
        buildConfig()
        for ((chain) in config.externalIndex) {
            for ((port, profile) in chain) {
                when (val bean = profile.requireBean()) {
                    is MieruBean -> {
                        initPlugin("mieru-plugin")
                        pluginConfigs[port] = bean.buildMieruConfig(port)
                    }

                    is NaiveBean -> {
                        initPlugin("naive-plugin")
                        val creds = config.localProxyCredentials[port]
                        pluginConfigs[port] = bean.buildNaiveConfig(port, creds?.first, creds?.second)
                    }

                    is HysteriaBean -> {
                        initPlugin("hysteria-plugin")
                        pluginConfigs[port] = bean.buildHysteria1Config(port) {
                            File(app.cacheDir, "hysteria_${SystemClock.elapsedRealtime()}.ca").apply {
                                parentFile?.mkdirs()
                                cacheFiles.add(this)
                            }
                        }
                    }
                }
            }
        }
        loadConfig()
    }

    open fun launch() {
        val cacheDir = File(SagerNet.application.cacheDir, "tmpcfg")
        cacheDir.mkdirs()
        for ((chain) in config.externalIndex) {
            for ((port, profile) in chain) {
                val config = pluginConfigs[port].orEmpty()
                when (val bean = profile.requireBean()) {
                    is MieruBean -> {
                        val configFile = File(cacheDir, "mieru_${SystemClock.elapsedRealtime()}.json")
                        configFile.writeText(config)
                        cacheFiles.add(configFile)
                        processes.start(
                            listOf(initPlugin("mieru-plugin").path, "run"),
                            mutableMapOf("MIERU_CONFIG_JSON_FILE" to configFile.absolutePath, "MIERU_PROTECT_PATH" to "protect_path"),
                        )
                    }

                    is NaiveBean -> {
                        val configFile = File(cacheDir, "naive_${SystemClock.elapsedRealtime()}.json")
                        configFile.writeText(config)
                        cacheFiles.add(configFile)
                        val env = mutableMapOf<String, String>()
                        bean.certificates?.takeIf { it.isNotBlank() }?.let { certificates ->
                            val certFile = File(cacheDir, "naive_${SystemClock.elapsedRealtime()}.crt")
                            certFile.writeText(certificates)
                            cacheFiles.add(certFile)
                            env["SSL_CERT_FILE"] = certFile.absolutePath
                        }
                        processes.start(listOf(initPlugin("naive-plugin").path, configFile.absolutePath), env)
                    }

                    is HysteriaBean -> {
                        val configFile = File(cacheDir, "hysteria_${SystemClock.elapsedRealtime()}.json")
                        configFile.writeText(config)
                        cacheFiles.add(configFile)
                        val commands = mutableListOf(
                            initPlugin("hysteria-plugin").path,
                            "--no-check",
                            "--config",
                            configFile.absolutePath,
                            "--log-level",
                            if (DataStore.logLevel > 0) "trace" else "warn",
                            "client",
                        )
                        if (bean.protocol == HysteriaBean.PROTOCOL_FAKETCP) commands.addAll(0, listOf("su", "-c"))
                        processes.start(commands)
                    }
                }
            }
        }
        box.start()
    }

    private suspend fun pendingExternalPorts(ports: Collection<Int>, timeoutMillis: Long) = withContext(Dispatchers.IO) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val pending = ports.toMutableSet()
        while (pending.isNotEmpty() && SystemClock.elapsedRealtime() < deadline && processes.isActive) {
            ensureActive()
            if (!processes.isActive) break
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val port = iterator.next()
                try {
                    Socket().use { it.connect(InetSocketAddress(LOCALHOST, port), 100) }
                    iterator.remove()
                } catch (_: IOException) {
                }
            }
            if (pending.isNotEmpty()) {
                if (!processes.isActive) break
                delay(50)
            }
        }
        pending
    }

    suspend fun awaitExternalProcessesReady(strict: Boolean = false) {
        if (!::processes.isInitialized || processes.processCount == 0) return
        val ports = config.externalIndex.flatMap { it.chain.keys }.distinct()
        if (ports.isEmpty()) return
        val timeout = DataStore.connectionTestTimeout.toLong().coerceAtLeast(1_000L)
        val pending = pendingExternalPorts(ports, if (strict) minOf(2_000L, timeout) else timeout)
        if (pending.isEmpty()) return
        if (!processes.isActive) {
            Logs.w("sidecar listener not ready on port(s): ${pending.joinToString()}; process pool already stopped")
            return
        }
        val message = "sidecar listener not ready on port(s): ${pending.joinToString()}"
        if (strict) throw IOException(message)
        Logs.w("$message; continuing (sing-box will retry the connection)")
    }

    @Suppress("EXPERIMENTAL_API_USAGE")
    override fun close() {
        cacheFiles.removeAll {
            it.delete()
            true
        }
        if (::processes.isInitialized) processes.close(GlobalScope + Dispatchers.IO)
        if (::box.isInitialized) box.close()
    }
}
