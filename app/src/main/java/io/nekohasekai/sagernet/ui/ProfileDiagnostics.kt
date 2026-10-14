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
                val runtime = if (profile.id == DataStore.currentProfile && DataStore.serviceState.started) {
                    runCatching { connection.service?.runtimeDiagnostics }.getOrNull()
                } else null
                val groups = runtime?.let { JSONObject(it).optJSONArray("groups") }
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
