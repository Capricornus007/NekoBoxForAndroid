package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutPackagesRegexDialogBinding
import io.nekohasekai.sagernet.databinding.LayoutPackagesRegexItemBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.RulePackages
import io.nekohasekai.sagernet.widget.AppListPreference
import io.nekohasekai.sagernet.widget.ListListener
import io.nekohasekai.sagernet.widget.OutboundPreference
import io.nekohasekai.sagernet.widget.PackageRegexPreference
import kotlinx.parcelize.Parcelize
import moe.matsuri.nb4a.ui.EditConfigPreference

@Suppress("UNCHECKED_CAST")
class RouteSettingsActivity(
    @LayoutRes resId: Int = R.layout.layout_settings_activity,
) : ThemedActivity(resId),
    OnPreferenceDataStoreChangeListener {

    fun init(packageName: String?) {
        RuleEntity().apply {
            if (!packageName.isNullOrBlank()) {
                packages = setOf(packageName)
                name = app.getString(R.string.route_for, PackageCache.loadLabel(packageName))
            }
        }.init()
    }

    fun RuleEntity.init() {
        DataStore.routeName = name
        DataStore.serverConfig = config
        DataStore.routeDomain = domains
        DataStore.routeIP = ip
        DataStore.routePort = port
        DataStore.routeSourcePort = sourcePort
        DataStore.routeNetwork = network
        DataStore.routeSource = source
        DataStore.routeProtocol = protocol
        DataStore.routeRuleset = ruleset
        DataStore.routeFallbackMode = fallbackMode
        DataStore.routeFallbackProfile = fallbackProfile
        DataStore.routeOutboundRule = outbound
        DataStore.routeOutbound = when (outbound) {
            0L -> 0
            -1L -> 1
            -2L -> 2
            else -> OutboundPreference.VALUE_SELECT_PROFILE.toInt()
        }
        DataStore.routePackages = packages.joinToString("\n")
    }

    fun RuleEntity.serialize() {
        name = DataStore.routeName
        config = DataStore.serverConfig
        domains = DataStore.routeDomain
        ip = DataStore.routeIP
        port = DataStore.routePort
        sourcePort = DataStore.routeSourcePort
        network = DataStore.routeNetwork
        source = DataStore.routeSource
        protocol = DataStore.routeProtocol
        ruleset = DataStore.routeRuleset
        outbound = when (DataStore.routeOutbound) {
            0 -> 0L
            1 -> -1L
            2 -> -2L
            else -> DataStore.routeOutboundRule
        }
        fallbackMode = DataStore.routeFallbackMode
        fallbackProfile = DataStore.routeFallbackProfile
        packages = DataStore.routePackages.split("\n").filter { it.isNotBlank() }.toSet()

        if (DataStore.editingId == 0L) {
            enabled = true
        }
    }

    private lateinit var editConfigPreference: EditConfigPreference

    fun needSave(): Boolean = DataStore.dirty

    fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.route_preferences)

        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!
    }

    override fun onResume() {
        super.onResume()

        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
        }
    }

    val selectProfileForAdd = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { (resultCode, data) ->
        if (resultCode == Activity.RESULT_OK) {
            runOnDefaultDispatcher {
                val profile = ProfileManager.getProfile(
                    data!!.getLongExtra(
                        ProfileSelectActivity.EXTRA_PROFILE_ID,
                        0,
                    ),
                ) ?: return@runOnDefaultDispatcher
                DataStore.routeOutboundRule = profile.id
                onMainDispatcher {
                    outbound.value = OutboundPreference.VALUE_SELECT_PROFILE
                }
            }
        }
    }

    private var backupPreference: Preference? = null
    private val selectFallbackProfile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            if (id > 0) {
                DataStore.routeFallbackProfile = id
                setFallbackSummary(id)
            }
        }
    }

    // getProfile 是阻塞 DAO，回呼／監聽器都跑在主線：名字交背景取、只把字串貼回 summary
    private fun setFallbackSummary(id: Long) {
        runOnDefaultDispatcher {
            val name = ProfileManager.getProfile(id)?.displayName()
                ?: getString(androidx.preference.R.string.not_set)
            onMainDispatcher { backupPreference?.summary = name }
        }
    }

    val selectAppList = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { (_, _) ->
        apps.postUpdate()
        // App 清單那一頁也可能透過剪貼簿匯動到正則條目，摘要一起刷新，別讓兩列不同步。
        packagesRegex.postUpdate()
    }

    lateinit var outbound: OutboundPreference
    lateinit var apps: AppListPreference
    lateinit var packagesRegex: PackageRegexPreference

    fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        outbound = findPreference(Key.ROUTE_OUTBOUND)!!
        apps = findPreference(Key.ROUTE_PACKAGES)!!
        packagesRegex = findPreference(Key.ROUTE_PACKAGES_REGEX_LIST)!!

        val fallback = findPreference<Preference>("routeFallbackMode")!!
        backupPreference = findPreference("routeFallbackProfile")
        fun updateFallback(mode: Int = DataStore.routeFallbackMode, out: Int = DataStore.routeOutbound) {
            fallback.isVisible = out == 1
            backupPreference?.isVisible = out == 1 && mode == 3
            setFallbackSummary(DataStore.routeFallbackProfile)
        }
        updateFallback()
        fallback.setOnPreferenceChangeListener { _, value ->
            updateFallback(value.toString().toInt())
            true
        }
        backupPreference?.setOnPreferenceClickListener {
            selectFallbackProfile.launch(Intent(this@RouteSettingsActivity, ProfileSelectActivity::class.java))
            true
        }

        outbound.setOnPreferenceChangeListener { _, newValue ->
            if (newValue.toString() == OutboundPreference.VALUE_SELECT_PROFILE) {
                // 選剖面頁要帶「當前已選」的 entity：getProfile 阻塞、這裡是主線，交背景取好再開頁
                val selectedId = DataStore.routeOutboundRule
                runOnDefaultDispatcher {
                    val selected = ProfileManager.getProfile(selectedId)
                    onMainDispatcher {
                        selectProfileForAdd.launch(
                            Intent(
                                this@RouteSettingsActivity,
                                ProfileSelectActivity::class.java,
                            ).apply {
                                selected?.let { putExtra(ProfileSelectActivity.EXTRA_SELECTED, it) }
                            },
                        )
                    }
                }
                false
            } else {
                updateFallback(out = newValue.toString().toInt())
                true
            }
        }

        apps.setOnPreferenceClickListener {
            selectAppList.launch(
                Intent(
                    this@RouteSettingsActivity,
                    AppListActivity::class.java,
                ),
            )
            true
        }

        packagesRegex.setOnPreferenceClickListener {
            showPackagesRegexDialog()
            true
        }
    }

    /**
     * 「用正則表達式比對套件名」的編輯框：上面一個輸入欄加一顆新增按鈕，下面列出現有的條目、
     * 每條都能刪。條目跟一般套件名同住 routePackages 這串清單，靠 `re:` 前綴辨認，所以不需要
     * 新的資料庫欄位。寫入會經過 profileCacheStore 的變更通知，頁面照樣標成「有未存檔的修改」。
     *
     * 非法正則在這裡就擋掉（空字串、含逗號、核心不支援的語法、編譯不過），不寫進清單；留著它
     * 進配置只會讓核心載入整份配置失敗。
     */
    private fun showPackagesRegexDialog() {
        val dialogBinding = LayoutPackagesRegexDialogBinding.inflate(layoutInflater)

        fun currentRegex(): List<String> = RulePackages.regexLines(DataStore.routePackages)

        fun persist(regex: List<String>) {
            DataStore.routePackages = RulePackages.join(
                RulePackages.plainLines(DataStore.routePackages),
                regex,
            )
            apps.postUpdate()
            packagesRegex.postUpdate()
        }

        fun render() {
            dialogBinding.regexListContainer.removeAllViews()
            for (entry in currentRegex()) {
                val rowBinding = LayoutPackagesRegexItemBinding.inflate(
                    layoutInflater,
                    dialogBinding.regexListContainer,
                    false,
                )
                rowBinding.regexPatternText.text = getString(
                    R.string.rule_packages_regex_item,
                    RulePackages.patternOf(entry),
                )
                rowBinding.regexDeleteButton.setOnClickListener {
                    persist(currentRegex().filterNot { it == entry })
                    render()
                }
                dialogBinding.regexListContainer.addView(rowBinding.root)
            }
        }

        fun showError(messageRes: Int) {
            dialogBinding.regexPatternLayout.error = getString(messageRes)
        }

        dialogBinding.regexAddButton.setOnClickListener {
            val pattern = dialogBinding.regexPatternField.text?.toString()?.trim().orEmpty()
            val error = RulePackages.checkPattern(pattern)
            if (error != RulePackages.PatternError.NONE) {
                showError(
                    when (error) {
                        RulePackages.PatternError.BLANK -> R.string.route_rule_packages_regex_error_blank
                        RulePackages.PatternError.SEPARATOR -> R.string.route_rule_packages_regex_error_separator
                        RulePackages.PatternError.UNSUPPORTED -> R.string.route_rule_packages_regex_error_unsupported
                        else -> R.string.route_rule_packages_regex_error_invalid
                    },
                )
                return@setOnClickListener
            }
            if (currentRegex().any { RulePackages.patternOf(it) == pattern }) {
                showError(R.string.route_rule_packages_regex_error_duplicate)
                return@setOnClickListener
            }
            persist(currentRegex() + RulePackages.regexEntry(pattern))
            dialogBinding.regexPatternField.setText("")
            dialogBinding.regexPatternLayout.error = null
            render()
        }

        render()
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.route_rule_packages_regex)
            .setMessage(R.string.route_rule_packages_regex_dialog_message)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    fun displayPreferenceDialog(preference: Preference): Boolean = false

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as RouteSettingsActivity).saveAndExit()
                }
            }
            setNegativeButton(R.string.no) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(android.R.string.cancel, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val ruleId: Long) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_route_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                runOnDefaultDispatcher {
                    ProfileManager.deleteRule(arg.ruleId)
                }
                requireActivity().finish()
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_ROUTE_ID = "id"
        const val EXTRA_PACKAGE_NAME = "pkg"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this) {
            if (needSave()) {
                UnsavedChangesDialogFragment().apply { key() }.show(supportFragmentManager, null)
            } else {
                finish()
            }
        }
        // ViewBinding intentionally not used here: this activity sets its content via the
        // ThemedActivity(@LayoutRes) constructor (contentLayoutId), not by inflating a binding,
        // so the toolbar is resolved with findViewById.
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.cag_route)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_ROUTE_ID, 0L)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                if (editingId == 0L) {
                    init(intent.getStringExtra(EXTRA_PACKAGE_NAME))
                } else {
                    val ruleEntity = SagerDatabase.rulesDao.getById(editingId)
                    if (ruleEntity == null) {
                        onMainDispatcher {
                            finish()
                        }
                        return@runOnDefaultDispatcher
                    }
                    ruleEntity.init()
                }

                onMainDispatcher {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.settings, MyPreferenceFragmentCompat())
                        .commit()

                    DataStore.dirty = false
                    DataStore.profileCacheStore.registerChangeListener(this@RouteSettingsActivity)
                }
            }
        }
    }

    suspend fun saveAndExit() {
        if (!needSave()) {
            onMainDispatcher {
                MaterialAlertDialogBuilder(this@RouteSettingsActivity).setTitle(R.string.empty_route)
                    .setMessage(R.string.empty_route_notice)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return
        }

        if (DataStore.routeOutbound == 1 && DataStore.routeFallbackMode == 3 &&
            ProfileManager.getProfile(DataStore.routeFallbackProfile) == null
        ) {
            onMainDispatcher { Toast.makeText(this@RouteSettingsActivity, R.string.route_fallback_missing, Toast.LENGTH_LONG).show() }
            return
        }
        io.nekohasekai.sagernet.database.NetworkSettingsHistory.capture()
        val editingId = DataStore.editingId
        if (editingId == 0L) {
            if (intent.hasExtra(EXTRA_PACKAGE_NAME)) {
                setResult(RESULT_OK, Intent())
            }

            ProfileManager.createRule(RuleEntity().apply { serialize() })
        } else {
            val entity = SagerDatabase.rulesDao.getById(DataStore.editingId)
            if (entity == null) {
                finish()
                return
            }
            ProfileManager.updateRule(entity.apply { serialize() })
        }
        finish()
    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    override fun onSupportNavigateUp(): Boolean {
        // Route the action-bar up button through the back dispatcher so it honors the
        // unsaved-changes guard instead of finishing directly (avoids data loss).
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: RouteSettingsActivity? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as RouteSettingsActivity).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    getString(R.string.toast_create_preferences_failed),
                    Toast.LENGTH_SHORT,
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)

            activity?.apply {
                viewCreated(view, savedInstanceState)
            }
        }

        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                if (DataStore.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(ProfileIdArg(DataStore.editingId))
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            else -> false
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity?.apply {
                if (displayPreferenceDialog(preference)) return
            }
            super.onDisplayPreferenceDialog(preference)
        }
    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }
    }
}
