package io.nekohasekai.sagernet.ui

import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.redactConfigJson
import io.nekohasekai.sagernet.ktx.redactDiagnostic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun MainActivity.showProfileDiagnostics(profile: ProxyEntity) {
    lifecycleScope.launch {
        try {
            val (summary, preview) = withContext(Dispatchers.IO) {
                val config = buildConfig(profile).config
                val validation = try {
                    Libcore.checkConfig(config, LocalResolverImpl)
                    getString(R.string.diagnostics_config_valid)
                } catch (e: Exception) {
                    getString(R.string.diagnostics_config_invalid) + "\n" + redactDiagnostic(e.readableMessage)
                }
                val json = JSONObject(config)
                val report = StringBuilder(validation)
                val inbounds = json.optJSONArray("inbounds")
                if (inbounds != null) for (index in 0 until inbounds.length()) {
                    val inbound = inbounds.optJSONObject(index) ?: continue
                    if (inbound.optString("type") in setOf("socks", "http", "mixed") &&
                        inbound.optString("listen") in setOf("0.0.0.0", "::", "") &&
                        (inbound.optJSONArray("users")?.length() ?: 0) == 0) {
                        report.append("\n\n").append(getString(R.string.diagnostics_listener_without_auth)).append(" ").append(inbound.optString("tag"))
                    }
                }

                fun hasAppGuard(rule: JSONObject): Boolean {
                    fun appCondition(rule: JSONObject): Boolean {
                        if (rule.has("user_id") || rule.has("package_name")) return true
                        val children = rule.optJSONArray("rules") ?: return false
                        return (0 until children.length()).any { index -> children.optJSONObject(index)?.let(::appCondition) == true }
                    }
                    return rule.optString("action") == "reject" && appCondition(rule)
                }
                val routeRules = json.optJSONObject("route")?.optJSONArray("rules")
                val hasTun = inbounds != null && (0 until inbounds.length()).any { inbounds.optJSONObject(it)?.optString("type") == "tun" }
                val guarded = routeRules != null && (0 until routeRules.length()).any { routeRules.optJSONObject(it)?.let(::hasAppGuard) == true }
                if (hasTun && DataStore.proxyApps && !guarded) report.append("\n\n").append(getString(R.string.diagnostics_no_app_guard))
                val runtime = if (profile.id == DataStore.currentProfile && DataStore.serviceState.started) {
                    runCatching { connection.service?.runtimeDiagnostics }.getOrNull()
                } else null
                val runtimeState = runtime?.let(::JSONObject)
                val groups = runtimeState?.optJSONArray("groups")
                if (groups == null || groups.length() == 0) report.append("\n\n").append(getString(R.string.diagnostics_no_groups))
                else for (index in 0 until groups.length()) {
                    val group = groups.getJSONObject(index)
                    report.append("\n\n").append(group.optString("tag"))
                    group.optString("url").takeIf { it.isNotBlank() }?.let { url ->
                        val uri = runCatching { URI(url) }.getOrNull()
                        if (uri?.host != null) report.append("\n").append(uri.scheme).append("://").append(uri.host).append(if (uri.port > 0) ":${uri.port}" else "")
                    }
                    for (network in listOf("tcp", "udp")) {
                        val state = group.optJSONObject(network) ?: continue
                        val selected = state.optString("current").ifBlank { "—" }
                        report.append("\n").append(network.uppercase()).append(": ").append(selected)
                        val candidates = group.optJSONArray("candidates")
                        if (candidates != null) for (candidateIndex in 0 until candidates.length()) {
                            val candidate = candidates.getJSONObject(candidateIndex)
                            if (candidate.optString("tag") == selected && candidate.has("rtt_ms")) report.append(" (RTT ").append(candidate.getInt("rtt_ms")).append(" ms)")
                        }
                        val reason = when (state.optString("reason")) {
                            "initial-selection" -> R.string.diagnostics_initial_selection
                            "no-successful-probe" -> R.string.diagnostics_no_probe
                            "control-site-unreachable" -> R.string.diagnostics_control_unreachable
                            "direct-recovered" -> R.string.diagnostics_direct_recovered
                            "previous-unavailable" -> R.string.diagnostics_previous_unavailable
                            "higher-priority-available" -> R.string.diagnostics_priority_available
                            "lower-latency" -> R.string.diagnostics_lower_latency
                            else -> R.string.diagnostics_no_candidate
                        }
                        report.append("\n").append(getString(reason))
                        runCatching {
                            val changed = Instant.parse(state.getString("changed_at"))
                            if (changed.epochSecond > 0) report.append(" · ").append(DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(changed))
                        }
                    }
                }
                val wireguard = runtimeState?.optJSONArray("wireguard")
                if (wireguard != null) for (index in 0 until wireguard.length()) {
                    val state = wireguard.getJSONObject(index)
                    report.append("\n\nWG/AWG · ").append(state.optString("tag")).append(" · MTU ").append(state.optInt("mtu"))
                    val peers = state.optJSONArray("peers")
                    if (peers != null) for (peerIndex in 0 until peers.length()) {
                        val peer = peers.getJSONObject(peerIndex)
                        val handshake = peer.optLong("last_handshake_time_sec")
                        val handshakeText = if (handshake > 0) DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(handshake)) else getString(R.string.diagnostics_handshake_never)
                        report.append("\n#").append(peer.optInt("index")).append(" · ").append(getString(R.string.diagnostics_handshake)).append(": ").append(handshakeText)
                        report.append("\nRX ").append(peer.optLong("rx_bytes")).append(" B · TX ").append(peer.optLong("tx_bytes")).append(" B")
                    }
                    report.append("\n").append(getString(R.string.diagnostics_wg_counters_hint))
                }
                if (runtimeState != null) {
                    report.append("\n\n").append(getString(R.string.diagnostics_connection_history))
                    if (!runtimeState.optBoolean("debug_history")) report.append("\n").append(getString(R.string.diagnostics_history_debug_only))
                    val events = runtimeState.optJSONArray("events")
                    if (events != null) for (index in 0 until events.length()) {
                        val event = events.getJSONObject(index)
                        val time = runCatching { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(event.getString("time"))) }.getOrDefault("")
                        report.append("\n\n").append(time)
                        event.optString("id").substringBefore('/').takeIf { it.isNotBlank() }?.let { report.append(" #").append(it) }
                        if (event.has("uid")) report.append(" UID ").append(event.optInt("uid"))
                        event.optJSONArray("packages")?.let { packages -> report.append(" ").append((0 until packages.length()).joinToString { packages.optString(it) }) }
                        when (event.optString("kind")) {
                            "route" -> report.append("\n").append(event.optString("network")).append(" ").append(event.optString("domain")).append(" ").append(event.optString("destination"))
                                .append("\n").append(event.optString("rule")).append(" → ").append(event.optString("outbound")).append(" [").append(event.optString("outbound_type")).append("]")
                            "selected" -> report.append("\n").append(getString(R.string.diagnostics_selected_node)).append(": ").append(event.optString("outbound"))
                            "dns" -> report.append("\nDNS ").append(event.optString("domain")).append(" → ").append(event.optString("server"))
                                .append(" · type ").append(event.optInt("query_type")).append(" · ")
                                .append(if (event.optBoolean("failed")) getString(R.string.diagnostics_query_failed) else "rcode ${event.optInt("rcode")}")
                                .append(if (event.optBoolean("cached")) " · cache" else "")
                        }
                    }
                }
                redactDiagnostic(report.toString()) to redactConfigJson(config)
            }
            fun showText(title: String, text: String, config: Boolean) {
                val padding = (16 * resources.displayMetrics.density).toInt()
                val content = TextView(this@showProfileDiagnostics).apply {
                    setPadding(padding, padding, padding, padding)
                    setTextIsSelectable(true)
                    this.text = text
                    if (config) typeface = Typeface.MONOSPACE
                }
                val dialog = MaterialAlertDialogBuilder(this@showProfileDiagnostics).setTitle(title)
                    .setView(ScrollView(this@showProfileDiagnostics).apply { addView(content) })
                    .setPositiveButton(android.R.string.ok, null)
                if (!config) {
                    dialog.setNeutralButton(R.string.menu_configuration) { _, _ -> showText(getString(R.string.menu_configuration), preview, true) }
                    dialog.setNegativeButton(R.string.diagnostics_refresh) { _, _ -> showProfileDiagnostics(profile) }
                }
                dialog.show()
            }
            showText(getString(R.string.profile_diagnostics), summary, false)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            snackbar(redactDiagnostic(e.readableMessage)).show()
        }
    }
}
