package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
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
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.root.RootLanSharing
import io.nekohasekai.sagernet.root.RootManager
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Theme
import moe.matsuri.nb4a.ui.*
import java.io.File

class SettingsPreferenceFragment : PreferenceFragmentCompat() {

    private lateinit var isProxyApps: SwitchPreferenceCompat
    private lateinit var globalCustomConfig: EditConfigPreference

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
    }

    private val reloadListener = Preference.OnPreferenceChangeListener { _, _ ->
        needReload()
        true
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

    private fun sanitizeDnsPreferenceValue(value: String): String {
        return value.lines().joinToString("\n") { line ->
            line.filterNot { it.isISOControl() }.trim()
        }
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
        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)!!
        val dynamicColors = findPreference<SwitchPreferenceCompat>(Key.DYNAMIC_COLORS)!!

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

        nightTheme.setOnPreferenceChangeListener { _, newTheme ->
            Theme.currentNightMode = (newTheme as String).toInt()
            DataStore.nightThemeBeforeDracula = -1
            Theme.applyNightTheme()
            recreateActivityAfterPreferencePersisted()
            true
        }

        val appLanguage = findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)!!
        appLanguage.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }
        val localProxySettings = findPreference<Preference>("localProxySettings")!!
        val httpProxyBypass = findPreference<EditTextPreference>(Key.HTTP_PROXY_BYPASS)!!
        val dnsHosts = findPreference<EditTextPreference>(Key.DNS_HOSTS)!!
        val serviceMode = findPreference<Preference>(Key.SERVICE_MODE)!!
        val appendHttpProxy = findPreference<SwitchPreferenceCompat>(Key.APPEND_HTTP_PROXY)!!
        val strictRoute = findPreference<SwitchPreferenceCompat>(Key.STRICT_ROUTE)!!
        val speedTestMode = findPreference<SimpleMenuPreference>(Key.SPEED_TEST_MODE)!!
        val speedTestTimeout = findPreference<EditTextPreference>(Key.SPEED_TEST_TIMEOUT_MS)!!
        val simpleDownloadURL = findPreference<EditTextPreference>(Key.SIMPLE_DOWNLOAD_URL)!!
        val showDirectSpeed = findPreference<SwitchPreferenceCompat>(Key.SHOW_DIRECT_SPEED)!!
        val ipv6Mode = findPreference<Preference>(Key.IPV6_MODE)!!
        val trafficSniffing = findPreference<Preference>(Key.TRAFFIC_SNIFFING)!!

        val bypassLan = findPreference<SwitchPreferenceCompat>(Key.BYPASS_LAN)!!
        val bypassLanInCore = findPreference<SwitchPreferenceCompat>(Key.BYPASS_LAN_IN_CORE)!!

        val remoteDns = findPreference<EditTextPreference>(Key.REMOTE_DNS)!!
        val directDns = findPreference<EditTextPreference>(Key.DIRECT_DNS)!!
        val enableDnsRouting = findPreference<SwitchPreferenceCompat>(Key.ENABLE_DNS_ROUTING)!!
        val enableFakeDns = findPreference<SwitchPreferenceCompat>(Key.ENABLE_FAKEDNS)!!

        val enableTLSFragment = findPreference<SwitchPreferenceCompat>(Key.ENABLE_TLS_FRAGMENT)!!

        val logLevel = findPreference<LongClickListPreference>(Key.LOG_LEVEL)!!
        val mtu = findPreference<MTUPreference>(Key.MTU)!!
        globalCustomConfig = findPreference(Key.GLOBAL_CUSTOM_CONFIG)!!
        globalCustomConfig.useConfigStore(Key.GLOBAL_CUSTOM_CONFIG)

        logLevel.dialogLayoutResource = R.layout.layout_loglevel_help
        logLevel.setOnPreferenceChangeListener { _, _ ->
            needRestart()
            true
        }
        logLevel.setOnLongClickListener {
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
        httpProxyBypass.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)
        dnsHosts.setOnBindEditTextListener(EditTextPreferenceModifiers.Hosts)

        speedTestMode.setOnPreferenceChangeListener { _, newValue ->
            SpeedTestSettings.isValidMode(newValue.toString())
        }
        speedTestTimeout.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        speedTestTimeout.setOnPreferenceChangeListener { _, newValue ->
            val valid = SpeedTestSettings.isValidTimeout(newValue.toString())
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_timeout_invalid, Toast.LENGTH_SHORT).show()
            }
            valid
        }
        simpleDownloadURL.setOnBindEditTextListener { editText ->
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            editText.setSingleLine()
        }
        simpleDownloadURL.setOnPreferenceChangeListener { preference, newValue ->
            val value = newValue.toString().trim()
            val valid = SpeedTestSettings.isValidHttpUrl(value)
            if (!valid) {
                Toast.makeText(requireContext(), R.string.speed_test_url_invalid, Toast.LENGTH_SHORT).show()
            } else if (value != newValue) {
                (preference as EditTextPreference).text = value
                return@setOnPreferenceChangeListener false
            }
            valid
        }

        val metedNetwork = findPreference<Preference>(Key.METERED_NETWORK)!!
        if (Build.VERSION.SDK_INT < 28) {
            metedNetwork.remove()
        }

        isProxyApps = findPreference(Key.PROXY_APPS)!!
        isProxyApps.setOnPreferenceChangeListener { _, newValue ->
            startActivity(Intent(activity, AppManagerActivity::class.java))
            if (newValue as Boolean) DataStore.dirty = true
            newValue
        }

        val profileTrafficStatistics =
            findPreference<SwitchPreferenceCompat>(Key.PROFILE_TRAFFIC_STATISTICS)!!
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)!!
        profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
        speedInterval.setOnPreferenceChangeListener { _, newValue ->
            profileTrafficStatistics.isEnabled = newValue.toString() != "0"
            needReload()
            true
        }

        serviceMode.setOnPreferenceChangeListener { _, _ ->
            if (DataStore.serviceState.started) SagerNet.stopService()
            true
        }

        val tunImplementation = findPreference<SimpleMenuPreference>(Key.TUN_IMPLEMENTATION)!!
        val enableHevTun = findPreference<SwitchPreferenceCompat>(Key.ENABLE_HEV_TUN)!!
        // androidx 的 setSummary 在已设 SummaryProvider 的偏好上会直接抛
        // IllegalStateException（线上 crash 实锤：打开设置页即闪退）。所以接管
        // 提示必须走 SummaryProvider 交换：先存原 provider，hev 关闭时还原。
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
        enableHevTun.setOnPreferenceChangeListener { _, newValue ->
            applyHevTunOverride(newValue as Boolean)
            needReload()
            true
        }
        val resolveDestination = findPreference<SwitchPreferenceCompat>(Key.RESOLVE_DESTINATION)!!
        val acquireWakeLock = findPreference<SwitchPreferenceCompat>(Key.ACQUIRE_WAKE_LOCK)!!
        val hideFromRecentApps = findPreference<SwitchPreferenceCompat>(Key.HIDE_FROM_RECENT_APPS)!!
        val enableClashAPI = findPreference<SwitchPreferenceCompat>(Key.ENABLE_CLASH_API)!!
        enableClashAPI.setOnPreferenceChangeListener { _, newValue ->
            (activity as MainActivity?)?.refreshNavMenu(newValue as Boolean)
            needReload()
            true
        }

        val rulesProvider = findPreference<SimpleMenuPreference>(Key.RULES_PROVIDER)!!
        val rulesGeositeUrl = findPreference<EditTextPreference>(Key.RULES_GEOSITE_URL)!!
        val rulesGeoipUrl = findPreference<EditTextPreference>(Key.RULES_GEOIP_URL)!!
        rulesGeositeUrl.isVisible = DataStore.rulesProvider == 4
        rulesGeoipUrl.isVisible = DataStore.rulesProvider == 4
        rulesProvider.setOnPreferenceChangeListener { _, newValue ->
            val provider = (newValue as String).toInt()
            rulesGeositeUrl.isVisible = provider == 4
            rulesGeoipUrl.isVisible = provider == 4
            true
        }
        localProxySettings.summary = localProxySummary()
        localProxySettings.setOnPreferenceClickListener {
            showLocalProxyDialog(localProxySettings)
            true
        }

        appendHttpProxy.setOnPreferenceChangeListener { _, newValue ->
            if (newValue as Boolean) {
                MaterialAlertDialogBuilder(requireContext()).apply {
                    setTitle(R.string.append_http_proxy_security_title)
                    setMessage(R.string.append_http_proxy_security_message)
                    setNegativeButton(android.R.string.cancel, null)
                    setPositiveButton(R.string.enable_anyway) { _, _ ->
                        appendHttpProxy.isChecked = true
                        if (DataStore.serviceState.started) {
                            SagerNet.reloadService() // 或者是你專案內定義的 reload 觸發方式
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

        httpProxyBypass.onPreferenceChangeListener = reloadListener
        dnsHosts.onPreferenceChangeListener = reloadListener
        strictRoute.onPreferenceChangeListener = reloadListener
        httpProxyBypass.setOnBindEditTextListener { editText ->
            editText.inputType = EditorInfo.TYPE_CLASS_TEXT or
                EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editText.minLines = 4
            editText.maxLines = 12
            editText.setHorizontallyScrolling(false)
        }
        // Pre-fill with the stored value (or the default when unset) so opening
        // the dialog and tapping OK doesn't overwrite the list with an empty
        // string. Persist the default once so it survives untouched edits.
        httpProxyBypass.text = DataStore.httpProxyBypass
        httpProxyBypass.onPreferenceChangeListener = reloadListener
        showDirectSpeed.onPreferenceChangeListener = reloadListener
        trafficSniffing.onPreferenceChangeListener = reloadListener
        bypassLan.onPreferenceChangeListener = reloadListener
        bypassLanInCore.onPreferenceChangeListener = reloadListener
        mtu.onPreferenceChangeListener = reloadListener

        val concurrentDial = findPreference<SwitchPreferenceCompat>(Key.CONCURRENT_DIAL)!!
        concurrentDial.onPreferenceChangeListener = reloadListener

        enableFakeDns.onPreferenceChangeListener = reloadListener
        dnsHosts.setOnBindEditTextListener { editText ->
            editText.inputType = EditorInfo.TYPE_CLASS_TEXT or
                EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
                EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            editText.minLines = 4
            editText.maxLines = 12
            editText.setHorizontallyScrolling(false)
        }
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
        dnsHosts.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            // Tabs are valid separators in pasted hosts entries; convert them to
            // spaces first so the control-character sanitization does not merge
            // the domain and address tokens together.
            dnsReloadListener(dnsHosts, newValue) { it.replace('\t', ' ') }
        }
        remoteDns.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            dnsReloadListener(remoteDns, newValue)
        }
        directDns.onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, newValue ->
            dnsReloadListener(directDns, newValue)
        }
        enableDnsRouting.onPreferenceChangeListener = reloadListener

        ipv6Mode.onPreferenceChangeListener = reloadListener

        // LAN 分享是 root 改 iptables + ip rule，原本只在連線（BaseService.lateInit）時套用、
        // 只在隧道關閉時拆除，而且這個開關完全沒掛 listener：開 ON 沒反應，關 OFF 後防火牆規則
        // 還留著（熱點上的人繼續走隧道）。這裡直接對稱起／拆，避免用重啟服務把使用者的連線全部打掉。
        findPreference<SwitchPreferenceCompat>(Key.LAN_SHARING)!!.setOnPreferenceChangeListener { preference, newValue ->
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

        resolveDestination.onPreferenceChangeListener = reloadListener
        tunImplementation.onPreferenceChangeListener = reloadListener
        acquireWakeLock.onPreferenceChangeListener = reloadListener
        hideFromRecentApps.setOnPreferenceChangeListener { _, newValue ->
            (activity as? MainActivity)?.applyHideFromRecentApps(newValue as Boolean)
            true
        }

        enableTLSFragment.onPreferenceChangeListener = reloadListener

        // reset to default settings feature
        val resetSettings = findPreference<Preference>("resetSettings")!!
        resetSettings.setOnPreferenceClickListener {
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
        val clearCache = findPreference<Preference>(Key.CLEAR_CACHE)!!
        clearCache.setOnPreferenceClickListener {
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

        val createClearCacheShortcut = findPreference<Preference>("createClearCacheShortcut")!!
        createClearCacheShortcut.setOnPreferenceClickListener {
            val pinned = requestPinClearCacheShortcut(requireContext())
            Toast.makeText(
                requireContext(),
                if (pinned) R.string.shortcut_pin_requested else R.string.shortcut_pin_not_supported,
                Toast.LENGTH_SHORT,
            ).show()
            true
        }

        // ── VPN Watchdog test button ─────────────────────────────────
        findPreference<Preference>("vpnWatchdogTest")?.setOnPreferenceClickListener {
            if (!DataStore.serviceState.connected) {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.dialog_watchdog_test_title)
                    .setMessage(R.string.dialog_watchdog_vpn_not_running)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                return@setOnPreferenceClickListener true
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_watchdog_auto_reconnect_title)
                .setMessage(R.string.dialog_watchdog_force_restart)
                .setPositiveButton(R.string.dialog_watchdog_start) { _, _ ->
                    io.nekohasekai.sagernet.bg.VpnWatchdog.testModeRequested = true
                    com.google.android.material.snackbar.Snackbar
                        .make(
                            requireView(),
                            R.string.snackbar_watchdog_test_started,
                            com.google.android.material.snackbar.Snackbar.LENGTH_LONG,
                        )
                        .show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            true
        }

        val vpnWatchdogInterval = findPreference<EditTextPreference>(Key.VPN_WATCHDOG_INTERVAL)
        vpnWatchdogInterval?.setOnBindEditTextListener(EditTextPreferenceModifiers.Number)
        vpnWatchdogInterval?.onPreferenceChangeListener = reloadListener
        // ────────────────────────────────────────────────────────────
    }

    private fun recreateActivityAfterPreferencePersisted() {
        listView.postDelayed({
            activity?.let(ActivityCompat::recreate)
        }, 100L)
    }

    override fun onResume() {
        super.onResume()

        if (::isProxyApps.isInitialized) {
            isProxyApps.isChecked = DataStore.proxyApps
        }
        if (::globalCustomConfig.isInitialized) {
            globalCustomConfig.notifyChanged()
        }
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
