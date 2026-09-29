package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.RoutingProfiles
import io.nekohasekai.sagernet.databinding.LayoutRoutingProfilesBinding
import io.nekohasekai.sagernet.databinding.LayoutTwoLineItemBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class RoutingProfilesActivity : ThemedActivity() {

    private lateinit var binding: LayoutRoutingProfilesBinding
    private val adapter = ProfileAdapter()
    private var pendingExport: RoutingProfiles.Profile? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutRoutingProfilesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.routing_profiles)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        binding.recyclerView.adapter = adapter
        adapter.reload()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.routing_profiles_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_save_current -> askName(getString(R.string.routing_profile_save_current), "") { name ->
                runOnDefaultDispatcher {
                    RoutingProfiles.saveLiveAs(name)
                    onMainDispatcher { adapter.reload() }
                }
            }

            R.id.action_import_file -> importFile.launch("*/*")

            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        runOnDefaultDispatcher {
            val imported = try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    ?.let(RoutingProfiles::import)
            } catch (e: Exception) {
                Logs.w(e)
                null
            }
            onMainDispatcher {
                if (imported == null) {
                    snackbar(R.string.routing_profile_import_invalid).show()
                } else {
                    adapter.reload()
                }
            }
        }
    }

    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val profile = pendingExport ?: return@registerForActivityResult
        pendingExport = null
        uri ?: return@registerForActivityResult
        runOnDefaultDispatcher {
            try {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use {
                    it.write(profile.toExportJson().toString(2))
                }
            } catch (e: Exception) {
                onMainDispatcher { snackbar(e.readableMessage).show() }
            }
        }
    }

    private fun askName(title: String, current: String, onName: (String) -> Unit) {
        val input = EditText(this).apply { setText(current) }
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onName)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun switchTo(profile: RoutingProfiles.Profile) {
        runOnDefaultDispatcher {
            RoutingProfiles.switchTo(profile.id)
            onMainDispatcher {
                adapter.reload()
                if (DataStore.serviceState.started) {
                    snackbar(R.string.need_reload).setAction(R.string.apply) { SagerNet.reloadService() }.show()
                }
            }
        }
    }

    private fun showMenu(anchor: android.view.View, profile: RoutingProfiles.Profile) {
        PopupMenu(this, anchor).apply {
            menuInflater.inflate(R.menu.routing_profile_item_menu, menu)
            menu.findItem(R.id.action_switch).isVisible = profile.id != RoutingProfiles.activeId
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_switch -> switchTo(profile)

                    R.id.action_rename -> askName(getString(R.string.routing_profile_rename), profile.name) { name ->
                        RoutingProfiles.rename(profile.id, name)
                        adapter.reload()
                    }

                    R.id.action_export -> {
                        pendingExport = profile
                        exportFile.launch("routing-${profile.name}.json")
                    }

                    R.id.action_delete -> MaterialAlertDialogBuilder(this@RoutingProfilesActivity)
                        .setTitle(R.string.confirm)
                        .setMessage(getString(R.string.routing_profile_delete_message, profile.name))
                        .setPositiveButton(R.string.yes) { _, _ ->
                            RoutingProfiles.delete(profile.id)
                            adapter.reload()
                        }
                        .setNegativeButton(R.string.no, null)
                        .show()
                }
                true
            }
            show()
        }
    }

    inner class ProfileAdapter : RecyclerView.Adapter<ProfileHolder>() {
        private val profiles = ArrayList<RoutingProfiles.Profile>()

        fun reload() {
            profiles.clear()
            profiles.addAll(RoutingProfiles.list())
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ProfileHolder(LayoutTwoLineItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount() = profiles.size

        override fun onBindViewHolder(holder: ProfileHolder, position: Int) = holder.bind(profiles[position])
    }

    inner class ProfileHolder(private val binding: LayoutTwoLineItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(profile: RoutingProfiles.Profile) {
            binding.title.text = profile.name
            val rules = resources.getQuantityString(R.plurals.routing_profile_rules, profile.ruleCount, profile.ruleCount)
            binding.summary.text = if (profile.id == RoutingProfiles.activeId) {
                getString(R.string.routing_profile_active, rules)
            } else {
                rules
            }
            binding.root.setOnClickListener { showMenu(binding.more, profile) }
            binding.more.setOnClickListener { showMenu(it, profile) }
        }
    }
}
