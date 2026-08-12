package io.nekohasekai.sagernet.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.databinding.LayoutAboutItemBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File

/**
 * About screen. Previously backed by the abandoned material-about-library; now hand-rolled with
 * a simple RecyclerView so we no longer depend on an unmaintained library. The item list is
 * rebuilt on demand (e.g. after returning from the battery-optimization settings screen).
 */
class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    private var _binding: LayoutAboutBinding? = null
    private val binding get() = _binding!!
    private val adapter = AboutAdapter()
    private var pendingApkFile: File? = null

    private val requestIgnoreBatteryOptimizations = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        // The battery-optimization request/settings screen returns RESULT_CANCELED even
        // when the user actually granted the exemption, so don't gate on the result code
        // - just rebuild the list so the item's on/off subtext reflects the new state.
        if (isAdded) rebuildList()
    }

    private val requestInstallPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val apk = pendingApkFile
        pendingApkFile = null
        if (apk != null && canInstallPackages()) {
            installApk(apk)
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        _binding = LayoutAboutBinding.bind(view)
        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        binding.aboutList.adapter = adapter
        rebuildList()
    }

    override fun onDestroyView() {
        // The fragment instance outlives its view; release the view-scoped binding and detach
        // the adapter so the destroyed RecyclerView/view tree isn't leaked.
        _binding?.aboutList?.adapter = null
        _binding = null
        super.onDestroyView()
    }

    private fun rebuildList() {
        // PackageCache.awaitLoadSync() and the plugin enumeration can, in the worst case (cache
        // still loading), block; build the list off the main thread and post the result back so
        // the About screen never janks.
        runOnIoDispatcher {
            val pluginItems = mutableListOf<AboutItem>()
            PackageCache.awaitLoadSync()
            for ((_, pkg) in PackageCache.installedPluginPackages) {
                try {
                    val pluginId = pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                    if (pluginId.isNullOrBlank()) continue
                    pluginItems += AboutItem(
                        icon = R.drawable.ic_baseline_nfc_24,
                        text = getString(R.string.version_x, pluginId) +
                            " (${Plugins.displayExeProvider(pkg.packageName)})",
                        subText = "v" + pkg.versionName,
                        onClick = {
                            startActivity(
                                Intent().apply {
                                    action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                    data = Uri.fromParts("package", pkg.packageName, null)
                                },
                            )
                        },
                    )
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
            runOnMainDispatcher {
                if (!isAdded) return@runOnMainDispatcher
                adapter.submitList(buildItems(pluginItems))
            }
        }
    }

    /** Assembles the full item list. [pluginItems] is computed off the main thread by the caller. */
    private fun buildItems(pluginItems: List<AboutItem>): List<AboutItem> {
        val items = mutableListOf<AboutItem>()

        items += AboutItem(
            icon = R.drawable.ic_baseline_update_24,
            text = getString(R.string.app_version),
            subText = SagerNet.appVersionNameForDisplay,
            onClick = {
                requireContext().launchCustomTab(
                    "https://github.com/Capricornus007/NekoBoxForAndroid/releases",
                )
            },
        )
        items += AboutItem(
            text = getString(R.string.check_update_release),
            onClick = { checkUpdate(false) },
        )
        items += AboutItem(
            text = getString(R.string.check_update_preview),
            onClick = { checkUpdate(true) },
        )
        items += AboutItem(
            icon = R.drawable.ic_baseline_layers_24,
            text = getString(R.string.version_x, "sing-box"),
            subText = Libcore.versionBox(),
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
            val ignoring = pm.isIgnoringBatteryOptimizations(app.packageName)
            items += AboutItem(
                icon = R.drawable.ic_baseline_running_with_errors_24,
                text = getString(R.string.ignore_battery_optimizations),
                subText = getString(
                    if (ignoring) {
                        R.string.battery_optimization_enabled
                    } else {
                        R.string.battery_optimization_disabled
                    },
                ),
                onClick = {
                    // The ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS dialog only appears while
                    // the app is still optimized; once exempt it is a no-op. So when already
                    // exempt, send the user to the battery settings screen where they can toggle
                    // it back off.
                    val intent = if (ignoring) {
                        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    } else {
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            "package:${app.packageName}".toUri(),
                        )
                    }
                    requestIgnoreBatteryOptimizations.launch(intent)
                },
            )
        }

        return items
    }

    private fun checkUpdate(checkPreview: Boolean) {
        runOnIoDispatcher {
            try {
                val client = Libcore.newHttpClient().apply {
                    modernTLS()
                    trySocks5(
                        DataStore.mixedPort,
                        DataStore.mixedInboundUser,
                        DataStore.mixedInboundPass,
                    )
                }
                try {
                    val response = client.newRequest().apply {
                        if (checkPreview) {
                            setURL(
                                "https://api.github.com/repos/Capricornus007/NekoBoxForAndroid/releases/tags/preview",
                            )
                        } else {
                            setURL("https://api.github.com/repos/Capricornus007/NekoBoxForAndroid/releases/latest")
                        }
                    }.execute()
                    val release = JSONObject(Util.getStringBox(response.getContentStringLimited(10L * 1024 * 1024)))
                    val releaseName = release.getString("name")
                    val releaseUrl = release.getString("html_url")
                    var haveUpdate = releaseName.isNotBlank()
                    haveUpdate = if (isPreview) {
                        if (checkPreview) {
                            haveUpdate && releaseName != BuildConfig.PRE_VERSION_NAME
                        } else {
                            // User: 1.3.9 pre-1.4.0 Stable: 1.3.9 -> No update
                            haveUpdate && releaseName != BuildConfig.VERSION_NAME
                        }
                    } else {
                        // User: 1.4.0 Preview: pre-1.4.0 -> No update
                        // User: 1.4.0 Preview: pre-1.4.1 -> Update
                        // User: 1.4.0 Stable: 1.4.0 -> No update
                        // User: 1.4.0 Stable: 1.4.1 -> Update
                        haveUpdate && !releaseName.contains(BuildConfig.VERSION_NAME)
                    }
                    val assets = release.optJSONArray("assets")?.filterIsInstance<JSONObject>().orEmpty()
                    val apkAsset = pickApkAsset(assets)
                    runOnMainDispatcher {
                        // The async work above may outlive the fragment's attachment
                        // (e.g. user navigates away). Touching requireContext()/app
                        // resources while detached throws IllegalStateException
                        // (issue #1192). Bail out if no longer attached.
                        if (!isAdded) return@runOnMainDispatcher
                        if (haveUpdate) {
                            val context = requireContext()
                            if (apkAsset != null) {
                                val downloadUrl = apkAsset.getStr("browser_download_url")
                                    ?: return@runOnMainDispatcher openReleasePage(releaseUrl)
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(R.string.update_dialog_title)
                                    .setMessage(
                                        context.getString(
                                            R.string.update_dialog_message,
                                            SagerNet.appVersionNameForDisplay,
                                            releaseName,
                                        ),
                                    )
                                    .setPositiveButton(R.string.yes) { _, _ ->
                                        downloadAndInstall(downloadUrl, releaseName, releaseUrl)
                                    }
                                    .setNeutralButton(R.string.update_open_page) { _, _ ->
                                        openReleasePage(releaseUrl)
                                    }
                                    .setNegativeButton(R.string.no, null)
                                    .show()
                            } else {
                                val abis = Build.SUPPORTED_ABIS.filter { it.isNotBlank() }
                                    .joinToString(", ")
                                MaterialAlertDialogBuilder(context)
                                    .setTitle(R.string.update_dialog_title)
                                    .setMessage(
                                        context.getString(R.string.update_no_apk_for_abi, abis),
                                    )
                                    .setPositiveButton(R.string.yes) { _, _ ->
                                        openReleasePage(releaseUrl)
                                    }
                                    .setNegativeButton(R.string.no, null)
                                    .show()
                            }
                        } else {
                            Toast.makeText(app, R.string.check_update_no, Toast.LENGTH_SHORT).show()
                        }
                    }
                } finally {
                    client.close()
                }
            } catch (e: Exception) {
                Logs.w(e)
                runOnMainDispatcher {
                    if (!isAdded) return@runOnMainDispatcher
                    Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Prefer the first SUPPORTED_ABIS entry that has a matching per-ABI release APK. */
    private fun pickApkAsset(assets: List<JSONObject>): JSONObject? {
        val apkAssets = assets.filter { asset ->
            val name = asset.getStr("name")?.lowercase() ?: return@filter false
            name.endsWith(".apk")
        }
        if (apkAssets.isEmpty()) return null
        for (abi in Build.SUPPORTED_ABIS) {
            if (abi.isNullOrBlank()) continue
            val needle = "-$abi.apk"
            apkAssets.find { (it.getStr("name") ?: "").contains(needle, ignoreCase = true) }
                ?.let { return it }
        }
        // universal / single apk without abi suffix
        return apkAssets.find { asset ->
            val name = asset.getStr("name")?.lowercase() ?: return@find false
            !name.contains("arm64") && !name.contains("armeabi") &&
                !name.contains("x86_64") && !name.contains("x86")
        } ?: apkAssets.firstOrNull()
    }

    private fun openReleasePage(releaseUrl: String) {
        if (!isAdded) return
        runCatching {
            requireContext().startActivity(Intent(Intent.ACTION_VIEW, releaseUrl.toUri()))
        }
    }

    private fun downloadAndInstall(downloadUrl: String, releaseName: String, releaseUrl: String) {
        if (!isAdded) return
        val context = requireContext()
        val apkFile = File(app.cacheDir, "update.apk")

        // Cancelled install earlier: reuse same-version cached APK without re-download.
        if (apkFile.isFile && apkFile.length() > 0L &&
            DataStore.pendingUpdateVersion == releaseName
        ) {
            requestInstall(apkFile)
            return
        }

        val progressBinding = LayoutProgressBinding.inflate(layoutInflater)
        progressBinding.content.setText(R.string.update_downloading)
        val progressDialog = MaterialAlertDialogBuilder(context)
            .setView(progressBinding.root)
            .setCancelable(false)
            .create()
        progressDialog.show()

        runOnIoDispatcher {
            val client = Libcore.newHttpClient().apply {
                modernTLS()
                keepAlive()
                trySocks5(
                    DataStore.mixedPort,
                    DataStore.mixedInboundUser,
                    DataStore.mixedInboundPass,
                )
            }
            try {
                val response = client.newRequest().apply {
                    setURL(downloadUrl)
                }.execute()
                app.cacheDir.mkdirs()
                // Fixed names so repeated updates overwrite instead of stacking versioned APKs.
                // Also drop leftover release-named APKs from older builds of this feature.
                app.cacheDir.listFiles()?.forEach { f ->
                    val n = f.name.lowercase()
                    if (n.endsWith(".apk") || n.endsWith(".apk.tmp") || n == "update.tmp") {
                        f.delete()
                    }
                }
                val tmpFile = File(app.cacheDir, "update.apk.tmp")
                response.writeTo(tmpFile.canonicalPath)
                if (apkFile.exists()) apkFile.delete()
                if (!tmpFile.renameTo(apkFile)) {
                    tmpFile.copyTo(apkFile, overwrite = true)
                    tmpFile.delete()
                }
                // Record staged version: startup cleans only after installed version catches up.
                // Cancelled install keeps this + update.apk for retry.
                DataStore.pendingUpdateVersion = releaseName
                runOnMainDispatcher {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    if (!isAdded) return@runOnMainDispatcher
                    requestInstall(apkFile)
                }
            } catch (e: Exception) {
                Logs.w(e)
                // Failed download: remove partial file so it doesn't sit in cache forever.
                runCatching {
                    File(app.cacheDir, "update.apk.tmp").delete()
                }
                runOnMainDispatcher {
                    if (progressDialog.isShowing) progressDialog.dismiss()
                    if (!isAdded) return@runOnMainDispatcher
                    val ctx = requireContext()
                    MaterialAlertDialogBuilder(ctx)
                        .setTitle(R.string.error_title)
                        .setMessage(e.readableMessage)
                        .setPositiveButton(android.R.string.ok, null)
                        .setNeutralButton(R.string.update_open_page) { _, _ ->
                            openReleasePage(releaseUrl)
                        }
                        .show()
                }
            } finally {
                client.close()
            }
        }
    }

    private fun canInstallPackages(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            app.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    private fun requestInstall(apkFile: File) {
        if (!isAdded) return
        if (!canInstallPackages()) {
            pendingApkFile = apkFile
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requestInstallPermission.launch(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        "package:${app.packageName}".toUri(),
                    ),
                )
            }
            return
        }
        installApk(apkFile)
    }

    private fun installApk(apkFile: File) {
        if (!isAdded) return
        val context = requireContext()
        if (!apkFile.isFile) {
            Toast.makeText(app, R.string.error_title, Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            BuildConfig.APPLICATION_ID + ".cache",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            startActivity(intent)
        }.onFailure { e ->
            Logs.w(e)
            Toast.makeText(app, e.readableMessage, Toast.LENGTH_SHORT).show()
        }
    }

    private data class AboutItem(
        @DrawableRes val icon: Int = 0,
        val text: CharSequence,
        val subText: CharSequence? = null,
        val onClick: (() -> Unit)? = null,
    )

    private class AboutAdapter : ListAdapter<AboutItem, AboutViewHolder>(DIFF) {

        companion object {
            // AboutItem carries an onClick lambda, so compare only the visible content.
            private val DIFF = object : DiffUtil.ItemCallback<AboutItem>() {
                override fun areItemsTheSame(oldItem: AboutItem, newItem: AboutItem) = oldItem.text == newItem.text

                override fun areContentsTheSame(oldItem: AboutItem, newItem: AboutItem) =
                    oldItem.icon == newItem.icon &&
                        oldItem.text == newItem.text &&
                        oldItem.subText == newItem.subText
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AboutViewHolder {
            return AboutViewHolder(
                LayoutAboutItemBinding.inflate(
                    LayoutInflater.from(parent.context),
                    parent,
                    false,
                ),
            )
        }

        override fun onBindViewHolder(holder: AboutViewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private class AboutViewHolder(
        private val binding: LayoutAboutItemBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AboutItem) {
            if (item.icon != 0) {
                binding.aboutItemIcon.setImageResource(item.icon)
                binding.aboutItemIcon.visibility = View.VISIBLE
            } else {
                binding.aboutItemIcon.visibility = View.INVISIBLE
            }
            binding.aboutItemText.text = item.text
            if (item.subText.isNullOrBlank()) {
                binding.aboutItemSubtext.visibility = View.GONE
            } else {
                binding.aboutItemSubtext.text = item.subText
                binding.aboutItemSubtext.visibility = View.VISIBLE
            }
            val click = item.onClick
            if (click != null) {
                binding.root.isClickable = true
                binding.root.setOnClickListener { click() }
            } else {
                binding.root.isClickable = false
                binding.root.setOnClickListener(null)
            }
        }
    }
}
