package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.root.RootShell
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.ArrayDeque
import kotlin.concurrent.thread

/**
 * eBPF 模式專用：core 由 root 外置 sing-box 二進制承載（包內 nativeLibraryDir/libsingbox.so），
 * 以 su 啟動的 root 進程有 CAP_NET_ADMIN，`type: ebpf`（內核 cgroup/TC 程式）可正常 attach。
 * in-process core 永遠是 u0_axxx，綁 tproxy/ebpf 埠會 EPERM（#83），所以資料平面只跑
 * 外置 core；gomobile libcore 僅負責把 geoip:/geosite: 偽路徑轉成外置 core 可讀的 SRS。
 * cancelStart()/closeAndPersist() 都會用 root PID 收掉該進程，輸出則持續排空並保留有限尾段。
 * eBPF 模式因此只有一個資料平面 core，不會留下阻塞 pipe 或孤兒 root 進程。
 */
class RootProxyInstance(
    profile: ProxyEntity,
    service: BaseService.Interface? = null,
) : ProxyInstance(profile, service) {

    private var rootProcess: Process? = null
    private var outputThread: Thread? = null
    private val outputLock = Any()
    private val outputTail = ArrayDeque<String>()

    override suspend fun loadConfig() {
        val app = SagerNet.application
        // 直接取包內那顆：buildScript/lib/singbox-sidecar.sh 把 sing-box 交叉編成
        // app/executableSo/<abi>/libsingbox.so，Gradle 再按 ABI 打进 nativeLibraryDir，
        // 所以它跟這個 APK 是同一次建置的產物，不會錯版。
        // 之前那條路是把 arm64 二進位塞進 assets 再複製到 filesDir，還用寫死的
        // ASSET_SIZE 判斷要不要重拷——sidecar 更新後體積一定變，舊 gate 卻認得舊數字，
        // 於是 root 跑的是上一版 core，報出來的錯跟代碼對不上（查了很久才定位到這裡）。
        val bin = File(app.applicationInfo.nativeLibraryDir, LIB_NAME)
        if (!bin.canRead()) error("包內找不到 ${'$'}LIB_NAME（${'$'}{bin.absolutePath}），eBPF 模式無法啟動")
        stopRootProcess()
        val cfg = File(app.filesDir, CONFIG_NAME)
        // The in-process core prepares legacy geoip:/geosite: pseudo paths inside box.New.
        // The standalone root binary has no gomobile callbacks, so materialize the same SRS
        // caches and serialize their absolute paths before handing the config across `su`.
        cfg.writeText(Libcore.prepareExternalSingBoxConfig(config.config, LocalResolverImpl))

        synchronized(outputLock) { outputTail.clear() }
        val pidFile = File(app.filesDir, PID_NAME).apply { delete() }
        val cmd =
            "echo \$\$ > '${pidFile.absolutePath}' && " +
                "exec '${bin.absolutePath}' run -c '${cfg.absolutePath}'"
        Logs.d("RootProxyInstance: starting root eBPF core")
        val process = ProcessBuilder("su", "-c", cmd)
            // Config fields such as ../cache/cache.db are relative to the app files directory.
            // Android app processes normally have `/` as cwd; leaving it implicit creates
            // `/cache/cache.db` and turns the next startup stage into another false timeout.
            .directory(app.filesDir)
            .redirectErrorStream(true)
            .start()
        rootProcess = process
        outputThread = thread(start = true, isDaemon = true, name = "nb4a-ebpf-core-log") {
            // stopRootProcess()/cancelStart() 會 process.destroy()，此時這條 read 正在阻塞，
            // Linux 層抛的是 InterruptedIOException（IOException 的子類）。這是在背景執行緒上，
            // 沒接住就會走 Android 預設的 uncaught handler 直接把整個 app 殺掉——
            // 實測崩潰日誌：RootProxyInstance.kt:225 java.io.InterruptedIOException:
            // read interrupted by close() on another thread。排空日誌不是關鍵路徑，收口就好。
            try {
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach(::recordCoreOutput)
                }
            } catch (e: IOException) {
                Logs.d("RootProxyInstance: core log drain ended: ${e.message}")
            }
        }
        awaitRootCoreReady(process)
        Logs.d("RootProxyInstance: root eBPF core ready")
    }

    private suspend fun awaitRootCoreReady(process: Process) {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            processExitCode(process)?.let { code ->
                throw IllegalStateException(
                    "root eBPF core exited with code $code before ready: ${coreOutputTail()}",
                )
            }
            if (PORTS.any { isPortOpen(it) }) return
            delay(200)
        }
        throw IllegalStateException(
            "root eBPF core did not become ready in ${READY_TIMEOUT_MS / 1000}s: ${coreOutputTail()}",
        )
    }

    private fun recordCoreOutput(line: String) {
        Logs.d("root eBPF core: $line")
        synchronized(outputLock) {
            if (outputTail.size == OUTPUT_TAIL_LINES) outputTail.removeFirst()
            outputTail.addLast(line)
        }
    }

    private fun coreOutputTail(): String = synchronized(outputLock) {
        outputTail.joinToString(" | ").ifEmpty { "no output" }
    }

    private fun processExitCode(process: Process): Int? = try {
        process.exitValue()
    } catch (_: IllegalThreadStateException) {
        null
    }

    @Synchronized
    private fun stopRootProcess() {
        val process = rootProcess
        rootProcess = null
        val pidFile = File(SagerNet.application.filesDir, PID_NAME)
        val pid = runCatching { pidFile.readText().trim().toLong() }.getOrNull()
        if (pid != null) {
            val bin = File(SagerNet.application.applicationInfo.nativeLibraryDir, LIB_NAME).absolutePath
            // A stale PID must never kill an unrelated process after Android reuses the number.
            RootShell.exec(
                "test \"\$(readlink /proc/$pid/exe 2>/dev/null)\" = '$bin' && kill -TERM $pid || true",
                timeoutSeconds = 2,
            )
        }
        process?.destroy()
        outputThread?.join(OUTPUT_DRAIN_JOIN_MS)
        outputThread = null
        pidFile.delete()
    }

    override fun cancelStart() {
        try {
            stopRootProcess()
        } finally {
            super.cancelStart()
        }
    }

    override suspend fun closeAndPersist() {
        try {
            // 先把還活著的連線結轉进總量，再讓 TrafficLooper 停下來寫 DB；
            // 順序反了的話最後一段在途位元組會留在 live 裡沒人結轉。
            runCatching { trafficTracker.flush() }
            super.closeAndPersist()
        } finally {
            stopRootProcess()
        }
    }

    private fun isPortOpen(port: Int): Boolean = try {
        Socket().use {
            it.connect(InetSocketAddress(LOOPBACK, port), 300)
            true
        }
    } catch (_: Exception) {
        false
    }

    // eBPF 模式刻意不建 in-process core（#83：u0_axxx 綁 tproxy/ebpf 埠會 EPERM），
    // 所以 box 永遠不初始化。原先 isInitialized() 只認 box，於是 runningProxy() 直接
    // 拋「core not started」，測速與連線狀態全線失敗——外置 core 明明在跑也一樣。
    // 不用 Process.isAlive：那要 API 26，本包 minSdk 更低，lint 的 NewApi 直接擋下來；
    // exitValue() 在進程還活著時抛 IllegalThreadStateException，這是 API 1 就有的行為。
    override fun isInitialized(): Boolean =
        isConfigReady() && rootProcess?.let { processExitCode(it) == null } == true

    // 測速改問外置 core 自己，不假造一個 box：
    //   * 指定節點／分組：Clash API 的 /proxies/<tag>/delay（9090，帶 lazy 生成的 secret）
    //   * 整體連通性：走包內 mixed 入口（2080，設定裡沒開認證）實測一次 HTTP
    fun clashDelay(tag: String, url: String, timeoutMs: Int): Int {
        val secret = DataStore.clashApiSecret
        val target = "http://$LOOPBACK:$CLASH_PORT/proxies/" +
            URLEncoder.encode(tag, "UTF-8") +
            "/delay?url=" + URLEncoder.encode(url, "UTF-8") + "&timeout=" + timeoutMs
        val conn = URL(target).openConnection() as HttpURLConnection
        conn.connectTimeout = timeoutMs + 2000
        conn.readTimeout = timeoutMs + 2000
        if (secret.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $secret")
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val delay = org.json.JSONObject(body).optInt("delay", -1)
            if (delay < 0) error("clash delay 無效: $body")
            return delay
        } finally {
            conn.disconnect()
        }
    }

    fun mixedDelay(url: String, timeoutMs: Int): Int {
        val conn = URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress(LOOPBACK, MIXED_PORT)))
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        val started = System.currentTimeMillis()
        try {
            val code = (conn as? HttpURLConnection)?.responseCode ?: conn.contentLength
            if (code >= 400) error("connection test 回 $code")
            return (System.currentTimeMillis() - started).toInt()
        } finally {
            (conn as? HttpURLConnection)?.disconnect()
        }
    }

    override fun launch() {
        looper = service?.let { TrafficLooper(it.data, GlobalScope) }
        looper?.start()
    }

    // 外置 core 沒有 in-process 計數器，流量改由它自己開的 Clash API 供；回 null 才會走
    // 原本 box.queryStats 那條路，所以只在外置 core 真的活著時接管。
    override fun trafficQueryOverride(): ((String, String) -> Long)? =
        if (isInitialized()) trafficTracker::queryStats else null

    private val trafficTracker by lazy {
        ClashTrafficTracker(port = CLASH_PORT, secret = { DataStore.clashApiSecret })
    }

    companion object {
        private const val LIB_NAME = "libsingbox.so"
        private const val PID_NAME = "sing-box-ebpf.pid"
        private const val CONFIG_NAME = "sing-box-ebpf.json"
        private const val READY_TIMEOUT_MS = 10_000L
        private const val OUTPUT_DRAIN_JOIN_MS = 1_000L
        private const val OUTPUT_TAIL_LINES = 32
        private const val LOOPBACK = "127.0.0.1"
        private val PORTS = intArrayOf(2080, 9090)
        private const val MIXED_PORT = 2080
        private const val CLASH_PORT = 9090
    }
}
