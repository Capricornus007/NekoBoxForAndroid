package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.proto.TailscaleAccess
import io.nekohasekai.sagernet.bg.proto.TailscalePeer
import io.nekohasekai.sagernet.bg.proto.TailscalePeersInstance
import io.nekohasekai.sagernet.bg.proto.parseTailscalePeers
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.tailscale.tailscaleStateDirectory
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import java.io.File

class TailscaleSettingsActivity :
    ProfileSettingsActivity<TailscaleBean>(),
    SagerConnection.Callback {

    override fun createEntity() = TailscaleBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val authKey = pbm.add(PreferenceBinding(Type.Text, "authKey"))
    private val controlUrl = pbm.add(PreferenceBinding(Type.Text, "controlUrl"))
    private val hostname = pbm.add(PreferenceBinding(Type.Text, "hostname"))
    private val exitNode = pbm.add(PreferenceBinding(Type.Text, "exitNode"))
    private val exitNodeAllowLanAccess = pbm.add(PreferenceBinding(Type.Bool, "exitNodeAllowLanAccess"))
    private val acceptRoutes = pbm.add(PreferenceBinding(Type.Bool, "acceptRoutes"))
    private val onlyTcp443 = pbm.add(PreferenceBinding(Type.Bool, "onlyTcp443"))

    // Peers come from the running service when it drives this profile (a node has one identity),
    // so the screen keeps a service connection like the share screen does.
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_TAILSCALE_SETTINGS)

    @Volatile
    private var pickingExitNode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connection.connect(this, this)
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onServiceConnected(service: ISagerNetService) {}

    override fun TailscaleBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun TailscaleBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.tailscale_preferences)
        pbm.setPreferenceFragment(this)

        (authKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
        findPreference<Preference>("exitNodePicker")!!.setOnPreferenceClickListener {
            pickExitNode()
            true
        }
        findPreference<Preference>("resetIdentity")!!.apply {
            // The node identity lives under the profile id, so a profile that has not
            // been saved yet has nothing to reset.
            isVisible = DataStore.editingId != 0L
            setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.tailscale_reset_identity)
                    .setMessage(R.string.tailscale_reset_identity_sum)
                    .setPositiveButton(R.string.yes) { _, _ -> resetIdentity() }
                    .setNegativeButton(R.string.no, null)
                    .show()
                true
            }
        }
    }

    private fun pickExitNode() {
        val profileId = DataStore.editingId
        if (profileId == 0L) {
            Toast.makeText(this, R.string.tailscale_save_first, Toast.LENGTH_SHORT).show()
            return
        }
        if (pickingExitNode) return
        pickingExitNode = true
        Toast.makeText(this, R.string.tailscale_exit_node_pick_loading, Toast.LENGTH_SHORT).show()
        // Scoped to the editor: leaving it cancels the query, and a probe node closes once its
        // readiness wait returns.
        lifecycleScope.launch(Dispatchers.IO) {
            val peers = try {
                loadPeers(profileId)
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(
                        this@TailscaleSettingsActivity,
                        e.readableMessage,
                        Toast.LENGTH_LONG,
                    ).show()
                }
                return@launch
            } finally {
                pickingExitNode = false
            }
            onMainDispatcher { showExitNodes(peers.filter { it.exitNode }) }
        }
    }

    // Through the service when it drives this node, otherwise a short-lived node on the saved
    // profile; refused while the service runs any node the probe would start (a group front or
    // landing node included).
    private suspend fun loadPeers(profileId: Long): List<TailscalePeer> {
        val entity = proxyEntity ?: error(getString(R.string.tailscale_save_first))
        val nodes = buildConfig(entity, forTest = true).tailscaleEndpoints.keys
        return TailscaleAccess.run(
            { connection.service },
            nodes,
            { parseTailscalePeers(it.tailscalePeers(profileId)) },
        ) {
            TailscaleAccess.probeLock.withLock { TailscalePeersInstance(entity).listPeers() }
        }
    }

    private fun showExitNodes(peers: List<TailscalePeer>) {
        if (isFinishing || isDestroyed) return
        if (peers.isEmpty()) {
            Toast.makeText(this, R.string.tailscale_exit_node_pick_none, Toast.LENGTH_LONG).show()
            return
        }
        val labels = peers.map { peer ->
            val address = peer.ips.firstOrNull { !it.contains(':') } ?: peer.ips.firstOrNull() ?: ""
            buildString {
                append(peer.name)
                if (address.isNotEmpty()) append("  ").append(address)
                if (!peer.online) append("  (").append(getString(R.string.tailscale_exit_node_pick_offline)).append(')')
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tailscale_exit_node_pick)
            .setItems(labels.toTypedArray()) { _, index ->
                // The hostname is what the control server shows and what SetExitNodeIP accepts.
                (exitNode.preference as EditTextPreference).text = peers[index].name
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetIdentity() {
        // A running core may hold the directory open and write the current identity back;
        // the profile may also be in use as a chain hop or rule outbound, so check the service, not the selection.
        if (DataStore.serviceState.started) {
            Toast.makeText(this, R.string.tailscale_reset_identity_running, Toast.LENGTH_SHORT).show()
            return
        }
        val profileId = DataStore.editingId
        runOnIoDispatcher {
            // The core runs with no_backup as its working directory (libcore InitCore).
            val removed = File(
                SagerNet.application.noBackupFilesDir,
                tailscaleStateDirectory(profileId),
            ).deleteRecursively()
            onMainDispatcher {
                val message = if (removed) R.string.tailscale_reset_identity_done else R.string.tailscale_reset_identity_failed
                Toast.makeText(this@TailscaleSettingsActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
