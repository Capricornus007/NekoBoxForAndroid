package io.nekohasekai.sagernet.root

import android.os.Process
import io.nekohasekai.sagernet.ktx.Logs

/**
 * eBPF 模式的流量重定向規則。
 *
 * 為什麼是 tproxy + root 而不是 sing-box 的 `type: ebpf` 入站：
 * sing-box 的 ebpf 入站要自己操作 cgroup v2／TC，需要「執行 core 的進程」有
 * CAP_NET_ADMIN。本專案的 core 是 libcore(gomobile) 跑在 app 進程內，app 永遠是
 * u0_axxx；folkpatch/apatch 給的是「呼叫 su 起新進程」的權限，不會把 app 進程提權。
 * 所以改成：core 用普通的 `tproxy` 入站（不需要 root），root 這一次只負責裝規則。
 *
 * 規則形狀跟 RootLanSharing 同一套（RootShell 跑一段 sh），重點：
 * - fwmark 0x1 → 路由表 100 → `local ... dev lo`，把標記過的封包交回本機；
 * - mangle 的 PREROUTING（其他 app 進來）與 OUTPUT（本機出去的）各掛一條自訂鏈；
 * - 鏈裡先 RETURN 掉「自己的 uid」（不然 core 自己連出去又被抓回來，無限迴圈）、
 *   回環、私有網段、組播；
 * - 其餘 TCP/UDP 全部 TPROXY 到 core 的 tproxy 埠。
 *
 * 這個物件只「裝/拆規則」，不知道服務何時該啟動；呼叫端負責在連線建立後 install、
 * 停止時 uninstall。
 */
object RootTProxyRules {

    private const val ROUTE_TABLE = 100
    private const val FWMARK = 1

    private const val CHAIN4 = "CORE_TPROXY"
    private const val CHAIN6 = "CORE_TPROXY6"

    private val bypassV4 = listOf(
        "0.0.0.0/8",
        "10.0.0.0/8",
        "127.0.0.0/8",
        "169.254.0.0/16",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "224.0.0.0/4",
        "240.0.0.0/4",
    )
    private val bypassV6 = listOf("::1/128", "fe80::/10", "fc00::/7", "ff00::/8")

    @Volatile
    private var installedPort: Int = 0

    /** 目前是否已裝上（回傳已安裝的埠，0 = 沒裝）。 */
    val installed: Int get() = installedPort

    fun install(port: Int): Boolean {
        if (port <= 0) {
            Logs.e("RootTProxyRules: invalid port $port")
            return false
        }
        if (installedPort == port) return true
        if (installedPort != 0) uninstall()

        val uid = Process.myUid()
        val mark = "$FWMARK/$FWMARK"
        val script = buildString {
            appendLine("set -e")
            // ── 路由：標記過的封包交回本機（tproxy 的必要條件）──────
            appendLine("ip rule add fwmark $mark lookup $ROUTE_TABLE 2>/dev/null || true")
            appendLine("ip route add local 0.0.0.0/0 dev lo table $ROUTE_TABLE 2>/dev/null || true")
            appendLine("ip -6 rule add fwmark $mark lookup $ROUTE_TABLE 2>/dev/null || true")
            appendLine("ip -6 route add local ::/0 dev lo table $ROUTE_TABLE 2>/dev/null || true")

            // ── IPv4 ────────────────────────────────────────────
            appendLine("iptables -w -t mangle -N $CHAIN4 2>/dev/null || true")
            appendLine("iptables -w -t mangle -F $CHAIN4")
            appendLine("iptables -w -t mangle -A $CHAIN4 -m owner --uid-owner $uid -j RETURN")
            bypassV4.forEach { cidr ->
                appendLine("iptables -w -t mangle -A $CHAIN4 -d $cidr -j RETURN")
            }
            appendLine("iptables -w -t mangle -A $CHAIN4 -p tcp -j TPROXY --on-port $port --tproxy-mark $mark")
            appendLine("iptables -w -t mangle -A $CHAIN4 -p udp -j TPROXY --on-port $port --tproxy-mark $mark")
            appendLine("iptables -w -t mangle -D PREROUTING -j $CHAIN4 2>/dev/null || true")
            appendLine("iptables -w -t mangle -A PREROUTING -j $CHAIN4")
            appendLine("iptables -w -t mangle -D OUTPUT -j $CHAIN4 2>/dev/null || true")
            appendLine("iptables -w -t mangle -A OUTPUT -j $CHAIN4")

            // ── IPv6（裝置沒開 v6 時這段會自然失敗，用 || true 放行）──
            appendLine("ip6tables -w -t mangle -N $CHAIN6 2>/dev/null || true")
            appendLine("ip6tables -w -t mangle -F $CHAIN6 2>/dev/null || true")
            appendLine("ip6tables -w -t mangle -A $CHAIN6 -m owner --uid-owner $uid -j RETURN 2>/dev/null || true")
            bypassV6.forEach { cidr ->
                appendLine("ip6tables -w -t mangle -A $CHAIN6 -d $cidr -j RETURN 2>/dev/null || true")
            }
            appendLine("ip6tables -w -t mangle -A $CHAIN6 -p tcp -j TPROXY --on-port $port --tproxy-mark $mark 2>/dev/null || true")
            appendLine("ip6tables -w -t mangle -A $CHAIN6 -p udp -j TPROXY --on-port $port --tproxy-mark $mark 2>/dev/null || true")
            appendLine("ip6tables -w -t mangle -D PREROUTING -j $CHAIN6 2>/dev/null || true")
            appendLine("ip6tables -w -t mangle -A PREROUTING -j $CHAIN6 2>/dev/null || true")
            appendLine("ip6tables -w -t mangle -D OUTPUT -j $CHAIN6 2>/dev/null || true")
            appendLine("ip6tables -w -t mangle -A OUTPUT -j $CHAIN6 2>/dev/null || true")
        }

        val result = RootShell.exec(script, timeoutSeconds = 20)
        if (!result.success) {
            // 失敗要把 stderr 留給使用者看，不然只會看到「連上了但沒流量」。
            Logs.e("RootTProxyRules: install failed (port=$port): ${result.output.trim()}")
            // 收拾殘局，避免留下半套規則。
            runCatching { RootShell.exec(uninstallScript(), timeoutSeconds = 10) }
            return false
        }
        installedPort = port
        Logs.d("RootTProxyRules: installed tproxy port $port (uid $uid excluded)")
        return true
    }

    fun uninstall() {
        if (installedPort == 0) return
        val result = RootShell.exec(uninstallScript(), timeoutSeconds = 10)
        if (!result.success) {
            Logs.w("RootTProxyRules: uninstall incomplete: ${result.output.trim()}")
        }
        installedPort = 0
        Logs.d("RootTProxyRules: uninstalled")
    }

    private fun uninstallScript(): String = buildString {
        appendLine("set +e")
        listOf("iptables", "ip6tables").forEach { ipt ->
            listOf("PREROUTING", "OUTPUT").forEach { hook ->
                appendLine("$ipt -w -t mangle -D $hook -j $CHAIN4 2>/dev/null")
                appendLine("$ipt -w -t mangle -D $hook -j $CHAIN6 2>/dev/null")
            }
            appendLine("$ipt -w -t mangle -F $CHAIN4 2>/dev/null")
            appendLine("$ipt -w -t mangle -X $CHAIN4 2>/dev/null")
            appendLine("$ipt -w -t mangle -F $CHAIN6 2>/dev/null")
            appendLine("$ipt -w -t mangle -X $CHAIN6 2>/dev/null")
        }
        // 路由規則逐條刪，直到刪不動為止（避免殘留重複項）
        appendLine("while ip rule del fwmark $FWMARK/$FWMARK lookup $ROUTE_TABLE 2>/dev/null; do :; done")
        appendLine("ip route flush table $ROUTE_TABLE 2>/dev/null")
        appendLine("while ip -6 rule del fwmark $FWMARK/$FWMARK lookup $ROUTE_TABLE 2>/dev/null; do :; done")
        appendLine("ip -6 route flush table $ROUTE_TABLE 2>/dev/null")
        appendLine("true")
    }
}
