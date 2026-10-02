package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.fmt.tailscale.tailscaleStateDirectory
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type
import java.io.File

class TailscaleSettingsActivity : ProfileSettingsActivity<TailscaleBean>() {

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
            val removed = File(SagerNet.application.noBackupFilesDir, tailscaleStateDirectory(profileId)).deleteRecursively()
            onMainDispatcher {
                val message = if (removed) R.string.tailscale_reset_identity_done else R.string.tailscale_reset_identity_failed
                Toast.makeText(this@TailscaleSettingsActivity, message, Toast.LENGTH_SHORT).show()
            }
        }
    }
}
