package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*
import java.io.File

/**
 * One page of global_preferences.xml. Without arguments it shows the hub (the nested screens as
 * rows); with [PreferenceFragmentCompat.ARG_PREFERENCE_ROOT] it shows that nested screen only.
 * Every lookup below is null-safe because a page holds just its own preferences.
 */
class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private var isProxyApps: SwitchPreference? = null

    private var globalCustomConfig: EditConfigPreference? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    private fun sanitizeDnsPreferenceValue(value: String): String = value.lines().joinToString("\n") { line ->
        line.filterNot { it.isISOControl() }.trim()
    }

    private fun dnsReloadListener(
        preference: EditTextPreference,
        newValue: Any?,
        preprocess: (String) -> String = { it },
    ): Boolean {
        val rawValue = newValue as? String ?: return reloadListener.onPreferenceChange(preference, newValue)
        val sanitizedValue = sanitizeDnsPreferenceValue(preprocess(rawValue))
        if (sanitizedValue != rawValue) {
            preference.text = sanitizedValue
            needReload()
            return false
        }
        needReload()
        return true
    }

    private val multilineEditText = EditTextPreference.OnBindEditTextListener { editText ->
        editText.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editText.minLines = 4
        editText.maxLines = 12
        editText.setHorizontallyScrolling(false)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        setPreferencesFromResource(R.xml.global_preferences, rootKey)

        setupAppearance()
        setupConnection()
        setupProtection()
        setupRoute()
        setupDns()
        setupInbound()
        setupTls()
        setupNotification()
        setupAdvanced()
    }

    private fun setupAppearance() {
        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)
        findPreference<ThemePickerPreference>(Key.APP_THEME)?.setOnPreferenceChangeListener { _, newTheme ->
            if (DataStore.serviceState.started) {
                SagerNet.reloadService()
            }
            val themeId = newTheme as Int
            val previousTheme = DataStore.appTheme // still the old value at this point
            // Dark-only themes (Dracula, Dark High Contrast) only look right in
            // night mode: force it on so their dark canvas (values-night) takes
            // effect instead of a washed-out light surface. Remember the prior
            // night-mode setting so it can be restored when leaving such a theme.
            val enteringDarkOnly = themeId in Theme.DARK_ONLY_THEMES
            val leavingDarkOnly = previousTheme in Theme.DARK_ONLY_THEMES
            if (enteringDarkOnly) {
                if (!leavingDarkOnly && DataStore.nightTheme != 1) {
                    DataStore.nightThemeBeforeDracula = DataStore.nightTheme
                    Theme.currentNightMode = 1
                    // nightTheme.value persists to configurationStore (same key as
                    // DataStore.nightTheme) and refreshes the picker, so no separate
                    // DataStore.nightTheme write is needed.
                    nightTheme?.value = "1"
                    Theme.applyNightTheme()
                }
            } else if (leavingDarkOnly) {
                // Leaving a dark-only theme: restore the night-mode setting we forced on.
                val restore = DataStore.nightThemeBeforeDracula
                if (restore != -1) {
                    DataStore.nightThemeBeforeDracula = -1
                    Theme.currentNightMode = restore
                    nightTheme?.value = restore.toString()
                    Theme.applyNightTheme()
                }
            }
            Theme.apply(app, themeId)
            requireActivity().apply {
                Theme.apply(this, themeId)
                ActivityCompat.recreate(this)
            }
            true
        }

        nightTheme?.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            // A manual night-mode change takes precedence: drop any pending
            // dark-only-theme restore so we don't override the user's choice later.
            DataStore.nightThemeBeforeDracula = -1
            Theme.applyNightTheme()
            true
        }
        findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)?.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }
        findPreference<SwitchPreference>(Key.HIDE_FROM_RECENT_APPS)?.setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            true
        }
    }

    private fun setupConnection() {
        findPreference<Preference>(Key.SERVICE_MODE)?.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }
        findPreference<Preference>(Key.METERED_NETWORK)?.let {
            if (Build.VERSION.SDK_INT < 28) it.remove()
        }
        findPreference<Preference>(Key.NETWORK_AUTOMATION_RULES)?.setOnPreferenceClickListener {
            startActivity(Intent(activity, NetworkAutomationActivity::class.java))
            true
        }
        for (key in listOf(
            Key.TUN_IMPLEMENTATION,
            Key.MTU,
            Key.STRICT_ROUTE,
            Key.CONCURRENT_DIAL,
            Key.ACQUIRE_WAKE_LOCK,
        )) {
            findPreference<Preference>(key)?.onPreferenceChangeListener = reloadListener
        }
    }

    // ACTION_VPN_SETTINGS is API 24; on 23 the resolve fails and openSystemSettings falls back.
    @SuppressLint("InlinedApi")
    private fun setupProtection() {
        val openVpnSettings = Preference.OnPreferenceClickListener {
            openSystemSettings(Settings.ACTION_VPN_SETTINGS)
            true
        }
        findPreference<Preference>(Key.PROTECTION_ALWAYS_ON)?.onPreferenceClickListener = openVpnSettings
        findPreference<Preference>(Key.PROTECTION_LOCKDOWN)?.onPreferenceClickListener = openVpnSettings
        findPreference<Preference>(Key.PROTECTION_BATTERY)?.setOnPreferenceClickListener {
            openSystemSettings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            true
        }
        refreshProtection()
    }

    // Summaries reflect system state that changes outside the app, so they are recomputed on
    // every resume (after the user returns from the system settings).
    private fun refreshProtection() {
        val alwaysOn = findPreference<Preference>(Key.PROTECTION_ALWAYS_ON) ?: return
        val lockdown = findPreference<Preference>(Key.PROTECTION_LOCKDOWN) ?: return
        findPreference<Preference>(Key.KILL_SWITCH)?.isEnabled = DataStore.serviceMode == Key.MODE_VPN
        when {
            DataStore.serviceMode != Key.MODE_VPN -> {
                alwaysOn.setSummary(R.string.protection_proxy_mode)
                lockdown.setSummary(R.string.protection_proxy_mode)
            }

            Build.VERSION.SDK_INT < 29 -> {
                alwaysOn.setSummary(R.string.protection_unknown)
                lockdown.setSummary(R.string.protection_unknown)
            }

            else -> {
                // isAlwaysOn/isLockdownEnabled answer for the calling app through a static system
                // binder and never touch the service instance, so a bare VpnService object works
                // from the UI process while the tunnel is down.
                val vpn = android.net.VpnService()
                alwaysOn.setSummary(
                    if (vpn.isAlwaysOn) R.string.protection_always_on_enabled else R.string.protection_always_on_disabled,
                )
                lockdown.setSummary(
                    if (vpn.isLockdownEnabled) R.string.protection_lockdown_enabled else R.string.protection_lockdown_disabled,
                )
            }
        }
        findPreference<Preference>(Key.PROTECTION_BATTERY)?.setSummary(
            if (SagerNet.power.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                R.string.protection_battery_unrestricted
            } else {
                R.string.protection_battery_optimized
            },
        )
    }

    private fun openSystemSettings(action: String) {
        try {
            startActivity(Intent(action))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun setupRoute() {
        isProxyApps = findPreference<SwitchPreference>(Key.PROXY_APPS)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                startActivity(Intent(activity, AppManagerActivity::class.java))
                if (newValue as Boolean) DataStore.dirty = true
                newValue
            }
        }
        for (key in listOf(
            Key.BYPASS_LAN,
            Key.BYPASS_LAN_IN_CORE,
            Key.TRAFFIC_SNIFFING,
            Key.RESOLVE_DESTINATION,
            Key.IPV6_MODE,
        )) {
            findPreference<Preference>(key)?.onPreferenceChangeListener = reloadListener
        }

        val rulesGeositeUrl = findPreference<EditTextPreference>(Key.RULES_GEOSITE_URL)
        val rulesGeoipUrl = findPreference<EditTextPreference>(Key.RULES_GEOIP_URL)
        rulesGeositeUrl?.isVisible = DataStore.rulesProvider == 4
        rulesGeoipUrl?.isVisible = DataStore.rulesProvider == 4
        findPreference<SimpleMenuPreference>(Key.RULES_PROVIDER)?.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toInt()
            rulesGeositeUrl?.isVisible = provider == 4
            rulesGeoipUrl?.isVisible = provider == 4
            true
        }
    }

    private fun setupDns() {
        findPreference<EditTextPreference>(Key.REMOTE_DNS)?.let { remoteDns ->
            remoteDns.setOnPreferenceChangeListener { _, newValue -> dnsReloadListener(remoteDns, newValue) }
        }
        findPreference<EditTextPreference>(Key.DIRECT_DNS)?.let { directDns ->
            directDns.setOnPreferenceChangeListener { _, newValue -> dnsReloadListener(directDns, newValue) }
        }
        findPreference<SwitchPreference>(Key.ENABLE_DNS_ROUTING)?.onPreferenceChangeListener = reloadListener
        findPreference<SwitchPreference>(Key.ENABLE_FAKEDNS)?.onPreferenceChangeListener = reloadListener
        findPreference<EditTextPreference>(Key.DNS_HOSTS)?.let { dnsHosts ->
            dnsHosts.setOnBindEditTextListener(multilineEditText)
            // Concise summary: the hosts list can be long and multiline, so show a line
            // count instead of dumping the raw value into the preference row. Comment
            // lines are excluded so the number reflects entries, not text lines.
            dnsHosts.summaryProvider = Preference.SummaryProvider<EditTextPreference> { preference ->
                val count = preference.text.orEmpty()
                    .lineSequence()
                    .map { it.trim() }
                    .count { it.isNotEmpty() && !it.startsWith("#") }
                if (count == 0) {
                    preference.context.getString(R.string.not_set)
                } else {
                    preference.context.resources.getQuantityString(R.plurals.dns_hosts_lines, count, count)
                }
            }
            dnsHosts.setOnPreferenceChangeListener { _, newValue ->
                // Tabs are valid separators in pasted hosts entries; convert them to
                // spaces first so the control-character sanitization does not merge
                // the domain and address tokens together.
                dnsReloadListener(dnsHosts, newValue) { it.replace('\t', ' ') }
            }
        }
    }

    private fun setupInbound() {
        findPreference<EditTextPreference>(Key.MIXED_PORT)?.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
            onPreferenceChangeListener = reloadListener
        }
        findPreference<SwitchPreference>(Key.APPEND_HTTP_PROXY)?.let { appendHttpProxy ->
            appendHttpProxy.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    MaterialAlertDialogBuilder(requireContext()).apply {
                        setTitle(R.string.append_http_proxy_security_title)
                        setMessage(R.string.append_http_proxy_security_message)
                        setNegativeButton(android.R.string.cancel, null)
                        setPositiveButton(R.string.enable_anyway) { _, _ ->
                            appendHttpProxy.isChecked = true
                            needReload()
                        }
                    }.show()
                    false
                } else {
                    needReload()
                    true
                }
            }
        }
        findPreference<EditTextPreference>(Key.HTTP_PROXY_BYPASS)?.apply {
            setOnBindEditTextListener(multilineEditText)
            // Pre-fill with the stored value (or the default when unset) so opening
            // the dialog and tapping OK doesn't overwrite the list with an empty
            // string. Persist the default once so it survives untouched edits.
            text = DataStore.httpProxyBypass
            onPreferenceChangeListener = reloadListener
        }
        findPreference<Preference>(Key.ALLOW_ACCESS)?.onPreferenceChangeListener = reloadListener
    }

    private fun setupTls() {
        findPreference<SwitchPreference>(Key.ENABLE_TLS_FRAGMENT)?.onPreferenceChangeListener = reloadListener
    }

    private fun setupNotification() {
        val profileTrafficStatistics = findPreference<SwitchPreference>(Key.PROFILE_TRAFFIC_STATISTICS)
        findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)?.let { speedInterval ->
            profileTrafficStatistics?.isEnabled = speedInterval.value.toString() != "0"
            speedInterval.setOnPreferenceChangeListener { _, newValue ->
                profileTrafficStatistics?.isEnabled = newValue.toString() != "0"
                needReload()
                true
            }
        }
        findPreference<SwitchPreference>(Key.SHOW_DIRECT_SPEED)?.onPreferenceChangeListener = reloadListener
    }

    private fun setupAdvanced() {
        findPreference<LongClickListPreference>(Key.LOG_LEVEL)?.apply {
            dialogLayoutResource = R.layout.layout_loglevel_help
            setOnPreferenceChangeListener { _, _ ->
                needRestart()
                true
            }
            setOnLongClickListener {
                if (context == null) return@setOnLongClickListener true

                val view = EditText(context).apply {
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
        globalCustomConfig = findPreference<EditConfigPreference>(Key.GLOBAL_CUSTOM_CONFIG)?.apply {
            useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)
        }
        findPreference<SwitchPreference>(Key.ENABLE_CLASH_API)?.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }

        // reset to default settings feature
        findPreference<Preference>("resetSettings")?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.confirm)
                setMessage(R.string.reset_settings_message)
                setNegativeButton(R.string.no, null)
                setPositiveButton(R.string.yes) { _, _ ->
                    // reset() clears the snapshot synchronously but commits the DB wipe on the
                    // ordered disk executor; await it before restarting so the rebirth can't race
                    // ahead of the commit and leave old settings on disk.
                    runOnDefaultDispatcher {
                        var ok = false
                        try {
                            DataStore.configurationStore.reset()
                            DataStore.configurationStore.awaitWrites()
                            ok = true
                        } catch (e: Exception) {
                            Logs.w(e)
                        }
                        onMainDispatcher {
                            // Only restart if the DB wipe actually committed; otherwise a rebirth
                            // could race ahead of the commit and leave old settings on disk.
                            if (ok && isAdded) triggerFullRestart(requireContext())
                        }
                    }
                }
            }.show()
            true
        }

        // clear cache feature
        findPreference<Preference>(Key.CLEAR_CACHE)?.setOnPreferenceClickListener {
            MaterialAlertDialogBuilder(requireContext()).apply {
                setTitle(R.string.clear_cache)
                setMessage(R.string.clear_cache_confirm)
                setPositiveButton(android.R.string.ok) { _, _ ->
                    clearAppCache()
                }
                setNegativeButton(android.R.string.cancel, null)
            }.show()
            true
        }
    }

    override fun onResume() {
        super.onResume()

        isProxyApps?.isChecked = DataStore.proxyApps
        globalCustomConfig?.notifyChanged()
        refreshProtection()
    }

    private fun clearAppCache() {
        try {
            val cacheDir = SagerNet.application.cacheDir
            clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))

            val parentDir = cacheDir.parentFile
            val relativeCache = File(parentDir, "cache")
            if (relativeCache.exists() && relativeCache.isDirectory) {
                clearDirFiles(relativeCache)
            }

            Toast.makeText(requireContext(), R.string.clear_cache_success, Toast.LENGTH_SHORT).show()

            Handler(Looper.getMainLooper()).postDelayed({
                needReload()
            }, 500)
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.clear_cache_failed, e.message),
                Toast.LENGTH_SHORT,
            ).show()
            e.printStackTrace()
        }
    }

    private fun clearDirFiles(dir: File, skipFiles: Set<String> = emptySet()): Boolean {
        if (dir.isDirectory) {
            val children = dir.list() ?: return true

            for (child in children) {
                val childFile = File(dir, child)

                if (child == "neko.log") {
                    try {
                        childFile.writeText("")
                        continue
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                if (child in skipFiles) {
                    continue
                }

                if (childFile.isDirectory) {
                    clearDirFiles(childFile, skipFiles)
                } else {
                    childFile.delete()
                }
            }

            return true
        }
        return false
    }
}
