package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

// TCP Ping：直接測 TCP 握手到節點伺服器的延遲（不經代理、不發 HTTP 請求）。
// 移植自 OwnBoxForAndroid 48cc1e43a；與 UrlTest 並存，走同一個
// connectionTestConcurrent worker pool 模式。
class TcpPing {

    private val timeout = DataStore.connectionTestTimeout

    suspend fun doTest(profile: ProxyEntity): Int = withContext(Dispatchers.IO) {
        val bean = profile.requireBean()
        val host = if (!bean.finalAddress.isNullOrBlank()) bean.finalAddress else bean.serverAddress
        val port = if (bean.finalPort != 0) bean.finalPort else (bean.serverPort ?: 443)

        if (host.isNullOrBlank() || port <= 0 || port > 65535) {
            error("Invalid host or port: $host:$port")
        }

        Logs.d("TcpPing ${profile.displayName()}: start, host=$host, port=$port, timeout=${timeout}ms")
        val socket = Socket()
        try {
            runCatching { SagerNet.underlyingNetwork?.bindSocket(socket) }
            runCatching { DataStore.vpnService?.protect(socket) }

            // InetSocketAddress(host, port) 會在構造時就做 DNS 解析，必須放在計時之外，
            // 否則域名型節點的「TCP 握手延遲」會把解析時間一併算進去而虛高。
            val address = InetSocketAddress(host, port)

            val startTime = SystemClock.elapsedRealtime()
            socket.connect(address, timeout)
            val latency = (SystemClock.elapsedRealtime() - startTime).toInt()
            Logs.d("TcpPing ${profile.displayName()}: done, latency=${latency}ms")
            latency
        } finally {
            runCatching { socket.close() }
        }
    }

    companion object {
        // 伺服器端根本沒有 TCP 監聽的傳輸。對它們做 TCP 握手探測必然一路等到 timeout，
        // 於是「節點是好的、TCP Ping 卻顯示失敗」——這是假故障，不是節點問題。
        // 只列已確認純 UDP/QUIC 的協定：把其實有 TCP 監聽的協定放進來會藏掉可用的測量結果。
        private val UDP_ONLY_TYPES = setOf(
            ProxyEntity.TYPE_WG,
            ProxyEntity.TYPE_AWG,
            ProxyEntity.TYPE_TUIC,
            ProxyEntity.TYPE_JUICITY,
            ProxyEntity.TYPE_SHADOWQUIC,
        )

        fun isTcpReachable(entity: ProxyEntity): Boolean {
            if (entity.type in UDP_ONLY_TYPES) return false
            return when (val bean = entity.requireBean()) {
                // HY2 恆為 QUIC；HY1 只有 tcp / 偽 TCP 模式才有真正的 TCP 監聽。
                is HysteriaBean -> bean.protocolVersion == 1 && bean.protocol != HysteriaBean.PROTOCOL_UDP
                else -> true
            }
        }
    }
}
