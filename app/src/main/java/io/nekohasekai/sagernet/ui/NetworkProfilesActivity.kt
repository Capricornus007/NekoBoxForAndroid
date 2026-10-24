package io.nekohasekai.sagernet.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import moe.matsuri.nb4a.utils.JavaUtil.gson

class NetworkProfilesActivity : ThemedActivity() {
    private lateinit var content: LinearLayout
    private var pending: NetworkProfilePolicy? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        render()
        if (DataStore.networkProfilesEnabled && DataStore.serviceState.started) SagerNet.reloadService(true)
    }
    private val backgroundPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        render()
        if (DataStore.networkProfilesEnabled && DataStore.serviceState.started) SagerNet.reloadService(true)
    }
    private val permissionSettings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        render()
        if (DataStore.networkProfilesEnabled && DataStore.serviceState.started) SagerNet.reloadService(true)
    }
    private fun requestSsidAccess() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        if (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.network_profiles_ssid_ready, Toast.LENGTH_LONG).show()
            return
        }
        val label = if (Build.VERSION.SDK_INT >= 30) packageManager.backgroundPermissionOptionLabel.toString() else getString(R.string.network_profiles_always)
        MaterialAlertDialogBuilder(this).setTitle(R.string.network_profiles_ssid_permission)
            .setMessage(getString(R.string.network_profiles_background_reason, label))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (Build.VERSION.SDK_INT == 29) backgroundPermission.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                else permissionSettings.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            }.show()
    }
    private val selectProfile = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val policy = pending
        if (result.resultCode == Activity.RESULT_OK && policy != null) {
            val id = result.data?.getLongExtra(ProfileSelectActivity.EXTRA_PROFILE_ID, 0L) ?: 0L
            if (id > 0) edit(policy.copy(profileId = id))
        }
        pending = null
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pending = savedInstanceState?.getString("pending")?.let { gson.fromJson(it, NetworkProfilePolicy::class.java) }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(MaterialToolbar(this).apply {
            setTitle(R.string.network_profiles)
            setNavigationIcon(R.drawable.baseline_arrow_back_24)
            setNavigationContentDescription(androidx.appcompat.R.string.abc_action_bar_up_description)
            setNavigationOnClickListener { finish() }
        }, ViewGroup.LayoutParams(-1, -2))
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(24)) }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        render()
        savedInstanceState?.getString("editor")?.let { json ->
            content.post { edit(gson.fromJson(json, NetworkProfilePolicy::class.java)) }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pending?.let { outState.putString("pending", gson.toJson(it)) }
        if (editor?.isShowing == true) editorState?.invoke()?.let { outState.putString("editor", gson.toJson(it)) }
        super.onSaveInstanceState(outState)
    }
    private fun changed(reload: Boolean = DataStore.networkProfilesEnabled) {
        render()
        if (reload && DataStore.serviceState.started) SagerNet.reloadService(true)
    }
    private fun render() {
        content.removeAllViews()
        content.addView(MaterialSwitch(this).apply {
            setText(R.string.network_profiles_enable)
            isChecked = DataStore.networkProfilesEnabled
            setOnCheckedChangeListener { _, checked ->
                NetworkSettingsHistory.capture()
                DataStore.networkProfilesEnabled = checked
                changed(true)
            }
        }, ViewGroup.LayoutParams(-1, -2))
        content.addView(TextView(this).apply { setText(R.string.network_profiles_summary); setPadding(0, dp(12), 0, dp(12)) })
        content.addView(MaterialButton(this).apply {
            setText(R.string.network_profiles_ssid_permission)
            setOnClickListener { requestSsidAccess() }
        })
        content.addView(TextView(this).apply { setText(R.string.network_profiles_ssid_hint); setPadding(0, dp(8), 0, dp(8)) })
        for (policy in NetworkProfiles.policies()) {
            val card = MaterialCardView(this).apply { radius = dp(24).toFloat(); cardElevation = 0f }
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)) }
            body.addView(TextView(this).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                text = transportLabel(policy.transport) + policy.ssid.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()
            })
            body.addView(TextView(this).apply {
                text = SagerDatabase.proxyDao.getById(policy.profileId)?.displayName() ?: getString(R.string.route_fallback_missing)
            })
            body.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                setText(R.string.network_profiles_edit)
                setOnClickListener { edit(policy) }
            })
            card.addView(body)
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        content.addView(MaterialButton(this).apply {
            setText(R.string.network_profiles_add)
            isEnabled = NetworkProfiles.policies().size < 32
            setOnClickListener {
                val types = listOf("wifi", "mobile", "ethernet")
                MaterialAlertDialogBuilder(this@NetworkProfilesActivity).setTitle(R.string.network_profiles_add)
                    .setItems(types.map(::transportLabel).toTypedArray()) { _, index ->
                        pending = NetworkProfilePolicy(transport = types[index])
                        selectProfile.launch(Intent(this@NetworkProfilesActivity, ProfileSelectActivity::class.java))
                    }.show()
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
    }
    private fun transportLabel(type: String) = when (type) {
        "mobile" -> getString(R.string.network_profiles_mobile)
        "ethernet" -> "Ethernet"
        else -> "Wi-Fi"
    }
    private fun edit(policy: NetworkProfilePolicy) {
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        fun input(label: Int, value: String, multiline: Boolean = true): TextInputEditText {
            val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { hint = getString(label) }
            val edit = TextInputEditText(layout.context).apply { setText(value); setSingleLine(!multiline); minLines = if (multiline) 2 else 1; maxLines = 6 }
            layout.addView(edit, LinearLayout.LayoutParams(-1, -2)); body.addView(layout)
            return edit
        }
        val ssid = if (policy.transport == "wifi") input(R.string.network_profiles_ssid, policy.ssid, false) else null
        val remote = input(R.string.network_profiles_remote_dns, policy.remoteDns)
        val direct = input(R.string.network_profiles_direct_dns, policy.directDns)
        body.addView(MaterialButton(this, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = SagerDatabase.proxyDao.getById(policy.profileId)?.displayName() ?: getString(R.string.route_fallback_profile)
            setOnClickListener {
                pending = policy.copy(ssid = ssid?.text?.toString().orEmpty(), remoteDns = remote.text.toString(), directDns = direct.text.toString())
                editor?.dismiss()
                selectProfile.launch(Intent(this@NetworkProfilesActivity, ProfileSelectActivity::class.java))
            }
        })
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.network_profiles_edit)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(R.string.yes, null).setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.delete) { _, _ ->
                NetworkSettingsHistory.capture()
                NetworkProfiles.save(NetworkProfiles.policies().filterNot { it.id == policy.id }); changed()
            }.create()
        editor = dialog
        editorState = { policy.copy(ssid = ssid?.text?.toString().orEmpty(), remoteDns = remote.text.toString(), directDns = direct.text.toString()) }
        dialog.setOnDismissListener { editorState = null }
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val updated = policy.copy(ssid = ssid?.text?.toString().orEmpty(), remoteDns = remote.text.toString().trim(), directDns = direct.text.toString().trim())
                    val policies = NetworkProfiles.policies().filterNot { it.id == policy.id } + updated
                    NetworkProfiles.validate(updated)
                    require(policies.map { it.transport to it.ssid }.distinct().size == policies.size)
                    NetworkSettingsHistory.capture()
                    NetworkProfiles.save(policies)
                    dialog.dismiss(); changed()
                } catch (_: Exception) { Toast.makeText(this, R.string.network_profiles_invalid, Toast.LENGTH_LONG).show() }
            }
        }
        dialog.show()
    }
    private var editor: androidx.appcompat.app.AlertDialog? = null
    private var editorState: (() -> NetworkProfilePolicy)? = null
}
