package io.nekohasekai.sagernet.ui

import android.content.Context
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService.State
import java.text.DateFormat
import java.util.Date

internal class TailscaleStatusFormatting(private val context: Context) {
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    fun expiry(peer: TailscaleStatusPeer): String = when {
        peer.expired -> text(R.string.tailscale_status_expired)

        peer.keyExpiry <= 0 || peer.keyExpiry > Long.MAX_VALUE / 1000 -> text(R.string.tailscale_status_expiry_unknown)

        else -> text(
            R.string.tailscale_status_expiry,
            DateFormat.getDateTimeInstance().format(Date(peer.keyExpiry * 1000)),
        )
    }

    fun peer(peer: TailscaleStatusPeer): String = listOfNotNull(
        peer.ips.joinToString(", ").takeIf { it.isNotEmpty() },
        text(if (peer.online) R.string.tailscale_status_online else R.string.tailscale_status_offline),
        expiry(peer),
        if (peer.exitNodeSelected) {
            text(R.string.tailscale_status_exit_selected)
        } else if (peer.exitNodeOption) {
            text(R.string.tailscale_status_exit_offer)
        } else {
            null
        },
    ).joinToString("\n")

    fun state(state: TailscaleStatusUiState): String {
        val status = state.status
        if (state.serviceState == State.Stopping) return text(R.string.stopping)
        if (state.serviceState == State.Connecting || state.refreshing) return text(R.string.connecting)
        if (state.temporaryRequested || (status?.stage == "starting" && status.source == "temporary")) {
            return text(R.string.tailscale_status_starting)
        }
        if (status?.stage == "starting") return text(R.string.connecting)
        if (!state.connected) {
            return text(
                if (state.failed) R.string.tailscale_status_error else R.string.tailscale_status_connecting,
            )
        }
        if (status?.stage == "closed") return text(R.string.tailscale_status_closed)
        if (status?.stage == "error") return text(R.string.tailscale_status_error)
        val node = status?.node ?: return text(R.string.tailscale_status_stopped)
        return listOfNotNull(
            text(R.string.tailscale_status_backend, node.backendState),
            if (node.needsLogin) text(R.string.tailscale_login_required) else null,
            if (node.needsApproval) text(R.string.tailscale_status_approval) else null,
            text(if (node.keyAuth) R.string.tailscale_status_key_auth else R.string.tailscale_status_interactive_auth),
        ).joinToString("\n")
    }

    fun currentExit(node: TailscaleNodeStatus?): String {
        if (node == null) return text(R.string.tailscale_status_unknown)
        val exit = node.currentExit ?: return text(R.string.tailscale_status_none)
        val name = node.peers.firstOrNull { it.id == exit.id }?.name?.takeIf { it.isNotEmpty() }
        val label = listOfNotNull(name, exit.ip.takeIf { it.isNotEmpty() } ?: exit.id).joinToString(" · ")
        return text(
            if (exit.live) R.string.tailscale_status_exit_live else R.string.tailscale_status_exit_not_live,
            label,
        )
    }

    fun sample(sample: TailscalePingSample): String {
        val path = when (sample.path) {
            "direct" -> text(R.string.tailscale_status_path_direct)

            "derp" -> text(
                R.string.tailscale_status_path_derp,
                sample.derpRegionCode.ifEmpty { sample.derpRegionId.toString() },
            )

            "peer-relay" -> text(R.string.tailscale_status_path_peer_relay)

            else -> text(R.string.tailscale_status_path_unknown)
        }
        val latency = when {
            sample.error -> text(R.string.tailscale_status_ping_error)
            sample.latencyMs == null -> text(R.string.tailscale_status_unknown)
            else -> text(R.string.tailscale_status_ping_latency, sample.latencyMs)
        }
        return text(R.string.tailscale_status_ping_result, sample.peerIp, latency, path)
    }

    fun exitOutcome(outcome: String): String = text(
        when (outcome) {
            "applied-and-saved" -> R.string.tailscale_status_exit_saved
            "saved-for-next-start" -> R.string.tailscale_status_exit_next_start
            "failed-unchanged" -> R.string.tailscale_status_exit_unchanged
            "failed-rolled-back" -> R.string.tailscale_status_exit_rolled_back
            "conflict" -> R.string.tailscale_status_exit_conflict
            "diverged" -> R.string.tailscale_status_exit_diverged
            "cancelled-before-apply" -> R.string.tailscale_status_exit_cancelled
            else -> R.string.tailscale_status_error
        },
    )
}
