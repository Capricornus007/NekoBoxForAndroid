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
 * through the existing VpnService tun. Traffic is identified by source IP CIDR ranges.
 * No additional hev-socks5-tunnel process is needed.
 */
object RootLanSharing {

    private const val TABLE = 1640 // NekoBox VpnService routing table
    private const val MARK = 0xCAFE

    private val lanCidrs = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")

    @Volatile
    var started = false
        private set

    private var lanShareJob: Job? = null

    fun startClientSharing(context: Context): Boolean {
        Logs.d("RootLanSharing.startClientSharing: lanSharing=${DataStore.lanSharing}, job=${lanShareJob?.isActive}, started=$started")
        if (!DataStore.lanSharing) {
            Logs.i("RootLanSharing: lan sharing disabled in settings, skipping")
            return true
        }
        if (lanShareJob != null) {
            Logs.w("RootLanSharing: already starting or started (job still active)")
            return false
        }
        if (started) {
            Logs.w("RootLanSharing: already started, refusing restart")
            return false
        }

        if (!RootManager.cachedRoot()) {
            Logs.d("RootLanSharing: root not cached, forcing refresh...")
            RootManager.isRootAvailable(forceRefresh = true)
        }
        if (!RootManager.cachedRoot()) {
            Logs.w("RootLanSharing: root not available after refresh")
            return true
        }
        Logs.i("RootLanSharing: root confirmed, starting iptables setup...")

        started = true
        lanShareJob = CoroutineScope(Dispatchers.IO).launch {
            val result = setupClientSharing(context)
            if (!result) {
                started = false
                stopClientSharing(context)
            }
        }
        return true
    }

    fun stopClientSharing(context: Context) {
        if (!started) return
        Logs.i("RootLanSharing.stopClientSharing: cleaning up...")
        runBlocking { lanShareJob?.cancelAndJoin() }
        lanShareJob = null
        teardown(context)
        started = false
        Logs.i("RootLanSharing: stopped")
    }

    /**
     * Public teardown that uses the best available tun name for cleanup.
     */
    private fun teardown(context: Context) {
        val tunName = getVpnTunName() ?: "tun0"
        teardownRules(tunName)
    }

    private suspend fun setupClientSharing(context: Context): Boolean {
        Logs.d("RootLanSharing.setupClientSharing: starting")
        val ipv6 = DataStore.ipv6Mode != 0
        val dns = resolveDns(DataStore.remoteDns)
        val tunName = getVpnTunName()
        if (tunName == null) {
            Logs.w("RootLanSharing: could not determine tun name, falling back to tun0")
        }
        val tunFinal = tunName ?: "tun0"
        Logs.d("RootLanSharing: tunName=$tunFinal, ipv6=$ipv6, dns=$dns")

        // Pre-cleanup
        Logs.d("RootLanSharing: pre-cleanup before setup...")
        teardownRules(tunFinal)
        Logs.d("RootLanSharing: pre-cleanup done")

        val script = buildString {
            appendLine("set -e")
            appendLine("TUN=$tunFinal")

            // ip_forward
            appendLine("echo 1 > /proc/sys/net/ipv4/ip_forward 2>/dev/null || true")
            if (ipv6) {
                appendLine("echo 1 > /proc/sys/net/ipv6/conf/all/forwarding 2>/dev/null || true")
            }

            // FORWARD chain
            appendLine("iptables -N CORE_FWD 2>/dev/null || true")
            appendLine("iptables -F CORE_FWD")
            appendLine("iptables -A CORE_FWD -i \$TUN -j ACCEPT")
            appendLine("iptables -A CORE_FWD -o \$TUN -j ACCEPT")
            appendLine("iptables -D FORWARD -j CORE_FWD 2>/dev/null || true")
            appendLine("iptables -I FORWARD -j CORE_FWD")

            // MSS clamp
            appendLine("iptables -t mangle -D FORWARD -o \$TUN -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350 2>/dev/null || true")
            appendLine("iptables -t mangle -A FORWARD -o \$TUN -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350")

            // DNS DNAT: redirect LAN clients' DNS queries to configured DNS
            appendLine("iptables -t nat -N CORE_DNS 2>/dev/null || true")
            appendLine("iptables -t nat -F CORE_DNS")
            lanCidrs.forEach { cidr ->
                appendLine("iptables -t nat -A CORE_DNS ! -i \$TUN -d $cidr -p udp --dport 53 -j DNAT --to $dns")
                appendLine("iptables -t nat -A CORE_DNS ! -i \$TUN -d $cidr -p tcp --dport 53 -j DNAT --to $dns")
            }
            appendLine("iptables -t nat -D PREROUTING -j CORE_DNS 2>/dev/null || true")
            appendLine("iptables -t nat -A PREROUTING -j CORE_DNS")

            // MASQUERADE for LAN clients
            lanCidrs.forEach { cidr ->
                appendLine("iptables -t nat -A POSTROUTING -s $cidr ! -d $cidr -j MASQUERADE 2>/dev/null || true")
            }

            // Mark LAN client packets in PREROUTING (before routing decision)
            // Phone's own traffic doesn't go through PREROUTING, so it won't be marked
            // Hotspot client DNS queries go to local gateway addr first, then DNAT'd after marking
            appendLine("iptables -t mangle -N CORE_MARK 2>/dev/null || true")
            appendLine("iptables -t mangle -F CORE_MARK")
            lanCidrs.forEach { cidr ->
                appendLine("iptables -t mangle -A CORE_MARK ! -i \$TUN -s $cidr -j MARK --set-xmark $MARK")
            }
            appendLine("iptables -t mangle -D PREROUTING -j CORE_MARK 2>/dev/null || true")
            appendLine("iptables -t mangle -A PREROUTING -j CORE_MARK")

            // Policy routing: marked packets -> table 1640 -> tun
            appendLine("ip rule add fwmark $MARK lookup $TABLE pref 5030 2>/dev/null || true")

            // Ensure table 1640 has routes for the tun interface
            appendLine("ip route add local 172.19.0.2 dev \$TUN table $TABLE 2>/dev/null || true")
            appendLine("ip route add default dev \$TUN table $TABLE 2>/dev/null || true")

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

        Logs.d("RootLanSharing: executing iptables rules for $tunFinal...")
        val result = RootShell.exec(script)
        if (!result.success) {
            Logs.e("RootLanSharing: iptables setup FAILED:\n${result.output}")
            teardown(context)
            return false
        }
        Logs.d("RootLanSharing: iptables rules applied successfully")

        val verify1 = RootShell.exec("iptables -t nat -L PREROUTING -n | grep -c 'CORE_DNS'")
        val verify2 = RootShell.exec("iptables -t nat -L POSTROUTING -n | grep -c 'MASQUERADE'")
        val verifyTable = RootShell.exec("ip route show table $TABLE")
        Logs.d("RootLanSharing: verify DNS rules=${verify1.output.trim()}, verify MASQ rules=${verify2.output.trim()}, verify table 1640=${verifyTable.output.trim()}")

        Logs.i("RootLanSharing: ✅ rules applied on $tunFinal (ipv6=$ipv6, dns=$dns)")
        return true
    }

    /**
     * Clean up all CORE_* rules created by this module.
     * Uses flush-style cleanup to handle duplicate rules from previous connections.
     */
    private fun teardownRules(tunName: String) {
        val script = buildString {
            appendLine("TUN=$tunName")

            // Flush and remove CORE_FWD chain
            appendLine("iptables -D FORWARD -j CORE_FWD 2>/dev/null || true")
            appendLine("iptables -F CORE_FWD 2>/dev/null || true")
            appendLine("iptables -X CORE_FWD 2>/dev/null || true")

            // Flush and remove CORE_DNS chain
            appendLine("iptables -t nat -D PREROUTING -j CORE_DNS 2>/dev/null || true")
            appendLine("iptables -t nat -F CORE_DNS 2>/dev/null || true")
            appendLine("iptables -t nat -X CORE_DNS 2>/dev/null || true")

            // Remove all MASQUERADE rules by CIDR (loop until no match)
            lanCidrs.forEach { cidr ->
                appendLine("while iptables -t nat -D POSTROUTING -s $cidr ! -d $cidr -j MASQUERADE 2>/dev/null; do :; done")
            }

            // Remove MSS clamp rule
            appendLine("iptables -t mangle -D FORWARD -o \$TUN -p tcp --tcp-flags SYN,RST SYN -j TCPMSS --set-mss 1350 2>/dev/null || true")

            // Flush and remove CORE_MARK chain
            appendLine("iptables -t mangle -D PREROUTING -j CORE_MARK 2>/dev/null || true")
            appendLine("iptables -t mangle -F CORE_MARK 2>/dev/null || true")
            appendLine("iptables -t mangle -X CORE_MARK 2>/dev/null || true")

            // Flush and remove CORE6_FWD chain
            appendLine("ip6tables -D FORWARD -j CORE6_FWD 2>/dev/null || true")
            appendLine("ip6tables -F CORE6_FWD 2>/dev/null || true")
            appendLine("ip6tables -X CORE6_FWD 2>/dev/null || true")

            // Flush and remove CORE6_PRE chain
            appendLine("ip6tables -t mangle -D PREROUTING -j CORE6_PRE 2>/dev/null || true")
            appendLine("ip6tables -t mangle -F CORE6_PRE 2>/dev/null || true")
            appendLine("ip6tables -t mangle -X CORE6_PRE 2>/dev/null || true")

            // Remove IPv6 MASQUERADE rules by CIDR
            lanCidrs.forEach { cidr ->
                appendLine("while ip6tables -t nat -D POSTROUTING -s $cidr -j MASQUERADE 2>/dev/null; do :; done")
            }

            // Remove fwmark policy routing rule
            appendLine("ip rule del fwmark $MARK lookup $TABLE pref 5030 2>/dev/null || true")
        }
        Logs.d("RootLanSharing: executing teardown script for $tunName")
        val result = RootShell.exec(script)
        if (!result.success) {
            Logs.w("RootLanSharing: teardown returned non-zero: ${result.output}")
        }
    }

    private fun getVpnTunName(): String? {
        Logs.d("RootLanSharing.getVpnTunName: searching for tun device")

        // Method 1: Check routing table (VpnService routes through table 1640)
        val output1 = try {
            RootShell.exec("ip route show table $TABLE 2>/dev/null | grep -v unreachable | head -1").output
        } catch (e: Exception) {
            Logs.w("RootLanSharing.getVpnTunName: method 1 (routing table) failed: ${e.message}")
            null
        }
        Logs.d("RootLanSharing.getVpnTunName: routing table output='$output1'")
        if (!output1.isNullOrBlank()) {
            val parts = output1.trim().split("dev")
            if (parts.size > 1) {
                val name = parts[1].trim().split(" ").firstOrNull()
                if (!name.isNullOrBlank() && name.startsWith("tun")) {
                    Logs.d("RootLanSharing.getVpnTunName: found tun='$name' via routing table")
                    return name
                }
            }
        }

        // Method 2: Check all tun devices (compatible with busybox grep, no -P flag)
        // Filter out system tunl devices (tunl0, tunl6, etc.) - only want tun0, tun1, etc.
        return try {
            val output2 = RootShell.exec("ip link show 2>/dev/null | grep -v tunl | grep ': tun' | head -1").output
            Logs.d("RootLanSharing.getVpnTunName: ip link output='$output2'")
            if (!output2.isNullOrBlank()) {
                val parts = output2.split(":")
                if (parts.size > 1) {
                    val name = parts[1].trim()
                    if (!name.isNullOrBlank() && name.startsWith("tun") && !name.startsWith("tunl")) {
                        Logs.d("RootLanSharing.getVpnTunName: found tun='$name' via ip link")
                        return name
                    }
                }
            }
            null
        } catch (e: Exception) {
            Logs.w("RootLanSharing.getVpnTunName: method 2 (ip link) failed: ${e.message}")
            null
        }
    }

    private fun resolveDns(remoteDns: String): String {
        Logs.d("RootLanSharing.resolveDns: input='$remoteDns'")
        // Try to extract IP from various DNS URL formats
        if (remoteDns.startsWith("host:")) {
            val ip = remoteDns.substringAfter("://").substringBefore(":")
            if (ip.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                Logs.d("RootLanSharing.resolveDns: extracted IP from host: protocol: $ip")
                return ip
            }
        }
        if (remoteDns.startsWith("tcp:")) {
            val ip = remoteDns.substringAfter("://").substringBefore(":")
            if (ip.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                Logs.d("RootLanSharing.resolveDns: extracted IP from tcp: protocol: $ip")
                return ip
            }
        }
        // For HTTPS/DoH URLs, extract hostname and resolve it using the app's own DNS
        // (VpnService is running, so this works - unlike root shell commands)
        val host = remoteDns.substringAfter("://").substringBefore("/").substringBefore(":")
        Logs.d("RootLanSharing.resolveDns: trying to resolve host='$host'")

        if (host.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
            Logs.d("RootLanSharing.resolveDns: host is already an IP: $host")
            return host
        }

        return try {
            val addr = java.net.InetAddress.getByName(host)
            val ip = addr.hostAddress
            if (!ip.isNullOrBlank() && ip.matches(Regex("""^\d+\.\d+\.\d+\.\d+$"""))) {
                Logs.d("RootLanSharing.resolveDns: resolved host='$host' to $ip via InetAddress")
                ip
            } else {
                Logs.w("RootLanSharing.resolveDns: InetAddress returned invalid IP: $ip, falling back to 1.1.1.1")
                "1.1.1.1"
            }
        } catch (e: Exception) {
            Logs.w("RootLanSharing.resolveDns: InetAddress resolution failed for $host: ${e.message}, falling back to 1.1.1.1")
            "1.1.1.1"
        }
    }
}
