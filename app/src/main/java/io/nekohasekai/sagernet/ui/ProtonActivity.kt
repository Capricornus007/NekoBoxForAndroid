package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.ActivityProtonBinding
import io.nekohasekai.sagernet.fmt.proton.ProtonJson
import io.nekohasekai.sagernet.fmt.proton.ProtonNode
import io.nekohasekai.sagernet.fmt.proton.ProtonSidecar
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.launch

class ProtonActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProtonBinding
    private var nodes: List<ProtonNode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProtonBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (!DataStore.protonWarningAccepted) {
            showWarning()
        }

        if (!ProtonSidecar.isAvailable()) {
            binding.status.setText(R.string.proton_sidecar_missing)
            binding.signIn.isEnabled = false
            binding.refresh.isEnabled = false
            return
        }

        binding.signIn.setOnClickListener { signIn() }
        binding.signOut.setOnClickListener { signOut() }
        binding.refresh.setOnClickListener { refresh() }

        if (DataStore.protonWarningAccepted && ProtonSidecar.hasSession()) {
            binding.status.setText(R.string.proton_signed_in)
            refresh()
        }
    }

    // Declining leaves nothing to show: every action on this screen is covered by
    // the warning, so it is shown before any of them are reachable.
    private fun showWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.proton_warning_title)
            .setMessage(R.string.proton_warning_message)
            .setCancelable(false)
            .setPositiveButton(R.string.proton_warning_accept) { _, _ ->
                DataStore.protonWarningAccepted = true
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun signIn() {
        val account = binding.account.text?.toString()?.trim().orEmpty()
        val password = binding.password.text?.toString().orEmpty()
        val twoFactor = binding.twoFactor.text?.toString()?.trim().orEmpty()
        if (account.isEmpty() || password.isEmpty()) return

        binding.signIn.isEnabled = false
        binding.refresh.isEnabled = false
        binding.status.setText(R.string.proton_refresh)

        lifecycleScope.launch {
            val result = ProtonSidecar.login(account, password, twoFactor)
            if (isFinishing || isDestroyed) return@launch
            binding.signIn.isEnabled = true
            binding.refresh.isEnabled = true
            when {
                result.ok -> {
                    binding.password.text = null
                    binding.twoFactorLayout.visibility = View.GONE
                    binding.status.setText(R.string.proton_signed_in)
                    refresh()
                }

                result.twoFactorRequired -> {
                    // Keep the code field visible so the retry is one tap away.
                    binding.twoFactorLayout.visibility = View.VISIBLE
                    binding.status.setText(R.string.proton_2fa_required)
                }

                else -> binding.status.text = result.error.ifEmpty { result.reason }
            }
        }
    }

    private fun signOut() {
        ProtonSidecar.logout()
        nodes = emptyList()
        binding.nodeList.removeAllViews()
        binding.status.setText(R.string.proton_not_signed_in)
    }

    private fun refresh() {
        if (!ProtonSidecar.hasSession()) {
            binding.status.setText(R.string.proton_not_signed_in)
            return
        }
        binding.refresh.isEnabled = false
        lifecycleScope.launch {
            val result = ProtonSidecar.nodes()
            if (isFinishing || isDestroyed) return@launch
            binding.refresh.isEnabled = true
            if (!result.ok) {
                binding.status.text = result.error
                return@launch
            }
            nodes = result.nodes
            binding.status.text =
                getString(R.string.proton_node_count, result.nodes.size, result.dropped)
            renderNodes()
        }
    }

    private fun renderNodes() {
        binding.nodeList.removeAllViews()
        val gap = (8 * resources.displayMetrics.density).toInt()
        for (node in nodes) {
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
            button.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = gap }
            button.text = getString(R.string.proton_add) + " · " + nodeLabel(node)
            button.setOnClickListener { import(node) }
            binding.nodeList.addView(button)
        }
    }

    private fun nodeLabel(node: ProtonNode): String {
        val place = listOf(node.city, node.country).filter { it.isNotEmpty() }.joinToString(" · ")
        return if (place.isEmpty()) node.name else "${node.name}   $place"
    }

    private fun import(node: ProtonNode) {
        binding.refresh.isEnabled = false
        lifecycleScope.launch {
            val pair = ProtonSidecar.keyPair()
            if (isFinishing || isDestroyed) return@launch
            if (!pair.valid) {
                binding.refresh.isEnabled = true
                binding.status.setText(R.string.proton_sidecar_missing)
                return@launch
            }
            val address = if (node.supportsIPv6) {
                "$CLIENT_IP/32, $CLIENT_IP_V6/128"
            } else {
                "$CLIENT_IP/32"
            }
            val dns = if (node.supportsIPv6) "$SERVER_IP, $SERVER_IP_V6" else SERVER_IP
            try {
                val conf = ProtonJson.wireGuardConf(node, pair.privateKey, address, dns)
                val beans = RawUpdater.parseWireGuardConf(conf)
                if (beans.isEmpty()) {
                    if (!isFinishing && !isDestroyed) binding.status.setText(R.string.proton_no_nodes)
                    return@launch
                }
                val bean = beans.first()
                bean.name = nodeLabel(node)
                ProfileManager.createProfile(DataStore.selectedGroupForImport(), bean)
                if (!isFinishing && !isDestroyed) {
                    binding.status.text = getString(R.string.proton_added, node.name)
                }
            } catch (e: Exception) {
                Logs.w("proton import failed", e)
                if (!isFinishing && !isDestroyed) {
                    binding.status.text = e.localizedMessage ?: e.toString()
                }
            } finally {
                if (!isFinishing && !isDestroyed) binding.refresh.isEnabled = true
            }
        }
    }

    companion object {
        // Proton's own client constants: the in-tunnel address is fixed rather than
        // assigned, and its DNS is the server side of that same pair.
        private const val CLIENT_IP = "10.2.0.2"
        private const val SERVER_IP = "10.2.0.1"
        private const val CLIENT_IP_V6 = "2a07:b944::2:2"
        private const val SERVER_IP_V6 = "2a07:b944::2:1"
    }
}
