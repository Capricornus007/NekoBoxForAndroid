package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.core.view.updatePadding
import androidx.core.app.ActivityCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.TrafficMode
import io.nekohasekai.sagernet.database.TrafficRoutingDiagnosticsResolver
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private var isProxyApps: SwitchPreference? = null
    private var appTrafficRulesPreference: Preference? = null
    private var defaultTrafficModePreference: SimpleMenuPreference? = null

    private var globalCustomConfig: EditConfigPreference? = null


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
        listView.setBackgroundColor(Color.TRANSPARENT)
        listView.clipToPadding = false
        listView.updatePadding(left = dp2px(12), top = dp2px(4), right = dp2px(12), bottom = dp2px(24))
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        val appTheme = findPreference<ColorPickerPreference>(Key.APP_THEME)
        appTheme?.setOnPreferenceChangeListener { _, newTheme ->
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
            val theme = Theme.getTheme(newTheme as Int)
            app.setTheme(theme)
            requireActivity().apply {
                setTheme(theme)
                ActivityCompat.recreate(this)
            }
            true
        }

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)
        nightTheme?.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            Theme.applyNightTheme()
            true
        }
        val mixedPort = findPreference<EditTextPreference>(Key.MIXED_PORT)
        val byeDpiPort = findPreference<EditTextPreference>(Key.BYEDPI_PORT)
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)
        val allowAccess = findPreference<Preference>(Key.ALLOW_ACCESS)
        val appendHttpProxy = findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)

        val showDirectSpeed = findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)

        val bypassLan = findPreference<SwitchPreference>(Key.BYPASS_LAN)
        val bypassLanInCore = findPreference<SwitchPreference>(Key.BYPASS_LAN_IN_CORE)

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)
        val enableDnsRouting = findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)
        val enableFakeDns = findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)
        val mtu = findPreference<MTUPreference>(Key.MTU)
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)
        globalCustomConfig?.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel?.apply {
            dialogLayoutResource = R.layout.layout_loglevel_help
            setOnPreferenceChangeListener { _, _ ->
                needRestart()
                true
            }
            setOnLongClickListener {
                val view = EditText(requireContext()).apply {
                    inputType = EditorInfo.TYPE_CLASS_NUMBER
                    var size = DataStore.logBufSize
                    if (size == 0) size = 50
                    setText(size.toString())
                }

                MaterialAlertDialogBuilder(requireContext()).setTitle("Log buffer size (kb)")
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        DataStore.logBufSize = view.text.toString().toInt()
                        if (DataStore.logBufSize <= 0) DataStore.logBufSize = 50
                        needRestart()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }

        mixedPort?.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        byeDpiPort?.setOnBindEditTextListener(EditTextPreferenceModifiers.Port)

        findPreference<Preference>(Key.METERED_NETWORK)?.let { metedNetwork ->
            if (Build.VERSION.SDK_INT < 28) {
                metedNetwork.remove()
            }
        }
        isProxyApps = findPreference(Key.PROXY_APPS)
        isProxyApps?.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }
        updateProxyAppsSummary()
        defaultTrafficModePreference = findPreference(Key.DEFAULT_TRAFFIC_MODE)
        defaultTrafficModePreference?.onPreferenceChangeListener = reloadListener
        appTrafficRulesPreference = findPreference("appTrafficRulesScreen")
        appTrafficRulesPreference?.setOnPreferenceClickListener {
            startActivity(Intent(activity, AppTrafficRulesActivity::class.java))
            true
        }
        updateAppTrafficRulesSummary()

        val profileTrafficStatistics =
            findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics?.isEnabled = speedInterval.value.toString() != "0"
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics?.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }

        serviceMode?.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)
        val resolveDestination = findPreference<SwitchPreference>(Key.RESOLVE_DESTINATION)
        val acquireWakeLock = findPreference<SwitchPreference>(Key.ACQUIRE_WAKE_LOCK)
        val enableClashAPI = findPreference<SwitchPreference>(Key.ENABLE_CLASH_API)
        enableClashAPI?.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }

        mixedPort?.onPreferenceChangeListener = reloadListener
        byeDpiPort?.onPreferenceChangeListener = reloadListener
        appendHttpProxy?.onPreferenceChangeListener = reloadListener
        showDirectSpeed?.onPreferenceChangeListener = reloadListener
        trafficSniffing?.onPreferenceChangeListener = reloadListener
        bypassLan?.onPreferenceChangeListener = reloadListener
        bypassLanInCore?.onPreferenceChangeListener = reloadListener
        mtu?.onPreferenceChangeListener = reloadListener

        enableFakeDns?.onPreferenceChangeListener = reloadListener
        remoteDns?.onPreferenceChangeListener = reloadListener
        directDns?.onPreferenceChangeListener = reloadListener
        enableDnsRouting?.onPreferenceChangeListener = reloadListener

        ipv6Mode?.onPreferenceChangeListener = reloadListener
        allowAccess?.onPreferenceChangeListener = reloadListener

        resolveDestination?.onPreferenceChangeListener = reloadListener
        tunImplementation?.onPreferenceChangeListener = reloadListener
        acquireWakeLock?.onPreferenceChangeListener = reloadListener
        globalCustomConfig?.onPreferenceChangeListener = reloadListener
    }

    override fun onResume() {
        super.onResume()

        isProxyApps?.let {
            it.isChecked = DataStore.proxyApps
            updateProxyAppsSummary()
        }
        if (appTrafficRulesPreference != null) {
            updateAppTrafficRulesSummary()
        }
        globalCustomConfig?.notifyChanged()
    }

    private fun updateAppTrafficRulesSummary() {
        val diagnostics = TrafficRoutingDiagnosticsResolver.snapshot()
        defaultTrafficModePreference?.let {
            it.isEnabled = diagnostics.ruleCount == 0
            it.summary = if (diagnostics.ruleCount == 0) {
                getString(R.string.default_traffic_mode_summary_legacy)
            } else {
                getString(R.string.default_traffic_mode_summary_rules_override)
            }
        }
        val baseSummary = if (diagnostics.ruleCount == 0) {
            getString(R.string.app_traffic_rules_summary_direct_default)
        } else {
            getString(
                R.string.app_traffic_rules_summary_explicit_only,
                diagnostics.ruleCount,
                diagnostics.remoteRuleCount,
                diagnostics.dpiBypassRuleCount
            )
        }
        appTrafficRulesPreference?.summary = baseSummary + "\n" + getString(R.string.traffic_mode_protocol_support)
    }

    private fun updateProxyAppsSummary() {
        val hasExplicitTrafficRules = DataStore.appTrafficRulesValidationReport.normalizedRules.isNotEmpty()
        isProxyApps?.isEnabled = !hasExplicitTrafficRules
        isProxyApps?.summary = when {
            hasExplicitTrafficRules -> getString(R.string.proxied_apps_summary_legacy_ignored)
            !DataStore.proxyApps -> getString(R.string.proxied_apps_summary_disabled)
            !DataStore.bypass -> getString(
                R.string.proxied_apps_summary_on_selected,
                DataStore.individual.lineSequence().count { it.isNotBlank() }
            )
            else -> getString(R.string.proxied_apps_summary_bypass_mode)
        }
    }

}
