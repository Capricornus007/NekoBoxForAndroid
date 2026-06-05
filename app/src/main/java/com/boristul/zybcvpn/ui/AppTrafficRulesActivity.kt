package com.boristul.zybcvpn.ui

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.ImageView
import android.view.View
import android.view.ViewGroup
import android.widget.Filter
import android.widget.Filterable
import androidx.annotation.UiThread
import androidx.core.view.ViewCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.simplecityapps.recyclerview_fastscroll.views.FastScrollRecyclerView
import com.boristul.zybcvpn.BuildConfig
import com.boristul.zybcvpn.R
import com.boristul.zybcvpn.database.AppTrafficRule
import com.boristul.zybcvpn.database.AppTrafficRuleValidator
import com.boristul.zybcvpn.database.DataStore
import com.boristul.zybcvpn.database.TrafficMode
import com.boristul.zybcvpn.database.TrafficRoutingDiagnosticsResolver
import com.boristul.zybcvpn.databinding.LayoutAppTrafficRulesBinding
import com.boristul.zybcvpn.databinding.LayoutAppTrafficRulesItemBinding
import com.boristul.zybcvpn.ktx.crossFadeFrom
import com.boristul.zybcvpn.ktx.Logs
import com.boristul.zybcvpn.utils.PackageCache
import com.boristul.zybcvpn.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

class AppTrafficRulesActivity : ThemedActivity() {

    companion object {
        private val cachedApps
            get() = PackageCache.installedPackages.toMutableMap().apply {
                remove(BuildConfig.APPLICATION_ID)
            }
    }

    private class InstalledApp(
        private val pm: PackageManager,
        private val appInfo: ApplicationInfo,
        val packageName: String,
    ) {
        val name: CharSequence = appInfo.loadLabel(pm)
        val icon: Drawable get() = appInfo.loadIcon(pm)
        val uid get() = appInfo.uid
        val sys get() = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    }

    private inner class AppViewHolder(
        private val binding: LayoutAppTrafficRulesItemBinding,
    ) : RecyclerView.ViewHolder(binding.root), View.OnClickListener {

        private lateinit var item: InstalledApp

        init {
            binding.root.setOnClickListener(this)
        }

        fun bind(app: InstalledApp) {
            item = app
            binding.itemicon.setImageDrawable(app.icon)
            binding.title.text = app.name
            binding.desc.text = "${app.packageName} (${app.uid})"
            val mode = ruleModes[app.packageName]
            binding.modeChip.text = modeLabel(mode)
            val (chipBg, chipStroke, chipText) = when (mode) {
                TrafficMode.REMOTE_VPN -> Triple(R.color.zybc_teal, R.color.zybc_teal, android.R.color.white)
                TrafficMode.DPI_BYPASS -> Triple(R.color.zybc_surface_border, R.color.zybc_teal_soft, android.R.color.white)
                null -> Triple(R.color.zybc_surface_alt, R.color.zybc_surface_border, android.R.color.white)
            }
            binding.modeChip.chipBackgroundColor =
                ColorStateList.valueOf(getColor(chipBg))
            binding.modeChip.chipStrokeColor =
                ColorStateList.valueOf(getColor(chipStroke))
            binding.modeChip.setTextColor(getColor(chipText))
            binding.modeChip.visibility = View.VISIBLE
        }

        override fun onClick(v: View?) {
            showModeDialog(item)
        }
    }

    private inner class AppsAdapter : RecyclerView.Adapter<AppViewHolder>(),
        Filterable,
        FastScrollRecyclerView.SectionedAdapter {

        var filteredApps = apps

        suspend fun reload() {
            PackageCache.reload()
            apps = cachedApps.mapNotNull { (packageName, packageInfo) ->
                coroutineContext[Job]!!.ensureActive()
                packageInfo.applicationInfo?.let { InstalledApp(packageManager, it, packageName) }
            }.sortedWith(appComparator)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            return AppViewHolder(
                LayoutAppTrafficRulesItemBinding.inflate(layoutInflater, parent, false)
            )
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(filteredApps[position])
        }

        override fun getItemCount(): Int = filteredApps.size

        private val filterImpl = object : Filter() {
            override fun performFiltering(constraint: CharSequence) = FilterResults().apply {
                var result = if (constraint.isEmpty()) {
                    apps
                } else {
                    apps.filter {
                        it.name.contains(constraint, true) ||
                            it.packageName.contains(constraint, true) ||
                            it.uid.toString().contains(constraint)
                    }
                }
                if (!showSystemApps) result = result.filter { !it.sys }
                count = result.size
                values = result
            }

            override fun publishResults(constraint: CharSequence, results: FilterResults) {
                @Suppress("UNCHECKED_CAST")
                filteredApps = results.values as List<InstalledApp>
                notifyDataSetChanged()
            }
        }

        override fun getFilter(): Filter = filterImpl

        override fun getSectionName(position: Int): String {
            return filteredApps[position].name.firstOrNull()?.toString() ?: ""
        }
    }

    private val loading by lazy { findViewById<View>(R.id.loading) }
    private lateinit var binding: LayoutAppTrafficRulesBinding
    private var loader: Job? = null
    private var apps = emptyList<InstalledApp>()
    private val appsAdapter = AppsAdapter()
    private val ruleModes = linkedMapOf<String, TrafficMode>()
    private var showSystemApps = false

    private val appComparator = compareBy<InstalledApp>(
        { !ruleModes.containsKey(it.packageName) },
        { it.name.toString() }
    )

    @UiThread
    private fun loadApps() {
        loader?.cancel()
        loader = lifecycleScope.launchWhenCreated {
            loading.crossFadeFrom(binding.list)
            withContext(Dispatchers.IO) { appsAdapter.reload() }
            appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
            if (apps.isEmpty()) {
                binding.appPlaceholder.emptyMessage.text = getString(R.string.app_traffic_rules_empty)
                binding.list.visibility = View.GONE
                binding.appPlaceholder.root.crossFadeFrom(loading)
            } else {
                binding.list.crossFadeFrom(loading)
            }
        }
    }

    private fun modeLabel(mode: TrafficMode?): String {
        return when (mode) {
            TrafficMode.REMOTE_VPN -> getString(R.string.app_traffic_rule_force_remote)
            TrafficMode.DPI_BYPASS -> getString(R.string.app_traffic_rule_force_bypass)
            null -> getString(R.string.app_traffic_rule_default_direct)
        }
    }

    private fun persistRules() {
        val rules = ruleModes.map { (packageName, trafficMode) ->
            AppTrafficRule(packageName, trafficMode)
        }
        val validation = AppTrafficRuleValidator.validateRules(rules)
        AppTrafficRuleValidator.logReport("uiPersist", validation)
        DataStore.appTrafficRules = validation.normalizedRules
        apps = apps.sortedWith(appComparator)
        appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
    }

    private fun updateShowSystemAppsState() {
        val check = binding.showSystemAppsCheck
        val card: MaterialCardView = binding.showSystemAppsCard
        check.visibility = if (showSystemApps) View.VISIBLE else View.GONE
        card.setCardBackgroundColor(
            getColor(if (showSystemApps) R.color.zybc_surface else R.color.zybc_surface_alt)
        )
        card.strokeColor = getColor(
            if (showSystemApps) R.color.zybc_teal else R.color.zybc_surface_border
        )
    }

    private fun showValidationDiagnostics() {
        val report = DataStore.appTrafficRulesValidationReport
        AppTrafficRuleValidator.logReport("uiLoad", report)
        val routingDiagnostics = TrafficRoutingDiagnosticsResolver.snapshot(ruleReport = report)
        TrafficRoutingDiagnosticsResolver.logSnapshot("uiLoad", routingDiagnostics)
        if (!report.hasIssues && routingDiagnostics.ineffectivePackages.isEmpty()) return

        val parts = mutableListOf<String>()
        if (report.hasIssues) {
            parts += getString(
                R.string.app_traffic_rules_validation_summary,
                report.duplicatePackages.size,
                report.invalidPackages.size + report.invalidModes.size,
                report.missingPackages.size,
            )
        }
        if (routingDiagnostics.ineffectivePackages.isNotEmpty()) {
            parts += getString(
                R.string.app_traffic_rules_conflict_summary,
                routingDiagnostics.ineffectivePackages.size
            )
        }
        val message = parts.joinToString(" ")
        Logs.w("Traffic rules[uiLoad]: userVisible=$message")
        snackbar(message).show()
    }

    private fun showModeDialog(app: InstalledApp) {
        val options = arrayOf(
            getString(R.string.traffic_mode_remote_vpn_choice),
            getString(R.string.traffic_mode_dpi_bypass_choice),
            getString(R.string.traffic_mode_direct_choice)
        )
        val currentSelection = when (ruleModes[app.packageName]) {
            TrafficMode.REMOTE_VPN -> 0
            TrafficMode.DPI_BYPASS -> 1
            null -> 2
        }
        var pendingSelection = currentSelection

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.app_traffic_rule_for, app.name))
            .setSingleChoiceItems(options, currentSelection) { _, which ->
                pendingSelection = which
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                when (pendingSelection) {
                    0 -> ruleModes[app.packageName] = TrafficMode.REMOTE_VPN
                    1 -> ruleModes[app.packageName] = TrafficMode.DPI_BYPASS
                    else -> ruleModes.remove(app.packageName)
                }
                persistRules()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = LayoutAppTrafficRulesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.appPlaceholder.emptyMessage.setText(R.string.app_list_permission_denied)
        binding.appPlaceholder.openSettings.setOnClickListener {
            val intent =
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.fromParts("package", packageName, null)
                }
            startActivity(intent)
        }

        setSupportActionBar(binding.toolbar)
        applyStatusBarInsetToToolbar(binding.toolbar)
        supportActionBar?.apply {
            setTitle(R.string.app_traffic_rules)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        DataStore.appTrafficRules.forEach { ruleModes[it.packageName] = it.trafficMode }
        showValidationDiagnostics()

        binding.list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        binding.list.itemAnimator = DefaultItemAnimator()
        binding.list.adapter = appsAdapter

        ViewCompat.setOnApplyWindowInsetsListener(binding.root, ListListener)

        binding.search.addTextChangedListener {
            appsAdapter.filter.filter(it?.toString() ?: "")
        }

        updateShowSystemAppsState()
        binding.showSystemApps.setOnClickListener {
            showSystemApps = !showSystemApps
            updateShowSystemAppsState()
            appsAdapter.filter.filter(binding.search.text?.toString() ?: "")
        }

        loadApps()
    }
}
