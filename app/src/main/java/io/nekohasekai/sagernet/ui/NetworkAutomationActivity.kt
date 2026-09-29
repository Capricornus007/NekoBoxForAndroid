package io.nekohasekai.sagernet.ui

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.EditText
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.NetworkAutomation
import io.nekohasekai.sagernet.bg.NetworkAutomation.Action
import io.nekohasekai.sagernet.bg.NetworkAutomation.Kind
import io.nekohasekai.sagernet.bg.NetworkAutomation.Rule
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutNetworkAutomationBinding
import io.nekohasekai.sagernet.databinding.LayoutTwoLineItemBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher

class NetworkAutomationActivity : ThemedActivity() {

    private lateinit var binding: LayoutNetworkAutomationBinding
    private val adapter = RuleAdapter()

    // The rule under construction while the profile picker is open.
    private var draft: Rule? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutNetworkAutomationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.network_automation)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }
        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        binding.recyclerView.adapter = adapter
        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.START) {
            override fun onMove(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder) = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                adapter.removeAt(viewHolder.bindingAdapterPosition)
            }
        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.network_automation_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId != R.id.action_add_rule) return super.onOptionsItemSelected(item)
        askKind()
        return true
    }

    // Rule creation is a chain of small choices: network, optional SSID, action, optional profile.
    private fun askKind() {
        val kinds = arrayOf(Kind.MOBILE, Kind.WIFI, Kind.SSID)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_rule_network)
            .setItems(kinds.map { kindLabel(it) }.toTypedArray()) { _, which ->
                if (kinds[which] == Kind.SSID) askSsid() else askAction(kinds[which], "")
            }
            .show()
    }

    private fun askSsid() {
        val input = EditText(this).apply { hint = getString(R.string.network_rule_ssid_hint) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_rule_ssid)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ssid = input.text.toString().trim()
                if (ssid.isNotEmpty()) askAction(Kind.SSID, ssid)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun askAction(kind: Kind, ssid: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.network_rule_action)
            .setItems(
                arrayOf(
                    getString(R.string.network_rule_connect_current),
                    getString(R.string.network_rule_connect_profile),
                    getString(R.string.network_rule_disconnect),
                ),
            ) { _, which ->
                when (which) {
                    0 -> add(Rule(kind, ssid, Action.CONNECT))

                    1 -> {
                        draft = Rule(kind, ssid, Action.CONNECT)
                        selectProfile.launch(Intent(this, ProfileSelectActivity::class.java))
                    }

                    else -> add(Rule(kind, ssid, Action.DISCONNECT))
                }
            }
            .show()
    }

    private val selectProfile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val rule = draft ?: return@registerForActivityResult
        draft = null
        val profileId = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
        if (result.resultCode == RESULT_OK && profileId > 0L) add(rule.copy(profileId = profileId))
    }

    private val requestLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) snackbar(R.string.network_rule_ssid_permission).show()
    }

    private fun add(rule: Rule) {
        adapter.add(rule)
        if (rule.kind == Kind.SSID && !NetworkAutomation.hasLocationPermission(this)) {
            requestLocation.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun kindLabel(kind: Kind) = getString(
        when (kind) {
            Kind.MOBILE -> R.string.network_rule_mobile
            Kind.WIFI -> R.string.network_rule_wifi
            Kind.SSID -> R.string.network_rule_ssid
        },
    )

    inner class RuleAdapter : RecyclerView.Adapter<RuleHolder>() {
        private val rules = NetworkAutomation.rules().toMutableList()

        fun add(rule: Rule) {
            rules += rule
            NetworkAutomation.saveRules(rules)
            notifyItemInserted(rules.size - 1)
        }

        fun removeAt(index: Int) {
            rules.removeAt(index)
            NetworkAutomation.saveRules(rules)
            notifyItemRemoved(index)
        }

        fun ruleAt(index: Int) = rules.getOrNull(index)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = RuleHolder(LayoutTwoLineItemBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount() = rules.size

        override fun onBindViewHolder(holder: RuleHolder, position: Int) = holder.bind(rules[position])
    }

    inner class RuleHolder(private val binding: LayoutTwoLineItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(rule: Rule) {
            binding.title.text = if (rule.kind == Kind.SSID) getString(R.string.network_rule_wifi_named, rule.ssid) else kindLabel(rule.kind)
            binding.more.setOnClickListener { adapter.removeAt(bindingAdapterPosition) }
            binding.more.setImageResource(R.drawable.ic_baseline_delete_24)
            binding.summary.text = when {
                rule.action == Action.DISCONNECT -> getString(R.string.network_rule_disconnect)

                rule.profileId <= 0L -> getString(R.string.network_rule_connect_current)

                else -> getString(R.string.network_rule_connect_named, "…").also {
                    runOnDefaultDispatcher {
                        val name = ProfileManager.getProfile(rule.profileId)?.displayName() ?: getString(R.string.error_title)
                        onMainDispatcher {
                            // The holder may have been recycled for another rule meanwhile.
                            if (adapter.ruleAt(bindingAdapterPosition) == rule) {
                                binding.summary.text = getString(R.string.network_rule_connect_named, name)
                            }
                        }
                    }
                }
            }
        }
    }
}
