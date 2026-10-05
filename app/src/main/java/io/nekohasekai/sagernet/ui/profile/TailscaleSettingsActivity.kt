package io.nekohasekai.sagernet.ui.profile

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.bg.proto.TAILSCALE_LOGIN_TIMEOUT_MS
import io.nekohasekai.sagernet.bg.proto.TailscaleAccess
import io.nekohasekai.sagernet.bg.proto.TailscaleLoginDeclined
import io.nekohasekai.sagernet.bg.proto.TailscaleLoginPending
import io.nekohasekai.sagernet.bg.proto.TailscalePeer
import io.nekohasekai.sagernet.bg.proto.TailscalePeersInstance
import io.nekohasekai.sagernet.bg.proto.parseTailscalePeers
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.TailscaleProfileStore
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.tailscale.resetTailscaleIdentity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.TailscaleLoginLink
import io.nekohasekai.sagernet.ui.TailscaleStatusActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val LOGIN_URL = Regex("https?://\\S+")

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

    private var baseline: TailscaleEditorBaseline? = null
    private var refreshingExit = false
    private var restoredEditorDirty = false
    private val saveMutex = Mutex()

    override fun onCreate(savedInstanceState: Bundle?) {
        restoredEditorDirty = savedInstanceState?.getBoolean("tailscaleEditorDirty") == true
        savedInstanceState?.getString("tailscaleIdentity")?.let { identity ->
            baseline = TailscaleEditorBaseline(
                identity,
                savedInstanceState.getString("tailscaleExit").orEmpty(),
                savedInstanceState.getBoolean("tailscaleExitEdited"),
            )
        }
        super.onCreate(savedInstanceState)
        connection.connect(this, this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("tailscaleEditorDirty", DataStore.dirty)
        baseline?.let {
            outState.putString("tailscaleIdentity", it.identity)
            outState.putString("tailscaleExit", it.exit)
            outState.putBoolean("tailscaleExitEdited", it.exitEdited)
        }
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        val id = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        if (id == 0L || baseline == null) return
        lifecycleScope.launch {
            val current = withContext(Dispatchers.IO) {
                runCatching { SagerDatabase.proxyDao.getById(id) }.getOrNull()
            } ?: return@launch
            val old = baseline ?: return@launch
            val refreshed = old.refreshed(current.uuid, current.tailscaleBean?.exitNode.orEmpty())
            if (old == refreshed) return@launch
            baseline = refreshed
            refreshingExit = true
            try {
                DataStore.profileCacheStore.putString(exitNode.cacheName, refreshed.exit)
                (supportFragmentManager.findFragmentById(R.id.settings) as? PreferenceFragmentCompat)
                    ?.findPreference<EditTextPreference>(exitNode.cacheName)?.text = refreshed.exit
            } finally {
                refreshingExit = false
            }
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key == exitNode.cacheName) {
            if (refreshingExit) return
            baseline = baseline?.copy(exitEdited = true)
        }
        super.onPreferenceDataStoreChanged(store, key)
    }

    override suspend fun saveAndExit() = saveMutex.withLock {
        val id = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
        if (id == 0L) {
            super.saveAndExit()
            return@withLock
        }
        try {
            val draft = withContext(Dispatchers.IO) {
                (checkNotNull(proxyEntity).requireBean() as TailscaleBean).clone()
            }
            val (expected, proposed) = onMainDispatcher {
                checkNotNull(baseline) to draft.apply { serialize() }
            }
            val saved = withContext(Dispatchers.IO) {
                TailscaleProfileStore.saveEditor(id, expected.identity, expected.exit, proposed, expected.exitEdited)
            }
            if (id == DataStore.selectedProxy) SagerNet.stopService()
            ProfileManager.postUpdate(saved)
            onMainDispatcher { finish() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onMainDispatcher {
                Toast.makeText(
                    this@TailscaleSettingsActivity,
                    if (e.message?.contains("tailscale:conflict") == true) {
                        R.string.tailscale_editor_conflict
                    } else {
                        R.string.tailscale_editor_save_failed
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onServiceConnected(service: ISagerNetService) {}

    override fun TailscaleBean.init() {
        baseline = TailscaleEditorBaseline(proxyEntity?.uuid.orEmpty(), this.exitNode.orEmpty())
        pbm.writeToCacheAll(this)
    }

    override fun TailscaleBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
        // The base fragment resets dirty during view creation, after this callback.
        if (restoredEditorDirty) view.post { DataStore.dirty = true }
    }

    override fun PreferenceFragmentCompat.createPreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.tailscale_preferences)
        pbm.setPreferenceFragment(this)
        findPreference<Preference>("tailscaleStatus")!!.setOnPreferenceClickListener {
            val id = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
            if (id == 0L) {
                Toast.makeText(this@TailscaleSettingsActivity, R.string.tailscale_save_first, Toast.LENGTH_SHORT).show()
            } else {
                startActivity(
                    Intent(this@TailscaleSettingsActivity, TailscaleStatusActivity::class.java)
                        .putExtra(TailscaleStatusActivity.EXTRA_PROFILE_ID, id),
                )
            }
            true
        }

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
            try {
                val entity = SagerDatabase.proxyDao.getById(
                    profileId,
                ) ?: error(getString(R.string.tailscale_save_first))
                val prepared = buildConfig(entity, forTest = true)
                val peers = try {
                    loadPeers(entity, prepared)
                } catch (e: TailscaleLoginPending) {
                    // The running service owns the node: offer the page, then poll it for the result.
                    if (!onMainDispatcher { askToOpenLogin(e.url) }) throw TailscaleLoginDeclined()
                    awaitPeersAfterLogin(entity, prepared)
                }
                onMainDispatcher { showExitNodes(peers.filter { it.exitNode }) }
            } catch (_: TailscaleLoginDeclined) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(
                        this@TailscaleSettingsActivity,
                        e.readableMessage,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                pickingExitNode = false
            }
        }
    }

    private suspend fun awaitPeersAfterLogin(entity: ProxyEntity, prepared: ConfigBuildResult): List<TailscalePeer> {
        val deadline = SystemClock.elapsedRealtime() + TAILSCALE_LOGIN_TIMEOUT_MS
        while (true) {
            try {
                return loadPeers(entity, prepared)
            } catch (e: TailscaleLoginPending) {
                if (SystemClock.elapsedRealtime() > deadline) throw e
            }
            delay(2_000)
        }
    }

    // Offers the interactive login page; true when the user opened it.
    private suspend fun askToOpenLogin(url: String): Boolean = suspendCancellableCoroutine { continuation ->
        if (!continuation.isActive) return@suspendCancellableCoroutine
        if (isFinishing || isDestroyed) {
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        val link = TailscaleLoginLink.parse(url)
        if (link == null) {
            Toast.makeText(this, R.string.tailscale_status_login_invalid, Toast.LENGTH_LONG).show()
            continuation.resume(false)
            return@suspendCancellableCoroutine
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.tailscale_login_required)
            .setMessage(
                getString(R.string.tailscale_status_login_origin, link.origin) + "\n\n" +
                    getString(R.string.tailscale_status_login_warning) +
                    if (link.isHttp) "\n\n" + getString(R.string.tailscale_login_http_warning) else "",
            )
            .setPositiveButton(R.string.tailscale_login_open) { _, _ ->
                if (continuation.isActive) {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, link.url.toUri()))
                        Toast.makeText(this, R.string.tailscale_login_waiting, Toast.LENGTH_LONG).show()
                        continuation.resume(true)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            }
            .setNegativeButton(
                android.R.string.cancel,
            ) { _, _ -> if (continuation.isActive) continuation.resume(false) }
            .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
            .show()
        continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
    }

    // Through the service when it drives this node, otherwise a short-lived node on the saved
    // profile; refused while the service runs any node the probe would start (a group front or
    // landing node included).
    private suspend fun loadPeers(entity: ProxyEntity, prepared: ConfigBuildResult): List<TailscalePeer> {
        val nodes = prepared.tailscaleEndpoints.keys
        // The service reports a pending login as an error carrying the URL; surface it as
        // TailscaleLoginPending so the caller can offer the page instead of a raw failure.
        val viaService = { service: ISagerNetService ->
            try {
                parseTailscalePeers(service.tailscalePeers(entity.id))
            } catch (e: IllegalStateException) {
                throw LOGIN_URL.find(e.readableMessage)?.let { TailscaleLoginPending(it.value) } ?: e
            }
        }
        return TailscaleAccess.run({ connection.service }, nodes, viaService) {
            TailscalePeersInstance(entity, prepared).listPeers { url -> onMainDispatcher { askToOpenLogin(url) } }
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
                try {
                    (exitNode.preference as EditTextPreference).text = peers[index].exitNodeAddress()
                } catch (e: IllegalStateException) {
                    Toast.makeText(this, e.readableMessage, Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun resetIdentity() {
        // A running core may hold the directory open and write the current identity back;
        // the profile may also be in use as a chain hop or rule outbound, so check the service, not the selection.
        if (DataStore.serviceState.ownsTailscaleState) {
            Toast.makeText(this, R.string.tailscale_reset_identity_running, Toast.LENGTH_SHORT).show()
            return
        }
        val profileId = DataStore.editingId
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val marker = resetTailscaleIdentity(profileId)
                onMainDispatcher {
                    proxyEntity?.uuid = marker
                    baseline = baseline?.copy(identity = marker)
                    Toast.makeText(
                        this@TailscaleSettingsActivity,
                        R.string.tailscale_reset_identity_done,
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
                onMainDispatcher {
                    Toast.makeText(
                        this@TailscaleSettingsActivity,
                        e.readableMessage,
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }
}
