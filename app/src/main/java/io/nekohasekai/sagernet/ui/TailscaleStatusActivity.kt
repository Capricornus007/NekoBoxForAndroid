package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceViewHolder
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import kotlinx.coroutines.launch

class TailscaleStatusActivity : ThemedActivity(R.layout.layout_tailscale_status) {
    companion object {
        const val EXTRA_PROFILE_ID = "profileId"
    }
    private val model by viewModels<TailscaleStatusViewModel>()
    private val browser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setSupportActionBar(findViewById(R.id.toolbar))
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.appbar)) { appbar, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            appbar.updatePadding(top = bars.top, left = bars.left, right = bars.right)
            insets
        }
        supportActionBar?.apply {
            setTitle(R.string.tailscale_status_title)
            setDisplayHomeAsUpEnabled(true)
        }
        model.initialize(intent.getLongExtra(EXTRA_PROFILE_ID, 0L))
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settings, StatusFragment()).commit()
        }
    }

    override fun onStart() {
        super.onStart()
        model.foreground()
    }

    override fun onStop() {
        if (isFinishing) model.session?.close() else model.background(isChangingConfigurations)
        super.onStop()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    internal fun openLogin() {
        val owner = model.session ?: return
        val link = owner.loginLink() ?: return
        confirmTailscaleLogin(this, link) {
            if (isFinishing || isDestroyed || model.session !== owner) return@confirmTailscaleLogin
            val confirmed = owner.openLogin(link) ?: return@confirmTailscaleLogin
            try {
                browser.launch(Intent(Intent.ACTION_VIEW, confirmed.url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: Exception) {
                owner.browserFailed()
            }
        }
    }

    class StatusFragment : PreferenceFragmentCompat() {
        private val model get() = ViewModelProvider(requireActivity())[TailscaleStatusViewModel::class.java]
        private val rows = linkedMapOf<String, Preference>()
        private val peers = linkedMapOf<String, Preference>()
        private lateinit var peerCategory: PreferenceCategory
        private lateinit var format: TailscaleStatusFormatting

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            format = TailscaleStatusFormatting(requireContext())
            preferenceScreen = preferenceManager.createPreferenceScreen(requireContext())
            fun row(key: String, title: Int, action: (() -> Unit)? = null) {
                val preference = newPreference(key).apply {
                    setTitle(title)
                    isSelectable = action != null
                    if (action != null) {
                        setOnPreferenceClickListener {
                            action()
                            true
                        }
                    }
                }
                rows[key] = preference
                preferenceScreen.addPreference(preference)
            }
            row("state", R.string.tailscale_status_title)
            row("check", R.string.tailscale_status_check) { model.session?.check() }
            rows.getValue("check").setSummary(R.string.tailscale_status_check_summary)
            row("temporary", R.string.tailscale_status_cancel) { model.session?.cancelSession() }
            rows.getValue("temporary").setSummary(R.string.tailscale_status_temporary)
            row("error", R.string.tailscale_status_error)
            row("login", R.string.tailscale_login_open) { (requireActivity() as TailscaleStatusActivity).openLogin() }
            row("loginWarning", R.string.tailscale_status_login_warning)
            row("self", R.string.tailscale_status_self)
            row("currentExit", R.string.tailscale_status_current_exit)
            row("savedExit", R.string.tailscale_status_saved_exit)
            row("exit", R.string.tailscale_status_exit_action) { chooseExit() }
            row("exitResult", R.string.tailscale_exit_node)
            row("ping", R.string.tailscale_status_ping)
            row("cancelRequest", R.string.tailscale_status_cancel_request) { model.session?.cancelRequest() }
            rows.getValue("cancelRequest").setSummary(R.string.tailscale_status_cancel_request_summary)
            peerCategory = PreferenceCategory(requireContext()).apply {
                key = "peers"
                setTitle(R.string.tailscale_status_peers)
                isIconSpaceReserved = false
            }
            preferenceScreen.addPreference(peerCategory)
            val inventory = newPreference("inventory").apply {
                isSelectable = false
                order = 0
            }
            rows["inventory"] = inventory
            peerCategory.addPreference(inventory)
        }

        private class StatusPreference(context: Context) : Preference(context) {
            override fun onBindViewHolder(holder: PreferenceViewHolder) {
                super.onBindViewHolder(holder)
                holder.itemView.minimumHeight = maxOf(
                    holder.itemView.minimumHeight,
                    (48 * context.resources.displayMetrics.density).toInt(),
                )
                (holder.findViewById(android.R.id.summary) as? TextView)?.maxLines = Int.MAX_VALUE
            }
        }

        private fun newPreference(key: String) = StatusPreference(requireContext()).apply {
            this.key = key
            isPersistent = false
            isIconSpaceReserved = false
            isSingleLineTitle = false
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            ViewCompat.setOnApplyWindowInsetsListener(listView) { list, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
                list.updatePadding(left = bars.left, right = bars.right, bottom = bars.bottom)
                insets
            }
            listView.clipToPadding = false
            viewLifecycleOwner.lifecycleScope.launch {
                viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    model.state.collect(::render)
                }
            }
        }

        private fun render(state: TailscaleStatusUiState) {
            val status = state.status
            val node = status?.node
            rows.getValue("state").apply {
                title = model.profileName.ifEmpty { getString(R.string.tailscale_status_title) }
                summary = format.state(state)
            }
            rows.getValue("check").isEnabled = state.canCheck
            rows.getValue("temporary").isVisible = state.temporary
            rows.getValue("error").isVisible = state.failed
            val link = node?.authUrl?.let(TailscaleLoginLink::parse)
            rows.getValue("login").apply {
                isVisible = node?.needsLogin == true || !node?.authUrl.isNullOrEmpty()
                isEnabled = link != null
                summary = if (link == null) {
                    getString(R.string.tailscale_status_login_invalid)
                } else {
                    getString(R.string.tailscale_status_login_origin, link.origin)
                }
            }
            rows.getValue("loginWarning").isVisible = rows.getValue("login").isVisible
            rows.getValue("self").summary = node?.self?.let {
                it.name + "\n" + format.peer(it)
            } ?: getString(R.string.tailscale_status_unknown)
            rows.getValue("currentExit").summary = format.currentExit(node)
            rows.getValue("savedExit").summary = status?.let {
                it.savedExit.ifEmpty { getString(R.string.tailscale_status_none) } +
                    if (node?.savedExitDiffers(it.savedExit) == true) "\n" + getString(R.string.tailscale_status_exit_mismatch) else ""
            } ?: getString(R.string.tailscale_status_unknown)
            rows.getValue("exit").apply {
                isEnabled = state.canOperate
                summary = if (state.pending == "exit") getString(R.string.tailscale_status_exit_pending) else null
            }
            rows.getValue("exitResult").apply {
                isVisible = state.exitOutcome != null
                summary = state.exitOutcome?.let(format::exitOutcome)
            }
            rows.getValue("ping").summary = buildList {
                add(getString(if (state.pending == "ping") R.string.tailscale_status_ping_pending else R.string.tailscale_status_ping_summary))
                addAll(state.samples.map(format::sample))
            }.joinToString("\n")
            rows.getValue("cancelRequest").isVisible = state.pending != null
            val inventory = node?.peers.orEmpty()
            rows.getValue("inventory").title = when {
                node?.peersTruncated == true -> getString(R.string.tailscale_status_inventory, inventory.size, node.totalPeers)
                inventory.isEmpty() -> getString(R.string.tailscale_status_no_peers)
                else -> getString(R.string.tailscale_status_inventory_complete, inventory.size)
            }
            val removed = peers.keys - inventory.map { it.id }.toSet()
            removed.forEach { id -> peerCategory.removePreference(peers.remove(id)!!) }
            inventory.forEachIndexed { index, peer ->
                val row = peers.getOrPut(peer.id) {
                    newPreference("peer:${peer.id}").also { peerCategory.addPreference(it) }
                }
                row.order = index + 1
                row.title = peer.name.ifEmpty { peer.dnsName.ifEmpty { peer.id } }
                row.summary = format.peer(peer)
                row.isEnabled = state.canOperate
                row.setOnPreferenceClickListener {
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(row.title)
                        .setMessage(getString(R.string.tailscale_status_ping_summary) + "\n\n" + format.peer(peer))
                        .setPositiveButton(R.string.tailscale_status_ping) { _, _ -> model.session?.ping(peer.id) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                    true
                }
            }
        }

        private fun chooseExit() {
            val state = model.state.value
            if (!state.canOperate) return
            val status = state.status ?: return
            val exits = status.node?.peers.orEmpty().filter { it.exitNodeOption }
            val labels = listOf(getString(R.string.tailscale_status_none)) + exits.map {
                it.name.ifEmpty { it.id } + " · " + it.ips.joinToString(", ") +
                    if (!it.online) " · " + getString(R.string.tailscale_status_offline) else ""
            }
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.tailscale_status_exit_action)
                .setItems(labels.toTypedArray()) { _, index ->
                    val peerId = if (index == 0) "" else exits[index - 1].id
                    val message = getString(R.string.tailscale_status_exit_confirm, labels[index]) +
                        if (index == 0) "\n\n" + getString(R.string.tailscale_status_none_warning) else ""
                    MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.tailscale_status_exit_action)
                        .setMessage(message)
                        .setPositiveButton(android.R.string.ok) { _, _ -> model.session?.selectExit(peerId, status.savedExit) }
                        .setNegativeButton(android.R.string.cancel, null).show()
                }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }
}
