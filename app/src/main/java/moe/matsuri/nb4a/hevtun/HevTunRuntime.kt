package moe.matsuri.nb4a.hevtun

import android.content.Context
import io.nekohasekai.sagernet.IPv6Mode
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import java.io.File

/**
 * Manages the in-process hev-socks5-tunnel runtime. The tunnel reads packets
 * from the VPN file descriptor and forwards TCP/UDP connections into the
 * sing-box mixed inbound on loopback.
 */
object HevTunRuntime {

    private const val CONFIG_FILE = "hev-socks5-tunnel.yaml"

    private var running = false

    fun isRunning(): Boolean {
        return try {
            HevTunNative.TProxyIsRunning()
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    @Synchronized
    fun start(context: Context, tunFd: Int) {
        stop()
        val configFile = File(context.filesDir, CONFIG_FILE)
        configFile.writeText(buildConfig())
        check(HevTunNative.TProxyStartService(configFile.absolutePath, tunFd)) {
            "Failed to start hev-socks5-tunnel"
        }
        running = true
    }

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            HevTunNative.TProxyStopService()
        } finally {
            running = false
        }
    }

    private fun buildConfig(): String {
        // Credentials must mirror the mixed inbound built in ConfigBuilder:
        // when the inbound requires auth it uses the fixed username constant
        // plus the generated secret, otherwise no credentials at all.
        val useAuth = DataStore.mixedInboundNeedsAuth
        return buildString {
            appendLine("tunnel:")
            appendLine("  mtu: ${DataStore.mtu}")
            appendLine("  ipv4: '${VpnService.PRIVATE_VLAN4_CLIENT}'")
            if (DataStore.ipv6Mode != IPv6Mode.DISABLE) {
                appendLine("  ipv6: '${VpnService.PRIVATE_VLAN6_CLIENT}'")
            }
            appendLine("socks5:")
            appendLine("  port: ${DataStore.mixedPort}")
            appendLine("  address: '$LOCALHOST'")
            appendLine("  udp: 'udp'")
            appendLine("  pipeline: true")
            if (useAuth) {
                appendLine("  username: '${Key.MIXED_USERNAME.yamlEscape()}'")
                appendLine("  password: '${DataStore.mixedSecret.yamlEscape()}'")
            }
            // mapdns 是 hev 模式唯一的 DNS 引擎：sing-box TUN 的「網關地址自動
            // 應答 DNS」在 mixed 入站下不存在，若關掉 mapdns，應用發往
            // 172.19.0.2:53 的查詢會被原樣轉發成一個發往 VPN 內部地址的 UDP，
            // 直接石沉大海（實機故障：DNS 全滅、萬物皆斷）。所以永遠啟用，
            // 與 FakeDNS 開關解耦。
            appendLine("mapdns:")
            appendLine("  address: '${VpnService.PRIVATE_VLAN4_ROUTER}'")
            appendLine("  port: 53")
            appendLine("  network: '${VpnService.HEV_MAPDNS_VLAN4}'")
            appendLine("  netmask: '255.192.0.0'")
            appendLine("  cache-size: 10000")
            appendLine("misc:")
            appendLine("  log-level: 'warn'")
        }
    }

    private fun String.yamlEscape(): String {
        return replace("'", "''")
    }
}
