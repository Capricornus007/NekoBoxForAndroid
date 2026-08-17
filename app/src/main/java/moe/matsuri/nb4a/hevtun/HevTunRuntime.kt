package moe.matsuri.nb4a.hevtun

import android.content.Context
import io.nekohasekai.sagernet.IPv6Mode
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
        val useAuth = DataStore.mixedInboundHasAuth
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
            if (useAuth) {
                appendLine("  username: '${DataStore.mixedUsername.yamlEscape()}'")
                appendLine("  password: '${DataStore.mixedSecret.yamlEscape()}'")
            }
            appendLine("misc:")
            appendLine("  log-level: 'warn'")
        }
    }

    private fun String.yamlEscape(): String {
        return replace("'", "''")
    }
}
