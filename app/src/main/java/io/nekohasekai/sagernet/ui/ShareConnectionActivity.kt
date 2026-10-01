package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.os.RemoteException
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.viewModels
import androidx.appcompat.widget.Toolbar
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.LayoutShareConnectionBinding
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.toUri
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.widget.QRCodeDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Hands the running proxy to other devices on the local networks: the mixed inbound binds to
 * every IPv4 interface with a dedicated credential. Sharing is the existing allowAccess flag;
 * this screen shows where the proxy is reachable and what to import.
 */
class ShareConnectionActivity :
    ThemedActivity(),
    SagerConnection.Callback {

    private lateinit var binding: LayoutShareConnectionBinding
    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_SHARE_CONNECTION)
    private val sync by viewModels<ShareServiceSync>()
    private var applyJob: Job? = null
    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) {
            sync.onLaunchFailed()
            snackbar(R.string.vpn_permission_denied).show()
        }
    }
    private var refreshVersion = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = LayoutShareConnectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(findViewById<Toolbar>(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.share_connection)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.baseline_arrow_back_24)
        }

        binding.shareSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked == DataStore.allowAccess) return@setOnCheckedChangeListener
            DataStore.allowAccess = checked
            // refresh() reads shareSecret and so creates it on first use; that write must be
            // queued before applyToService() waits for writes and the service reads the store.
            refresh()
            applyToService(startIfStopped = checked)
        }
        binding.shareCopyCredentials.setOnClickListener {
            copy("${Key.SHARE_USERNAME}:${DataStore.shareSecret}")
        }
        binding.shareRegenerate.setOnClickListener {
            DataStore.regenerateShareSecret()
            refresh()
            applyToService(startIfStopped = false)
            snackbar(R.string.share_connection_regenerated).show()
        }
        connection.connect(this, this)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        connection.disconnect(this)
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!super.onSupportNavigateUp()) finish()
        return true
    }

    override fun snackbarInternal(text: CharSequence): Snackbar = Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG)

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        sync.onState(state)
        // The barrier is only worth paying for when a change can still act on this report.
        if (sync.pending) applyPendingChanges()
        refresh()
    }

    // Binding does not announce the state, and a change made before the bind completed waits for
    // it. The same applies after the service process died and the binding came back.
    override fun onServiceConnected(service: ISagerNetService) {
        val state = try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
        DataStore.serviceState = state
        stateChanged(state, null, null)
    }

    // The inbound is generated at start: a running service reloads, a stopped one starts when
    // sharing is switched on. The flag lives in the cached store, so wait for the write before
    // the service process reads it.
    private fun applyToService(startIfStopped: Boolean) {
        sync.onChange(DataStore.serviceState, startIfStopped, DataStore.allowAccess)
        applyPendingChanges()
    }

    private fun applyPendingChanges() {
        // A newer change must get its own write barrier; an older waiter must not consume it.
        // Destruction cancels the waiter, while the ViewModel keeps the intent for the next bind.
        applyJob?.cancel()
        applyJob = lifecycleScope.launch {
            try {
                DataStore.configurationStore.awaitWrites()
                act(sync.nextAction(DataStore.serviceState, DataStore.allowAccess))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sync.onLaunchFailed()
                Logs.w(e)
                snackbar(R.string.service_failed).show()
            }
        }
    }

    private fun act(action: ShareServiceSync.Action) {
        when (action) {
            ShareServiceSync.Action.Reload -> SagerNet.reloadService()
            ShareServiceSync.Action.Launch -> connect.launch(null)
            ShareServiceSync.Action.None -> Unit
        }
    }

    private fun refresh() {
        val sharing = DataStore.allowAccess
        binding.shareSwitch.isChecked = sharing
        binding.shareCredentials.text = getString(R.string.share_connection_username) + ": " + Key.SHARE_USERNAME +
            "\n" + getString(R.string.share_connection_password) + ": " + DataStore.shareSecret
        val port = DataStore.mixedPort
        val addresses = lanAddresses()
        binding.shareAddresses.removeAllViews()
        if (addresses.isEmpty()) {
            binding.shareAddresses.addView(TextView(this).apply { setText(R.string.share_connection_no_address) })
        }
        addresses.forEach { (label, address) -> binding.shareAddresses.addView(addressRow(label, address, port)) }
        // Only the latest lookup may write the status: an older one finishing late would re-enable
        // the switch from a sharing value that has since changed.
        val version = ++refreshVersion
        runOnDefaultDispatcher {
            val connected = DataStore.serviceState.connected
            val profile = ProfileManager.getProfile(if (connected) DataStore.currentProfile else DataStore.selectedProxy)
            // A full custom config is handed to the core verbatim, so no shared listener is generated.
            val shareable = profile != null && profile.configBean?.type != 0
            val status = when {
                profile == null -> getString(R.string.profile_empty)
                !shareable -> getString(R.string.share_connection_unsupported, profile.displayName())
                !sharing -> getString(R.string.share_connection_off)
                !connected -> getString(R.string.share_connection_waiting)
                else -> getString(R.string.share_connection_on, profile.displayName(), port)
            }
            onMainDispatcher {
                if (version != refreshVersion) return@onMainDispatcher
                binding.shareStatus.text = status
                // The switch starts disabled in the layout until this lookup has run. Turning sharing
                // off must stay possible whatever the current profile is.
                binding.shareSwitch.isEnabled = shareable || DataStore.allowAccess
            }
        }
    }

    private fun addressRow(label: String, address: String, port: Int): LinearLayout {
        val link = SOCKSBean().apply {
            serverAddress = address
            serverPort = port
            username = Key.SHARE_USERNAME
            password = DataStore.shareSecret
            name = getString(R.string.share_connection_link_name)
            initializeDefaultValues()
        }.toUri()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(context).apply {
                    text = "$address:$port\n$label"
                    setTextIsSelectable(true)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(textButton(R.string.share_qr_nfc) { QRCodeDialog(link, "$address:$port").show(supportFragmentManager, null) })
            addView(textButton(R.string.share_connection_copy_link) { copy(link) })
        }
    }

    private fun textButton(textRes: Int, onClick: () -> Unit) = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
        setText(textRes)
        setOnClickListener { onClick() }
    }

    private fun copy(text: String) {
        snackbar(if (SagerNet.trySetPrimaryClip(text)) R.string.copy_success else R.string.copy_failed).show()
    }

    // IPv4 only: the inbound listens on 0.0.0.0. The tun address belongs to this device alone.
    // Whether an address outside the private ranges is reachable depends on the carrier or
    // router in front of it, so the label only says that it is not private.
    // ponytail: interface names are vendor specific, so the label is the raw name.
    private fun lanAddresses(): List<Pair<String, String>> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("tun") }
            .flatMap { iface ->
                iface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLinkLocalAddress }
                    .map { address ->
                        val label = if (address.isSiteLocalAddress) iface.name else iface.name + ", " + getString(R.string.share_connection_public)
                        label to address.hostAddress!!
                    }
            }
    }.getOrDefault(emptyList())
}
