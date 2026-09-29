package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutConnectionsBinding
import io.nekohasekai.sagernet.databinding.LayoutTwoLineItemBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One routed connection as reported by libcore's tracker (see libcore/connections.go). */
data class ConnectionEntry(
    val id: Long,
    val start: Long,
    val end: Long,
    val network: String,
    val destination: String,
    val domain: String,
    val addresses: List<String>,
    val fakeIp: Boolean,
    val packageName: String,
    val uid: Int,
    val rule: String,
    val outbound: String,
    val closeReason: String,
    val upload: Long,
    val download: Long,
) {
    val closed get() = end != 0L

    /** Destination IP without the port, or null when the destination is a domain or fake IP. */
    val ip: String?
        get() = destination.substringBeforeLast(':').removeSurrounding("[", "]")
            .takeIf { domain.isEmpty() && !fakeIp && it.any(Char::isDigit) }

    companion object {
        fun parse(json: String): List<ConnectionEntry> {
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }

        private fun fromJson(json: JSONObject) = ConnectionEntry(
            id = json.getLong("id"),
            start = json.getLong("start"),
            end = json.optLong("end", 0L),
            network = json.optString("network"),
            destination = json.optString("destination"),
            domain = json.optString("domain"),
            addresses = json.optJSONArray("addresses")?.let { a -> (0 until a.length()).map(a::getString) } ?: emptyList(),
            fakeIp = json.optBoolean("fakeip", false),
            packageName = json.optString("package"),
            uid = json.optInt("uid", 0),
            rule = json.optString("rule"),
            outbound = json.optString("outbound"),
            closeReason = json.optString("closeReason"),
            upload = json.optLong("upload", 0L),
            download = json.optLong("download", 0L),
        )
    }
}

class ConnectionsFragment :
    ToolbarFragment(R.layout.layout_connections),
    Toolbar.OnMenuItemClickListener {

    private lateinit var binding: LayoutConnectionsBinding
    private val adapter = ConnectionAdapter()
    private var showClosed = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding = LayoutConnectionsBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_connections)
        toolbar.inflateMenu(R.menu.connections_menu)
        toolbar.menu.findItem(R.id.action_diagnostics).isChecked = DataStore.connectionDiagnostics
        toolbar.setOnMenuItemClickListener(this)

        binding.connectionList.layoutManager = FixedLinearLayoutManager(binding.connectionList)
        binding.connectionList.adapter = adapter

        // ponytail: fixed 1.5 s poll while visible; a push channel is not worth an AIDL callback.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    refresh()
                    delay(1500)
                }
            }
        }
    }

    private suspend fun refresh() {
        val activity = activity as? MainActivity ?: return
        val service = activity.connection.service?.takeIf { DataStore.serviceState.connected }
        if (service == null) {
            binding.connectionsStatus.setText(R.string.connections_status_stopped)
            adapter.submitList(emptyList())
            return
        }
        val entries = withContext(Dispatchers.IO) {
            try {
                ConnectionEntry.parse(service.connections(showClosed))
            } catch (e: Exception) {
                Logs.w(e)
                emptyList()
            }
        }.sortedByDescending { it.start }
        binding.connectionsStatus.text = getString(
            R.string.connections_status_live,
            entries.count { !it.closed },
            entries.count { it.closed },
            getString(if (DataStore.connectionDiagnostics) R.string.connections_status_on else R.string.connections_status_off),
        )
        adapter.submitList(entries)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_show_closed -> {
                item.isChecked = !item.isChecked
                showClosed = item.isChecked
            }

            R.id.action_diagnostics -> {
                item.isChecked = !item.isChecked
                DataStore.connectionDiagnostics = item.isChecked
                needReload()
            }

            else -> return false
        }
        return true
    }

    private fun label(entry: ConnectionEntry): String = when {
        entry.packageName.isNotEmpty() -> PackageCache.loadLabel(entry.packageName)
        entry.uid > 0 -> "uid ${entry.uid}"
        else -> "?"
    }

    private fun traffic(entry: ConnectionEntry) = "↑ ${Formatter.formatFileSize(requireContext(), entry.upload)} ↓ ${Formatter.formatFileSize(requireContext(), entry.download)}"

    private fun showDetails(anchor: View, entry: ConnectionEntry) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(entry.domain.ifEmpty { entry.destination })
            .setMessage(
                getString(
                    R.string.connections_detail,
                    label(entry),
                    entry.destination,
                    entry.addresses.joinToString(", ").ifEmpty { "-" },
                    entry.rule.ifEmpty { "-" },
                    entry.outbound,
                    traffic(entry),
                ),
            )
            .setPositiveButton(R.string.route_add) { _, _ -> showRuleMenu(anchor, entry) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRuleMenu(anchor: View, entry: ConnectionEntry) {
        PopupMenu(requireContext(), anchor).apply {
            menuInflater.inflate(R.menu.connection_item_menu, menu)
            menu.findItem(R.id.action_rule_domain).isVisible = entry.domain.isNotEmpty()
            menu.findItem(R.id.action_rule_ip).isVisible = entry.ip != null
            menu.findItem(R.id.action_rule_app).isVisible = entry.packageName.isNotEmpty()
            setOnMenuItemClickListener { item ->
                val intent = Intent(requireContext(), RouteSettingsActivity::class.java)
                when (item.itemId) {
                    R.id.action_rule_domain -> intent.putExtra(RouteSettingsActivity.EXTRA_DOMAIN, entry.domain)
                    R.id.action_rule_ip -> intent.putExtra(RouteSettingsActivity.EXTRA_IP, entry.ip)
                    R.id.action_rule_app -> intent.putExtra(RouteSettingsActivity.EXTRA_PACKAGE_NAME, entry.packageName)
                    else -> return@setOnMenuItemClickListener false
                }
                startActivity(intent)
                true
            }
            show()
        }
    }

    inner class ConnectionAdapter :
        ListAdapter<ConnectionEntry, ConnectionHolder>(
            object : DiffUtil.ItemCallback<ConnectionEntry>() {
                override fun areItemsTheSame(oldItem: ConnectionEntry, newItem: ConnectionEntry) = oldItem.id == newItem.id

                override fun areContentsTheSame(oldItem: ConnectionEntry, newItem: ConnectionEntry) = oldItem == newItem
            },
        ) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ConnectionHolder(LayoutTwoLineItemBinding.inflate(layoutInflater, parent, false))

        override fun onBindViewHolder(holder: ConnectionHolder, position: Int) = holder.bind(getItem(position))
    }

    inner class ConnectionHolder(private val binding: LayoutTwoLineItemBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(entry: ConnectionEntry) {
            binding.title.text = entry.domain.ifEmpty { entry.destination }
            binding.summary.text = listOf(
                label(entry),
                entry.network,
                entry.outbound,
                traffic(entry),
                if (entry.closed) getString(R.string.connections_closed, entry.closeReason) else "",
            ).filter { it.isNotEmpty() }.joinToString(" · ")
            binding.root.alpha = if (entry.closed) 0.6f else 1f
            binding.root.setOnClickListener { showDetails(binding.more, entry) }
            binding.more.setOnClickListener { showRuleMenu(it, entry) }
        }
    }
}
