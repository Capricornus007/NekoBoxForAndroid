package io.nekohasekai.sagernet.ui

import android.app.Dialog
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.redactConfigJson
import io.nekohasekai.sagernet.ktx.redactDiagnostic
import kotlinx.coroutines.*
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun MainActivity.showProfileDiagnostics(profile: ProxyEntity) {
    val context = this
    fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    val dialog = Dialog(context, R.style.Theme_SagerNet)
    val root = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val toolbar = MaterialToolbar(context).apply {
        setTitle(R.string.menu_diagnostics)
        subtitle = profile.displayName()
        setNavigationIcon(R.drawable.ic_navigation_close)
        setNavigationContentDescription(android.R.string.cancel)
        setNavigationOnClickListener { dialog.dismiss() }
    }
    root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))
    val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
    val scroll = ScrollView(context).apply { addView(content) }
    root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
    ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
        view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
        insets
    }
    fun card(title: Int, emphasis: Boolean = false): LinearLayout {
        val card = MaterialCardView(context).apply {
            radius = dp(if (emphasis) 28 else 20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(MaterialColors.getColor(this, if (emphasis) com.google.android.material.R.attr.colorPrimaryContainer else com.google.android.material.R.attr.colorSurfaceContainer, 0))
        }
        val body = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        body.addView(TextView(context).apply { setText(title); setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium) })
        card.addView(body)
        content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })
        return body
    }
    fun LinearLayout.label() = TextView(context).also {
        it.setTextIsSelectable(true)
        it.setPadding(0, dp(8), 0, dp(8))
        addView(it, ViewGroup.LayoutParams(-1, -2))
    }
    val overview = card(R.string.diagnostics_overview, true)
    val validationView = overview.label().apply { setText(R.string.diagnostics_loading) }
    val configButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setText(R.string.menu_configuration); isEnabled = false }
    overview.addView(configButton)
    val groupsView = card(R.string.diagnostics_live_state).label()
    val probesCard = card(R.string.diagnostics_active_tests)
    val probesView = probesCard.label().apply { setText(R.string.diagnostics_probe_hint) }
    fun probeInput(title: Int, value: String): TextInputEditText {
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = getString(title) }
        val edit = TextInputEditText(layout.context).apply { setSingleLine(true); setText(value) }
        layout.addView(edit, LinearLayout.LayoutParams(-1, -2))
        probesCard.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return edit
    }
    val probeDomain = probeInput(R.string.diagnostics_probe_domain, "example.com")
    val probeDns = probeInput(R.string.diagnostics_probe_dns, "1.1.1.1:53")
    val runProbes = MaterialButton(context).apply { setText(R.string.diagnostics_run_checks) }
    probesCard.addView(runProbes, ViewGroup.LayoutParams(-1, -2))
    probesCard.addView(MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
        setText(R.string.network_profiles_edit_profile)
        setOnClickListener {
            val subscription = io.nekohasekai.sagernet.database.SagerDatabase.groupDao.getById(profile.groupId)?.subscription != null
            dialog.dismiss()
            startActivity(profile.settingIntent(context, subscription))
        }
    }, ViewGroup.LayoutParams(-1, -2))
    var probeJob: Job? = null
    runProbes.setOnClickListener {
        if (profile.id != DataStore.currentProfile || !DataStore.serviceState.connected) {
            probesView.setText(R.string.diagnostics_not_active)
            return@setOnClickListener
        }
        runProbes.isEnabled = false
        probesView.setText(R.string.diagnostics_loading)
        val domain = probeDomain.text.toString().trim()
        val dns = probeDns.text.toString().trim()
        probeJob = lifecycleScope.launch {
            val results = mutableListOf<String>()
            try {
                for (direct in listOf(true, false)) {
                    val result = withContext(Dispatchers.IO) {
                        connection.service?.networkProbe(profile.id, direct, domain, dns)?.let(::JSONObject)
                            ?: error(getString(R.string.diagnostics_not_active))
                    }
                    val title = getString(if (direct) R.string.route_bypass else R.string.route_proxy)
                    results += buildString {
                        append(title)
                        append("\nUDP: ").append(getString(if (result.optString("udp") == "response") R.string.diagnostics_reachable else R.string.diagnostics_unreachable))
                        append("\nDNS (UDP): ").append(if (result.optString("outbound_dns_udp") == "response")
                            "rcode ${result.optInt("rcode")} · ${result.optInt("answers")} · ${result.optInt("dns_ms")} ms" else getString(R.string.diagnostics_query_failed))
                        if (result.optBoolean("truncated")) append(" · truncated")
                        append("\nTCP dial: ").append(if (result.optString("tcp") == "dialed") "${result.optInt("tcp_ms")} ms" else getString(R.string.diagnostics_query_failed))
                        append("\nTLS: ").append(if (result.optString("tls") == "verified") "${result.optInt("tls_ms")} ms" else getString(R.string.diagnostics_query_failed))
                        result.optJSONObject("mtu")?.let { mtu ->
                            append("\nMTU: ").append(when (mtu.optString("state")) {
                                "verified-inner-packet" -> getString(R.string.diagnostics_mtu_verified, mtu.optInt("largest_verified"), mtu.optInt("configured_mtu"))
                                "unsupported" -> getString(R.string.diagnostics_mtu_unsupported)
                                "busy" -> getString(R.string.diagnostics_mtu_busy)
                                else -> getString(R.string.diagnostics_mtu_unknown)
                            })
                            mtu.optJSONArray("samples")?.let { samples -> for (n in 0 until samples.length()) {
                                val sample = samples.getJSONObject(n)
                                append("\n  ").append(sample.optInt("size")).append(" B · ").append(if (sample.optBoolean("received")) "${sample.optInt("rtt_ms")} ms" else getString(R.string.diagnostics_mtu_no_reply))
                            } }
                        }
                    }
                    probesView.text = results.joinToString("\n\n") + "\n\n" + getString(R.string.diagnostics_probe_result_hint)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) { probesView.text = results.joinToString("\n\n") + "\n" + redactDiagnostic(e.readableMessage) }
            finally { runProbes.isEnabled = true }
        }
    }
    val historyCard = card(R.string.diagnostics_connection_history)
    fun filter(title: Int): TextInputEditText {
        val layout = TextInputLayout(context, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = getString(title) }
        val edit = TextInputEditText(layout.context).apply { setSingleLine(true); maxLines = 1; filters = arrayOf(android.text.InputFilter.LengthFilter(256)) }
        layout.addView(edit, LinearLayout.LayoutParams(-1, -2))
        historyCard.addView(layout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        return edit
    }
    val appFilter = filter(R.string.diagnostics_filter_app)
    val domainFilter = filter(R.string.diagnostics_filter_domain)
    val nodeFilter = filter(R.string.diagnostics_filter_node)
    val historySummary = historyCard.label()
    val historyView = historyCard.label()
    val olderHistory = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setText(R.string.diagnostics_history_older); isEnabled = false }
    val latestHistory = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { setText(R.string.diagnostics_history_latest); isEnabled = false }
    historyCard.addView(olderHistory)
    historyCard.addView(latestHistory)
    var historyBefore = 0L
    var nextHistoryBefore = 0L
    var historyVersion = 0
    olderHistory.setOnClickListener { historyBefore = nextHistoryBefore; historyVersion++; olderHistory.isEnabled = false }
    latestHistory.setOnClickListener { historyBefore = 0; historyVersion++; latestHistory.isEnabled = false }
    var runtimeState: JSONObject? = null
    fun time(value: String): String = runCatching { DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.parse(value)) }.getOrDefault("")
    fun renderHistory() {
        val state = runtimeState
        if (state == null || !state.optBoolean("debug_history")) {
            historySummary.text = ""
            olderHistory.isEnabled = false
            latestHistory.isEnabled = false
            historyView.setText(R.string.diagnostics_history_debug_only)
            return
        }
        val array = state.optJSONArray("events")
        val events = if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        nextHistoryBefore = state.optLong("history_before")
        olderHistory.isEnabled = state.optBoolean("history_has_more") && nextHistoryBefore > 0 && nextHistoryBefore != historyBefore
        latestHistory.isEnabled = historyBefore > 0
        historySummary.text = getString(R.string.diagnostics_history_window, state.optInt("history_total"), events.size) +
            if (state.optBoolean("history_truncated")) "\n" + getString(R.string.diagnostics_history_truncated) else ""
        val report = events.indices.reversed().joinToString("\n\n") { index ->
            val event = events[index]
            buildString {
                append(time(event.optString("time")))
                event.optString("id").substringBefore('/').takeIf(String::isNotBlank)?.let { append(" #").append(it) }
                if (event.has("uid")) append(" UID ").append(event.optInt("uid"))
                event.optJSONArray("packages")?.let { append(" ").append((0 until it.length()).joinToString { n -> it.optString(n) }) }
                when (event.optString("kind")) {
                    "route" -> append("\n").append(event.optString("network")).append(" ").append(event.optString("domain")).append(" ").append(event.optString("destination"))
                        .append("\n").append(event.optString("rule")).append(" → ").append(event.optString("outbound"))
                    "selected" -> append("\n").append(getString(R.string.diagnostics_selected_node)).append(": ").append(event.optString("outbound"))
                    "dns" -> append("\nDNS ").append(event.optString("domain")).append(" → ").append(event.optString("server"))
                        .append(" · type ").append(event.optInt("query_type")).append(" · ")
                        .append(if (event.optBoolean("failed")) getString(R.string.diagnostics_query_failed) else "rcode ${event.optInt("rcode")}")
                        .append(if (event.optBoolean("cached")) " · cache" else "")
                }
            }
        }
        historyView.text = redactDiagnostic(report.ifBlank { getString(R.string.diagnostics_no_matching_events) })
    }
    listOf(appFilter, domainFilter, nodeFilter).forEach { edit -> edit.addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { historyBefore = 0; historyVersion++ }
        override fun afterTextChanged(s: Editable?) = Unit
    }) }
    fun renderRuntime(state: JSONObject?) {
        runtimeState = state
        val report = StringBuilder()
        val groups = state?.optJSONArray("groups")
        if (state == null) report.append(getString(R.string.diagnostics_not_active))
        else if (groups == null || groups.length() == 0) report.append(getString(R.string.diagnostics_no_groups))
        else for (index in 0 until groups.length()) {
            val group = groups.getJSONObject(index)
            if (report.isNotEmpty()) report.append("\n\n")
            report.append(group.optString("tag"))
            for (network in listOf("tcp", "udp")) {
                val selected = group.optJSONObject(network) ?: continue
                report.append("\n").append(network.uppercase()).append(": ").append(selected.optString("current").ifBlank { "—" })
                val reason = when (selected.optString("reason")) {
                    "initial-selection" -> R.string.diagnostics_initial_selection
                    "no-successful-probe" -> R.string.diagnostics_no_probe
                    "control-site-unreachable" -> R.string.diagnostics_control_unreachable
                    "direct-recovered" -> R.string.diagnostics_direct_recovered
                    "previous-unavailable" -> R.string.diagnostics_previous_unavailable
                    "higher-priority-available" -> R.string.diagnostics_priority_available
                    "lower-latency" -> R.string.diagnostics_lower_latency
                    else -> R.string.diagnostics_no_candidate
                }
                report.append(" · ").append(getString(reason)).append(" · ").append(time(selected.optString("changed_at")))
            }
            group.optJSONObject("fallback")?.let { fallback ->
                report.append("\n").append(getString(R.string.diagnostics_fallback_rounds, fallback.optInt("failed_rounds"), fallback.optInt("failure_threshold"), fallback.optInt("healthy_rounds"), fallback.optInt("recovery_threshold")))
                fallback.optJSONArray("sites")?.let { sites -> for (n in 0 until sites.length()) {
                    val site = sites.getJSONObject(n)
                    report.append("\n").append(site.optString("host")).append(": ").append(getString(when (site.optString("state")) {
                        "reachable" -> R.string.diagnostics_reachable
                        "unreachable" -> R.string.diagnostics_unreachable
                        else -> R.string.diagnostics_not_checked
                    }))
                    if (site.has("rtt_ms")) report.append(" · ").append(site.optInt("rtt_ms")).append(" ms")
                } }
            }
            group.optJSONArray("candidates")?.let { candidates -> for (n in 0 until candidates.length()) {
                val candidate = candidates.getJSONObject(n)
                report.append("\n  ").append(candidate.optString("tag")).append(" · ")
                    .append(if (candidate.optBoolean("reachable")) "${candidate.optInt("rtt_ms")} ms" else getString(R.string.diagnostics_no_probe))
            } }
        }
        state?.optJSONArray("wireguard")?.let { wireguard -> for (index in 0 until wireguard.length()) {
            val wg = wireguard.getJSONObject(index)
            report.append("\n\nWG/AWG · ").append(wg.optString("tag")).append(" · MTU ").append(wg.optInt("mtu"))
            wg.optJSONArray("peers")?.let { peers -> for (n in 0 until peers.length()) {
                val peer = peers.getJSONObject(n)
                val handshake = peer.optLong("last_handshake_time_sec")
                report.append("\n#").append(peer.optInt("index")).append(" · ").append(getString(R.string.diagnostics_handshake)).append(": ")
                    .append(if (handshake > 0) DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(handshake)) else getString(R.string.diagnostics_handshake_never))
                report.append("\nRX ").append(peer.optLong("rx_bytes")).append(" B · TX ").append(peer.optLong("tx_bytes")).append(" B")
            } }
            report.append("\n").append(getString(R.string.diagnostics_wg_counters_hint))
        } }
        groupsView.text = (if (state?.optBoolean("probes_paused") == true) getString(R.string.diagnostics_probes_paused) + "\n\n" else "") + redactDiagnostic(report.toString())
        renderHistory()
    }
    dialog.setContentView(root)
    dialog.show()
    dialog.window?.let { window ->
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        ViewCompat.requestApplyInsets(root)
    }
    val job = lifecycleScope.launch {
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
                redactDiagnostic(report.toString()) to redactConfigJson(config)
            }
            validationView.text = summary
            configButton.isEnabled = true
            configButton.setOnClickListener {
                val text = TextView(context).apply { setPadding(dp(16), dp(8), dp(16), dp(8)); setTextIsSelectable(true); typeface = Typeface.MONOSPACE; this.text = preview }
                MaterialAlertDialogBuilder(context).setTitle(R.string.menu_configuration)
                    .setView(ScrollView(context).apply { addView(text) }).setPositiveButton(android.R.string.ok, null).show()
            }
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (isActive) {
                    val version = historyVersion
                    val query = JSONObject().put("app", appFilter.text.toString()).put("domain", domainFilter.text.toString())
                        .put("outbound", nodeFilter.text.toString()).put("before", historyBefore).put("limit", 100).toString()
                    val state = withContext(Dispatchers.IO) {
                        if (profile.id == DataStore.currentProfile && DataStore.serviceState.connected)
                            runCatching { connection.service?.getRuntimeDiagnostics(query)?.let(::JSONObject) }.getOrNull()
                        else null
                    }
                    if (version == historyVersion) renderRuntime(state)
                    delay(2000)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (e: Exception) { validationView.text = redactDiagnostic(e.readableMessage) }
    }
    dialog.setOnDismissListener { job.cancel(); probeJob?.cancel() }
}
