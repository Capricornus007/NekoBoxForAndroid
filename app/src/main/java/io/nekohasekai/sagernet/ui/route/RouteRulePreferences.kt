package io.nekohasekai.sagernet.ui.route

import android.text.InputType
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.route.RouteRuleEditor

/** JSON-backed preferences leave Room and the legacy binary backup contract untouched. */
class RouteRulePreferences(private val fragment: PreferenceFragmentCompat) {
    private var expanded = false
    private val context get() = fragment.requireContext()
    private fun pref(key: String) = fragment.findPreference<Preference>(key)!!
    fun bind() {
        for (key in listOf(Key.ROUTE_DOMAIN, Key.ROUTE_IP, Key.ROUTE_SOURCE, Key.ROUTE_PORT,
                Key.ROUTE_SOURCE_PORT, Key.ROUTE_RULESET, Key.ROUTE_NETWORK, Key.ROUTE_PROTOCOL)) {
            (pref(key) as? EditTextPreference)?.setOnBindEditTextListener { edit ->
                edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                edit.isSingleLine = false
                edit.minLines = 3
                edit.maxLines = 10
            }
        }
        for (field in RouteRuleEditor.fields) {
            val preference = pref("rr_$field")
            preference.isPersistent = false
            if (preference is EditTextPreference) preference.setOnBindEditTextListener { edit ->
                edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                edit.isSingleLine = false
                edit.minLines = if (field in RouteRuleEditor.lists) 3 else 1
            }
            preference.setOnPreferenceChangeListener { _, value ->
                try {
                    DataStore.serverConfig = RouteRuleEditor.change(DataStore.serverConfig, field, value)
                    DataStore.dirty = true
                    preferenceScreenState()
                    true
                } catch (_: Exception) {
                    val reason = if (runCatching { RouteRuleEditor.json(DataStore.serverConfig) }.isFailure)
                        R.string.rr_json_error else R.string.rr_value_error
                    MaterialAlertDialogBuilder(context).setTitle(R.string.rr_invalid)
                        .setMessage(context.getString(R.string.rr_problem, preference.title, context.getString(reason)))
                        .setPositiveButton(android.R.string.ok, null).show()
                    false
                }
            }
        }
        pref("rr_toggle").setOnPreferenceClickListener {
            expanded = !expanded
            preferenceScreenState()
            true
        }
        pref("rr_picker").setOnPreferenceClickListener { showRuleSets(); true }
        refresh()
    }
    fun refresh() {
        val obj = runCatching { RouteRuleEditor.json(DataStore.serverConfig) }.getOrNull() ?: return
        for (field in RouteRuleEditor.fields) {
            val preference = pref("rr_$field")
            when (preference) {
                is SwitchPreferenceCompat -> preference.isChecked = obj.optBoolean(field)
                is ListPreference -> preference.value = RouteRuleEditor.text(DataStore.serverConfig, field)
                is EditTextPreference -> preference.text = RouteRuleEditor.text(DataStore.serverConfig, field)
            }
        }
        preferenceScreenState()
    }
    private fun preferenceScreenState() {
        pref("rr_advanced").isVisible = expanded
        pref("rr_toggle").setTitle(if (expanded) R.string.rr_hide_advanced else R.string.rr_show_advanced)
        val act = runCatching { RouteRuleEditor.json(DataStore.serverConfig).optString("action").ifBlank { "route" } }.getOrDefault("route")
        pref(Key.ROUTE_OUTBOUND).isEnabled = act == "route"
        // Preserve previously entered action options so changing actions never silently loses data.
        for ((field, applicable) in mapOf("method" to (act == "reject"), "no_drop" to (act == "reject"),
                "strategy" to (act == "resolve"), "sniffer" to (act == "sniff"),
                "override_address" to (act in setOf("route", "route-options")),
                "override_port" to (act in setOf("route", "route-options")))) {
            pref("rr_$field").isVisible = applicable || runCatching { RouteRuleEditor.json(DataStore.serverConfig).has(field) }.getOrDefault(false)
        }
    }
    private fun showRuleSets() {
        // Existing geodata tags already used; no new catalogue downloader or mirror policy.
        val presets = listOf("geosite:cn", "geoip:cn", "geosite:category-ads-all")
        val domains = DataStore.routeDomain.split('\n').toMutableList()
        val ips = DataStore.routeIP.split('\n').toMutableList()
        val checked = presets.map { it in domains || it in ips }.toBooleanArray()
        MaterialAlertDialogBuilder(context).setTitle(R.string.rr_picker)
            .setMultiChoiceItems(presets.toTypedArray(), checked) { _, index, selected -> checked[index] = selected }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                for ((index, tag) in presets.withIndex()) {
                    val target = if (tag.startsWith("geoip:")) ips else domains
                    target.removeAll { it == tag }
                    if (checked[index]) target.add(tag)
                }
                DataStore.routeDomain = domains.filter { it.isNotBlank() }.joinToString("\n")
                DataStore.routeIP = ips.filter { it.isNotBlank() }.joinToString("\n")
                (pref(Key.ROUTE_DOMAIN) as EditTextPreference).text = DataStore.routeDomain
                (pref(Key.ROUTE_IP) as EditTextPreference).text = DataStore.routeIP
            }.setNegativeButton(android.R.string.cancel, null).show()
    }
}
