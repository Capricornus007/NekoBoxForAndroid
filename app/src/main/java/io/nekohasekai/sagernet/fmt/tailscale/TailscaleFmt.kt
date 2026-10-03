package io.nekohasekai.sagernet.fmt.tailscale

import moe.matsuri.nb4a.SingBoxOptions

// Per-profile node identity. Relative to the core working directory (no_backup),
// keyed by profile id so renames keep the node and "reset identity" can delete it.
fun tailscaleStateDirectory(profileId: Long) = "tailscale/$profileId"

fun buildSingBoxEndpointTailscaleBean(bean: TailscaleBean, profileId: Long): SingBoxOptions.Endpoint_TailscaleOptions =
    SingBoxOptions.Endpoint_TailscaleOptions().apply {
        type = "tailscale"
        state_directory = tailscaleStateDirectory(profileId)
        if (bean.authKey!!.isNotBlank()) auth_key = bean.authKey
        if (bean.controlUrl!!.isNotBlank()) control_url = bean.controlUrl
        if (bean.hostname!!.isNotBlank()) hostname = bean.hostname
        if (bean.exitNode!!.isNotBlank()) {
            exit_node = bean.exitNode
            if (bean.exitNodeAllowLanAccess == true) exit_node_allow_lan_access = true
        }
        if (bean.acceptRoutes == true) accept_routes = true
        if (bean.onlyTcp443 == true) only_tcp_443 = true
    }
