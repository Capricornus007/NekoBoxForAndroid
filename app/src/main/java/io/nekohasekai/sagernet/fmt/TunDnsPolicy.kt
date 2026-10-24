package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.bg.VpnService
import moe.matsuri.nb4a.SingBoxOptions.Rule_DefaultOptions

// The virtual DNS address supports hijacked plaintext DNS, not TLS. Reject
// Android's opportunistic DoT probe instead of dialing this address via LAN.
fun virtualDnsTlsRejectRule() = Rule_DefaultOptions().apply {
    inbound = listOf("tun-in")
    ip_cidr = listOf("${VpnService.PRIVATE_VLAN4_ROUTER}/32", "${VpnService.PRIVATE_VLAN6_ROUTER}/128")
    port = listOf(853)
    action = "reject"
}
