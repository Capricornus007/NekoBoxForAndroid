package io.nekohasekai.sagernet.root

import android.content.Context
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Installs iptables/ip6tables rules to route Wi-Fi hotspot and USB-tethered clients
 * through the existing VpnService tun. No additional hev-socks5-tunnel process is needed.
 */
object RootLanSharing {

    private const val TABLE = 1640 // NekoBox VpnService routing table
    private const val FWMARK = 0xCAFE
    private const val MARK = 0xCAFE

    private val lanCidrs = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")
    private val bypassCidrsV6 = listOf("::1/128", "fe80::/10", "fc00::/7", "ff00::/8")

    @Volatile
    var started = false
        private set

    private var lanShareJob: Job? = null

    fun startClientSharing(context: Context): Boolean {
        Logs.d("RootLanSharing.startClientSharing: lanSharing=${DataStore.lanSharing}")
        if (!DataStore.lanSharing) return true
        if (lanShareJob != null) return false

        if (!RootManager.cachedRoot()) {
            RootManager.isRootAvailable(forceRefresh = true)
        }
        if (!RootManager.cachedRoot()) {
            Logs.w("RootLanSharing: root not available")
            return true
        }

        lanShareJob = CoroutineScope(Dispatchers.IO).launch {
            val result = setupClientSharing(context)
            if (!result) stopClientSharing(context)
        }
        return true
    }

    fun stopClientSharing(context: Context) {
        if (!started) return
        runBlocking { lanShareJob?.cancelAndJoin() }
        lanShareJob = null
        teardown(context)
        started = false
        Logs.i("RootLanSharing: stopped")
    }

    private suspend fun setupClientSharing(context: Context): Boolean {
        Logs.d("RootLanSharing.setupClientSharing: starting")
        val ipv6 = DataStore.ipv6Mode != 0
        val dns = resolveDns(DataStore.remoteDns)
        val tunName = getVpnTunName() ?: "tun0"

        val script = buildString {
            appendLine("set -e")
            appendLine("TUN=$tunName")

            // ip_forward
            appendLine("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true")

            // FORWARD chain
            appendLine("iptables -N CORE_FWD 2>/dev/null || true")
            appendLine("iptables -F CORE_FWD")
            appendLine("iptables -A CORE_FWD -i \$TUN -j ACCEPT")
            appendLine("iptables -A CORE_FWD -o \$TUN -j ACCEPT")
            appendLine("iptables -D FORWARD -j CORE_FWD 2>/dev/null || true")
            appendLine("iptables -I FORWARD -j CORE_FWD")

            // MSS clamp
            appendLine(
                "iptables -t mangle -D FORWARD -o \$TUN -p tcp --tcp-flags SYN,RST SYN" +
                    " -j TCPMSS --set-mss 1350 2>/dev/null || true"
            )
            appendLine(
                "iptables -t mangle -A FORWARD -o \$TUN -p tcp --tcp-flags SYN,RST SYN" +
                    " -j TCPMSS --set-mss 1350"
            )

            // DNS DNAT
            appendLine("iptables -t nat -N CORE_DNS 2>/dev/null || true")
            appendLine("iptables -t nat -F CORE_DNS")
            lanCidrs.forEach { cidr ->
                appendLine("iptables -t nat -A CORE_DNS ! -i \$TUN -d $cidr -p udp --dport 53 -j DNAT --to $dns")
            }
            appendLine("iptables -t nat -D PREROUTING -j CORE_DNS 2>/dev/null || true")
            appendLine("iptables -t nat -A PREROUTING -j CORE_DNS")

            // Policy routing
            appendLine("ip rule add iif \$TUN lookup main suppress_prefixlength 0 pref 5010 2>/dev/null || true")
            appendLine("ip rule add iif \$TUN goto 6000 pref 5020 2>/dev/null || true")
            appendLine("ip rule add from 10.0.0.0/8 lookup $TABLE pref 5030 2>/dev/null || true")
            appendLine("ip rule add from 172.16.0.0/12 lookup $TABLE pref 5040 2>/dev/null || true")
            appendLine("ip rule add from 192.168.0.0/16 lookup $TABLE pref 5050 2>/dev/null || true")

            // IPv6
            appendLine("ip6tables -N CORE6_FWD 2>/dev/null || true")
            appendLine("ip6tables -F CORE6_FWD")
            appendLine("ip6tables -D FORWARD -j CORE6_FWD 2>/dev/null || true")
            appendLine("ip6tables -I FORWARD -j CORE6_FWD")
            if (ipv6) {
                appendLine("ip6tables -A CORE6_FWD -i \$TUN -j ACCEPT")
                appendLine("ip6tables -A CORE6_FWD -o \$TUN -j ACCEPT")
                appendLine("ip6tables -t mangle -N CORE6_PRE 2>/dev/null || true")
                appendLine("ip6tables -t mangle -F CORE6_PRE")
                appendLine("ip6tables -t mangle -A CORE6_PRE ! -i \$TUN -p udp --dport 53 -j MARK --set-xmark $MARK")
                appendLine("ip6tables -t mangle -A CORE6_PRE ! -i \$TUN -p tcp --dport 53 -j MARK --set-xmark $MARK")
                appendLine("ip6tables -t mangle -A CORE6_PRE ! -i \$TUN -j MARK --set-xmark $MARK")
                appendLine("ip6tables -t mangle -D PREROUTING -j CORE6_PRE 2>/dev/null || true")
                appendLine("ip6tables -t mangle -A PREROUTING -j CORE6_PRE")
                appendLine("ip6tables -A CORE6_FWD -j REJECT --reject-with icmp6-no-route")
            } else {
                appendLine("ip6tables -A CORE6_FWD -j REJECT --reject-with icmp6-no-route")
            }
        }

        val result = RootShell.exec(script)
        if (!result.success) {
            Logs.e("RootLanSharing: setup failed:\n${result.output}")
            teardown(context)
            return false
        }

        started = true
        Logs.d("RootLanSharing: started on $tunName")
        return true
    }

    private fun teardown(context: Context) {
        val tunName = getVpnTunName() ?: "tun0"
        RootShell.exec(buildString {
            appendLine("TUN=$tunName")
            // Remove routing rules
            for (pref in listOf(5010, 5020, 5030, 5040, 5050)) {
                appendLine("ip rule del pref $pref 2>/dev/null || true")
            }
            // Remove iptables chains
            appendLine("iptables -D FORWARD -j CORE_FWD 2>/dev/null || true")
            appendLine("iptables -F CORE_FWD 2>/dev/null || true")
            appendLine("iptables -X CORE_FWD 2>/dev/null || true")
            appendLine("iptables -t mangle -D FORWARD -o \$TUN -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350 2>/dev/null || true")
            appendLine("iptables -t nat -D PREROUTING -j CORE_DNS 2>/dev/null || true")
            appendLine("iptables -t nat -F CORE_DNS 2>/dev/null || true")
            appendLine("iptables -t nat -X CORE_DNS 2>/dev/null || true")
            appendLine("ip6tables -D FORWARD -j CORE6_FWD 2>/dev/null || true")
            appendLine("ip6tables -F CORE6_FWD 2>/dev/null || true")
            appendLine("ip6tables -X CORE6_FWD 2>/dev/null || true")
            appendLine("ip6tables -t mangle -D PREROUTING -j CORE6_PRE 2>/dev/null || true")
            appendLine("ip6tables -t mangle -F CORE6_PRE 2>/dev/null || true")
            appendLine("ip6tables -t mangle -X CORE6_PRE 2>/dev/null || true")
        })
    }

    private fun getVpnTunName(): String? {
        // Find the tun device that has the NekoBox routing table
        return try {
            val output = RootShell.exec("ip route show table $TABLE 2>/dev/null | head -1").output
            val parts = output.trim().split("dev")
            if (parts.size > 1) parts[1].trim().split(" ").firstOrNull() else null
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDns(remoteDns: String): String {
        if (remoteDns.startsWith("host:") || remoteDns.startsWith("tcp:")) {
            val ip = remoteDns.substringAfter("://").substringBefore(":")
            if (ip.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) return ip
        }
        return "1.1.1.1"
    }
}