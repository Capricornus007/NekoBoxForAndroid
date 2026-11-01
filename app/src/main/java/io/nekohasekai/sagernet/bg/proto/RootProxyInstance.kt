package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.delay
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * eBPF 模式專用：core 由 root 外置 sing-box 二進制承載（assets/sing-box-bin/sing-box-arm64），
 * 以 su 啟動的 root 進程有 CAP_NET_ADMIN，`type: ebpf`（內核 cgroup/TC 程式）可正常 attach。
 * in-process libcore 永遠是 u0_axxx，綁 tproxy/ebpf 埠會 EPERM（#83）——所以本類別完全不啟
 * libcore：close()/cancelStart() 都有 ::box.isInitialized 守衛，box 未初始化時是安全 no-op；
 * closeAndPersist() 走 ProxyInstance 原路（looper 為 null）。
 *
 * 不會產生多餘進程：eBPF 模式只有這一個 root core 進程，停止時 destroy()。
 */
class RootProxyInstance(
    profile: ProxyEntity,
    service: BaseService.Interface? = null,
) : ProxyInstance(profile, service) {

    private var rootProcess: Process? = null

    override suspend fun loadConfig() {
        val app = SagerNet.application
        val bin = File(app.filesDir, BIN_NAME)
        if (!bin.exists() || bin.length() != ASSET_SIZE) {
            app.assets.open(ASSET_PATH).use { input ->
                bin.outputStream().use { output -> input.copyTo(output) }
            }
        }
        bin.setExecutable(true, false)
        val cfg = File(app.filesDir, CONFIG_NAME)
        // config 已由 ConfigBuilder 產生，eBPF 入站為 `type: ebpf`。
        cfg.writeText(config.config)

        rootProcess?.destroy()
        val cmd =
            "chmod 755 '${bin.absolutePath}' && exec '${bin.absolutePath}' run -c '${cfg.absolutePath}'"
        Logs.d("RootProxyInstance: starting root eBPF core")
        rootProcess = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
        awaitRootCoreReady()
        Logs.d("RootProxyInstance: root eBPF core ready")
    }

    private suspend fun awaitRootCoreReady() {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (PORTS.any { isPortOpen(it) }) return
            delay(200)
        }
        throw IllegalStateException(
            "root eBPF core did not become ready in ${READY_TIMEOUT_MS / 1000}s",
        )
    }

    private fun isPortOpen(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), 300); true }
    } catch (_: Exception) {
        false
    }

    override fun launch() {
        // 不啟 in-process libcore；TrafficLooper 依賴 libcore box，eBPF 暫不起（後續接 clash API）。
        looper = null
    }

    companion object {
        private const val ASSET_PATH = "sing-box-bin/sing-box-arm64"
        private const val BIN_NAME = "sing-box-ebpf"
        private const val CONFIG_NAME = "sing-box-ebpf.json"
        private const val READY_TIMEOUT_MS = 10_000L
        private const val LOOPBACK = "127.0.0.1"

        // mixed(2080) 與 clash API(9090)：外置 core 用同一份 config，埠與 in-process 版一致。
        private val PORTS = intArrayOf(2080, 9090)

        // assets 內二進制大小；不一致就強制重新解壓。
        private const val ASSET_SIZE = 56_164_646L
    }
}
