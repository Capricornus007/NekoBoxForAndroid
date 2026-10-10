package io.nekohasekai.sagernet.ui.profile

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardServiceStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shows the handshake state of the edited WireGuard or AmneziaWG profile while the service runs it. */
internal class WireGuardStatusLoader(private val activity: AppCompatActivity) :
    SagerConnection.Callback,
    DefaultLifecycleObserver {

    private val connection = SagerConnection(SagerConnection.CONNECTION_ID_WIREGUARD_STATUS)

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) = connection.connect(activity, this)

    override fun onDestroy(owner: LifecycleOwner) = connection.disconnect(activity)

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {}

    override fun onServiceConnected(service: ISagerNetService) {}

    fun bind(preference: Preference) {
        val profileId = DataStore.editingId
        preference.isVisible = profileId != 0L
        preference.setOnPreferenceClickListener {
            activity.lifecycleScope.launch {
                val message = withContext(Dispatchers.IO) { describe(profileId) }
                if (activity.isFinishing || activity.isDestroyed) return@launch
                MaterialAlertDialogBuilder(activity)
                    .setTitle(R.string.wireguard_status)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            true
        }
    }

    private fun describe(profileId: Long): String {
        val json = runCatching { connection.service?.wireguardStatus(profileId) }.getOrNull()
        return describeWireGuardStatus(activity, json, System.currentTimeMillis()) { id ->
            SagerDatabase.proxyDao.getById(id)?.displayName() ?: id.toString()
        }
    }
}

/**
 * Dialog text for a status answer. Not running, not part of the running configuration, an
 * unavailable instance and a peer without a handshake each read differently; a profile that runs
 * in several chains lists every instance under the profile whose chain runs it.
 */
internal fun describeWireGuardStatus(context: Context, json: String?, now: Long, ownerName: (Long) -> String): String {
    val state = json?.let { runCatching { parseWireGuardServiceStatus(it) }.getOrNull() }
    if (state == null || !state.running) return context.getString(R.string.wireguard_status_not_running)
    if (state.instances.isEmpty()) return context.getString(R.string.wireguard_status_not_in_config)
    val labelled = state.instances.size > 1
    return state.instances.joinToString("\n\n") { instance ->
        buildList {
            if (labelled) add(context.getString(R.string.wireguard_status_via, ownerName(instance.ownerProfileId)))
            if (instance.error != null) add(context.getString(R.string.wireguard_status_unavailable))
            for (peer in instance.peers) {
                val handshake = if (peer.lastHandshake == 0L) {
                    context.getString(R.string.wireguard_status_never)
                } else {
                    context.getString(
                        R.string.wireguard_status_handshake,
                        DateUtils.getRelativeTimeSpanString(peer.lastHandshake, now, DateUtils.SECOND_IN_MILLIS),
                    )
                }
                val traffic = context.getString(
                    R.string.wireguard_status_traffic,
                    Formatter.formatFileSize(context, peer.rxBytes),
                    Formatter.formatFileSize(context, peer.txBytes),
                )
                add("${peer.endpoint.ifEmpty { peer.publicKey }}: $handshake, $traffic")
            }
        }.joinToString("\n")
    }
}
