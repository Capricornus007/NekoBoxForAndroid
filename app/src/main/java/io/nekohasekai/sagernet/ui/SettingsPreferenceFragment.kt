package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.preference.*
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.SpeedTestSettings
import io.nekohasekai.sagernet.api.LocalApiAccess
import io.nekohasekai.sagernet.bg.LocationSpoofing
import io.nekohasekai.sagernet.bg.LocationTurnOffReceiver
import io.nekohasekai.sagernet.bg.NetworkAutomation
import io.nekohasekai.sagernet.bg.RuleAssetUpdater
import io.nekohasekai.sagernet.bg.parseMockCoordinates
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.root.RootLanSharing
import io.nekohasekai.sagernet.root.RootManager
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Theme
import io.nekohasekai.sagernet.widget.enableCollapsibleCategories
import moe.matsuri.nb4a.ui.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private var isProxyApps: SwitchPreferenceCompat? = null
    private var globalCustomConfig: EditConfigPreference? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
    }

    // 多行輸入框的統一設定：hosts 類清單（dnsHosts / httpProxyBypass）共用同一個 binding listener，
    // 取代各處重複的匿名 lambda。
    private val multilineEditText = EditTextPreference.OnBindEditTextListener { editText ->
        editText.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        editText.minLines = 4
        editText.maxLines = 12
        editText.setHorizontallyScrolling(false)
    }

    private fun localProxySummary(): String {
        val bind = if (DataStore.allowAccess) "0.0.0.0" else "127.0.0.1"
        return "$bind  SOCKS:${DataStore.socksPort}  HTTP:${DataStore.httpPort}"
    }

    private fun showLocalProxyDialog(preference: Preference) {
        val view = layoutInflater.inflate(R.layout.layout_local_proxy_dialog, null)
        val socksField = view.findViewById<TextInputEditText>(R.id.socksPortField)
        val httpField = view.findViewById<TextInputEditText>(R.id.httpPortField)
        val userField = view.findViewById<TextInputEditText>(R.id.proxyUsernameField)
        val passField = view.findViewById<TextInputEditText>(R.id.proxyPasswordField)
        val allowSwitch = view.findViewById<SwitchCompat>(R.id.allowAccessSwitch)

        socksField.setText(DataStore.socksPort.toString())
        httpField.setText(DataStore.httpPort.toString())
        userField.setText(DataStore.mixedUsername)
        passField.setText(DataStore.mixedPassword)
        allowSwitch.isChecked = DataStore.allowAccess

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.local_proxy_settings)
            .setView(view)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val sp = socksField.text.toString().toIntOrNull()
                val hp = httpField.text.toString().toIntOrNull()
                if (sp == null || hp == null || sp !in 1..65535 || hp !in 1..65535) {
                    Toast.makeText(
                        requireContext(),
                        R.string.port_out_of_range,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setPositiveButton
                }
                DataStore.socksPort = sp
                DataStore.httpPort = hp
                DataStore.mixedUsername = userField.text.toString().trim()
                DataStore.mixedPassword = passField.text.toString()
                DataStore.allowAccess = allowSwitch.isChecked
                preference.summary = localProxySummary()
                needReload()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        DataStore.initGlobal()
        addPreferencesFromResource(R.xml.global_preferences)

        setupAppearance()
        setupConnection()
        setupProtection()
        setupLocationSpoofing()
        setupRoute()
        setupDns()
        setupInbound()
        setupTls()
        setupNotification()
        setupAdvanced()

        // 放在最後：上面那一大批 findPreference(...) 要在整棵樹都還完整時綁定，
        // 折疊只改 isVisible，不影響查找，但綁定先跑完才不會有「藏在收合群組裡的項沒接上」。
        preferenceScreen?.enableCollapsibleCategories("global")
    }

    private fun setupAppearance() {
        findPreference<Preference>(Key.DYNAMIC_COLORS)?.let { dynamicColors ->
            dynamicColors.isEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
            dynamicColors.summary = getString(
                if (dynamicColors.isEnabled) {
                    R.string.dynamic_colors_summary
                } else {
                    R.string.dynamic_colors_unavailable
                },
            )
            dynamicColors.setOnPreferenceChangeListener { _, _ ->
                recreateActivityAfterPreferencePersisted()
                true
            }
        }

        findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)?.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            // A manual night-mode change takes precedence: drop any pending
            // dark-only-theme restore so we don't override the user's choice later.
            DataStore.nightThemeBeforeDracula = -1
            Theme.applyNightTheme()
            recreateActivityAfterPreferencePersisted()
            true
        }

        findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)?.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }

        findPreference<Preference>(Key.HIDE_FROM_RECENT_APPS)?.setOnPreferenceChangeListener { _, newValue ->
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
        findPreference<Preference>(Key.NETWORK_AUTOMATION)?.setOnPreferenceChangeListener { preference, newValue ->
            NetworkAutomation.onSwitched(preference.context.applicationContext, newValue as Boolean)
            refreshAutomation()
            true
        }
        findPreference<Preference>(Key.NETWORK_AUTOMATION_RULES)?.setOnPreferenceClickListener {
            startActivity(Intent(activity, NetworkAutomationActivity::class.java))
            true
        }
        refreshAutomation()

        findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)?.let { tunImplementation ->
            // androidx 的 setSummary 在已設 SummaryProvider 的偏好上會直接拋
            // IllegalStateException（線上 crash 實錘：打開設定頁即閃退）。所以接管
            // 提示必須走 SummaryProvider 交換：先存原 provider，hev 關閉時還原。
            val tunOriginalSummaryProvider = tunImplementation.summaryProvider
            fun applyHevTunOverride(hevOn: Boolean) {
                tunImplementation.isEnabled = !hevOn
                tunImplementation.summaryProvider =
                    if (hevOn) {
                        Preference.SummaryProvider<SimpleMenuPreference> {
                            getString(R.string.tun_implementation_hev_override)
                        }
                    } else {
                        tunOriginalSummaryProvider
                    }
            }
            applyHevTunOverride(DataStore.enableHevTun)
            findPreference<Preference>(Key.ENABLE_HEV_TUN)?.setOnPreferenceChangeListener { _, newValue ->
                applyHevTunOverride(newValue as Boolean)
                needReload()
                true
            }
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

        // LAN 分享是 root 改 iptables + ip rule，原本只在連線（BaseService.lateInit）時套用、
        // 只在隧道關閉時拆除，而且這個開關完全沒掛 listener：開 ON 沒反應，關 OFF 後防火牆規則
        // 還留著（熱點上的人繼續走隧道）。這裡直接對稱起／拆，避免用重啟服務把使用者的連線全部打掉。
        findPreference<SwitchPreferenceCompat>(
            Key.LAN_SHARING,
        )?.setOnPreferenceChangeListener { preference, newValue ->
            val enabled = newValue as Boolean
            runOnDefaultDispatcher {
                try {
                    // :bg 起會立刻回讀設定，先讓寫入落盤
                    DataStore.configurationStore.awaitWrites()
                } catch (e: Exception) {
                    Logs.w(e)
                }
                // root 不可用時原本的狀態是「開關打開、什麼都沒發生、只在日誌留一行 warning」，
                // 使用者會以為已經生效。探測 root 要起 su 進程（最多等 10 秒）不能放主執行緒，
                // 所以先讓它打開、探不到再退回並說明原因。
                if (enabled && !RootManager.cachedRoot() && !RootManager.refresh()) {
                    runOnMainDispatcher {
                        (preference as SwitchPreferenceCompat).isChecked = false
                        if (isAdded) {
                            Toast.makeText(
                                requireContext(),
                                R.string.lan_sharing_requires_root,
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    }
                    return@runOnDefaultDispatcher
                }
                // 隧道沒在跑時不動作：此時沒有可分享的進程，設定留給下次連線由 lateInit 套用。
                if (DataStore.serviceState.canStop) {
                    // 不去判回傳值：startClientSharing 回 false 只代表「已在跑」（無害）；
                    // root 不可用已在上面擋掉了，這裡不會再走到那條 Logs.w 分支。
                    // stopClientSharing 未啟動時是安全空轉。
                    if (enabled) {
                        RootLanSharing.startClientSharing(SagerNet.application)
                    } else {
                        RootLanSharing.stopClientSharing(SagerNet.application)
                    }
                }
            }
            true
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
        findPreference<Preference>(Key.WEBRTC_LEAK_PROTECTION)?.onPreferenceChangeListener = reloadListener
        refreshProtection()
    }

    // ACTION_APP_NOTIFICATION_SETTINGS is API 26; on older releases openSystemSettings falls back.
    @SuppressLint("InlinedApi")
    private fun setupLocationSpoofing() {
        findPreference<SwitchPreferenceCompat>(Key.GPS_SPOOFING)?.apply {
            setOnPreferenceChangeListener { _, newValue ->
                val context = requireContext()
                if (newValue == false) {
                    // Stops spoofing at once; the VPN stays connected.
                    context.sendBroadcast(Intent(context, LocationTurnOffReceiver::class.java))
                    return@setOnPreferenceChangeListener true
                }
                val dialog = MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.gps_spoofing)
                    .setNegativeButton(android.R.string.cancel, null)
                when {
                    !LocationSpoofing.supported(context) -> dialog.setMessage(R.string.gps_spoofing_unsupported)
                        .setNegativeButton(android.R.string.ok, null)

                    // Nothing is added while an earlier simulated location may remain, so the switch
                    // would show spoofing that cannot run.
                    LocationSpoofing.mayRemain(context) -> dialog.setMessage(R.string.gps_spoofing_remove_first)
                        .setPositiveButton(R.string.gps_spoofing_remove_action) { _, _ -> askToRemoveSimulatedLocation() }

                    !LocationSpoofing.mockLocationAllowed(context) -> dialog.setMessage(R.string.gps_spoofing_setup)
                        .setPositiveButton(R.string.settings) { _, _ ->
                            openSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        }

                    !LocationSpoofing.notificationsAllowed(context) -> dialog.setMessage(R.string.gps_spoofing_notifications)
                        .setPositiveButton(R.string.settings) { _, _ ->
                            openSystemSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS) {
                                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                        }

                    else -> dialog.setMessage(getString(R.string.gps_spoofing_warning, DataStore.gpsLookupUrl))
                        .setPositiveButton(R.string.enable_anyway) { _, _ ->
                            isChecked = true
                            needReload()
                        }
                }
                dialog.show()
                false
            }
        }
        findPreference<Preference>(Key.GPS_SPOOFING_REMOVE)?.setOnPreferenceClickListener {
            askToRemoveSimulatedLocation()
            true
        }
        findPreference<EditTextPreference>(Key.GPS_COORDINATES)?.setOnPreferenceChangeListener { _, newValue ->
            if (runCatching { parseMockCoordinates(newValue as String) }.isFailure) {
                MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.gps_spoofing_invalid_coordinates)
                    .setPositiveButton(android.R.string.ok, null).show()
                false
            } else {
                needReload()
                true
            }
        }
        findPreference<EditTextPreference>(Key.GPS_LOOKUP_URL)?.setOnPreferenceChangeListener { _, newValue ->
            val url = (newValue as String).toHttpUrlOrNull()
            if (url == null || !url.isHttps || url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
                MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.gps_spoofing_invalid_url)
                    .setPositiveButton(android.R.string.ok, null).show()
                false
            } else {
                needReload()
                true
            }
        }
    }

    private fun askToRemoveSimulatedLocation() {
        val context = requireContext()
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.gps_spoofing_remove)
            .setNegativeButton(android.R.string.cancel, null)
        // Only the mock location app can remove a test provider.
        if (LocationSpoofing.mockLocationAllowed(context)) {
            dialog.setMessage(getString(R.string.gps_spoofing_remove_confirm, LocationSpoofing.standardProviders.joinToString(", ")))
                .setPositiveButton(R.string.gps_spoofing_remove_action) { _, _ -> removeSimulatedLocation() }
        } else {
            dialog.setMessage(R.string.gps_spoofing_remove_setup)
                .setPositiveButton(R.string.settings) { _, _ -> openSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
        }
        dialog.show()
    }

    // The background process removes it and turns spoofing off; it reports whether nothing remains.
    private fun removeSimulatedLocation() {
        val context = requireContext()
        findPreference<SwitchPreferenceCompat>(Key.GPS_SPOOFING)?.isChecked = false
        val outcome = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!isAdded) return
                snackbar(if (resultCode == Activity.RESULT_OK) R.string.gps_spoofing_removed else R.string.gps_spoofing_remove_failed).show()
                refreshProtection()
            }
        }
        context.sendOrderedBroadcast(LocationSpoofing.confirmedRemoval(context), null, outcome, null, Activity.RESULT_CANCELED, null, null)
    }

    // Summaries reflect system state that changes outside the app, so they are recomputed on
    // every resume (after the user returns from the system settings).
    private fun refreshProtection() {
        val alwaysOn = findPreference<Preference>(Key.PROTECTION_ALWAYS_ON) ?: return
        val lockdown = findPreference<Preference>(Key.PROTECTION_LOCKDOWN) ?: return
        val vpnMode = DataStore.serviceMode == Key.MODE_VPN
        findPreference<Preference>(Key.KILL_SWITCH)?.isEnabled = vpnMode
        findPreference<Preference>(Key.WEBRTC_LEAK_PROTECTION)?.isEnabled = vpnMode
        findPreference<SwitchPreferenceCompat>(Key.GPS_SPOOFING)?.apply {
            isEnabled = vpnMode
            isChecked = DataStore.gpsSpoofing
        }
        findPreference<Preference>(Key.GPS_SPOOFING_REMOVE)?.isVisible = LocationSpoofing.mayRemain(requireContext())
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

    // A refusal or a pause stays visible here until automation works again.
    private fun refreshAutomation() {
        findPreference<Preference>(Key.NETWORK_AUTOMATION)?.setSummary(NetworkAutomation.status())
    }

    private fun openSystemSettings(action: String, extras: Intent.() -> Unit = {}) {
        try {
            startActivity(Intent(action).apply(extras))
        } catch (_: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun setupRoute() {
        isProxyApps = findPreference<SwitchPreferenceCompat>(Key.PROXY_APPS)?.apply {
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

        // 後台的規則資產排程要跟著開關一起改，否則關掉開關後工作還是照跑。
        findPreference<Preference>(Key.AUTO_UPDATE_RULE_ASSETS)?.setOnPreferenceChangeListener { _, _ ->
            RuleAssetUpdater.reconfigure()
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
        findPreference<Preference>(Key.ENABLE_DNS_ROUTING)?.onPreferenceChangeListener = reloadListener
        findPreference<Preference>(Key.ENABLE_FAKEDNS)?.onPreferenceChangeListener = reloadListener
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
                    preference.context.resources.getString(R.string.dns_hosts_lines, count)
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
        // 本機代理用單一對話框一次處理 SOCKS/HTTP 端口、帳密與允許遠端存取，
        // 所以這裡不再替 mixedPort / allowAccess 單獨開輸入框（它仍會即時重載）。
        findPreference<Preference>("localProxySettings")?.let { localProxySettings ->
            localProxySettings.summary = localProxySummary()
            localProxySettings.setOnPreferenceClickListener {
                showLocalProxyDialog(localProxySettings)
                true
            }
        }
        findPreference<EditTextPreference>(Key.MIXED_PORT)?.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
            onPreferenceChangeListener = reloadListener
        }
        findPreference<SwitchPreferenceCompat>(Key.APPEND_HTTP_PROXY)?.let { appendHttpProxy ->
            appendHttpProxy.setOnPreferenceChangeListener { _, newValue ->
                if (newValue as Boolean) {
                    MaterialAlertDialogBuilder(requireContext()).apply {
                        setTitle(R.string.append_http_proxy_security_title)
                        setMessage(R.string.append_http_proxy_security_message)
                        setNegativeButton(android.R.string.cancel, null)
                        setPositiveButton(R.string.enable_anyway) { _, _ ->
                            appendHttpProxy.isChecked = true
                            if (DataStore.serviceState.started) {
                                SagerNet.reloadService()
                            }
                        }
                    }.show()
                    false
                } else {
                    if (DataStore.serviceState.started) {
                        SagerNet.reloadService()
                    }
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
        findPreference<Preference>(Key.ENABLE_TLS_FRAGMENT)?.onPreferenceChangeListener = reloadListener
    }

    private fun setupNotification() {
        val profileTrafficStatistics = findPreference<SwitchPreferenceCompat>(Key.PROFILE_TRAFFIC_STATISTICS)
        findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)?.let { speedInterval ->
            profileTrafficStatistics?.isEnabled = speedInterval.value.toString() != "0"
            speedInterval.setOnPreferenceChangeListener { _, newValue ->
                profileTrafficStatistics?.isEnabled = newValue.toString() != "0"
                needReload()
                true
            }
        }
        findPreference<Preference>(Key.SHOW_DIRECT_SPEED)?.onPreferenceChangeListener = reloadListener
    }

    private fun setupAdvanced() {
        findPreference<Preference>("localControlApi")?.setOnPreferenceClickListener {
            LocalApiAccess.showSettings(requireActivity())
            true
        }

        findPreference<LongClickListPreference>(Key.LOG_LEVEL)?.apply {
            dialogLayoutResource = R.layout.layout_loglevel_help
            setOnPreferenceChangeListener { _, _ ->
                needRestart()
                true
            }
            setOnLongClickListener {
                val ctx = context ?: return@setOnLongClickListener true

                val view = EditText(ctx).apply {
                    inputType = EditorInfo.TYPE_CLASS_NUMBER
                    var size = DataStore.logBufSize
                    if (size == 0) size = 50
                    setText(size.toString())
                }

                MaterialAlertDialogBuilder(ctx).setTitle(R.string.dialog_log_buffer_size)
                    .setView(ctx.wrapDialogContent(view))
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

        // 速度測試：模式與逾時都要先驗證再落地，髒值會讓 SpeedTestRunner 直接拋。
        findPreference<SimpleMenuPreference>(Key.SPEED_TEST_MODE)?.setOnPreferenceChangeListener { _, newValue ->
            SpeedTestSettings.isValidMode(newValue.toString())
        }
        findPreference<EditTextPreference>(Key.SPEED_TEST_TIMEOUT_MS)?.let { speedTestTimeout ->
            speedTestTimeout.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
            speedTestTimeout.setOnPreferenceChangeListener { _, newValue ->
                val valid = SpeedTestSettings.isValidTimeout(newValue.toString())
                if (!valid) {
                    Toast.makeText(
                        requireContext(),
                        R.string.speed_test_timeout_invalid,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                valid
            }
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

        findPreference<Preference>("createClearCacheShortcut")?.setOnPreferenceClickListener {
            val pinned = requestPinClearCacheShortcut(requireContext())
            Toast.makeText(
                requireContext(),
                if (pinned) R.string.shortcut_pin_requested else R.string.shortcut_pin_not_supported,
            ).show()
            true
        }
    }

    private fun recreateActivityAfterPreferencePersisted() {
        listView.postDelayed({
            activity?.let(ActivityCompat::recreate)
        }, 100L)
    }

    override fun onResume() {
        super.onResume()

        isProxyApps?.isChecked = DataStore.proxyApps
        globalCustomConfig?.notifyChanged()
        refreshProtection()
        refreshAutomation()
    }

    private fun clearAppCache() {
        runOnDefaultDispatcher {
            try {
                SagerNet.stopService()
                Thread.sleep(300)

                val cacheDir = SagerNet.application.cacheDir
                clearDirFiles(cacheDir, skipFiles = setOf("neko.log"))

                val parentDir = cacheDir.parentFile
                val relativeCache = File(parentDir, "cache")
                if (relativeCache.exists() && relativeCache.isDirectory) {
                    clearDirFiles(relativeCache)
                }

                onMainDispatcher {
                    Toast.makeText(
                        requireContext(),
                        R.string.clear_cache_success,
                        Toast.LENGTH_SHORT,
                    ).show()
                    triggerFullRestart(requireContext())
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.clear_cache_failed, e.message),
                        Toast.LENGTH_SHORT,
                    ).show()
                }
                e.printStackTrace()
            }
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
