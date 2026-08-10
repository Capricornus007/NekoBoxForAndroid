package io.nekohasekai.sagernet.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.util.Linkify
import android.view.View
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.RecyclerView
import com.danielstone.materialaboutlibrary.MaterialAboutFragment
import com.danielstone.materialaboutlibrary.items.MaterialAboutActionItem
import com.danielstone.materialaboutlibrary.model.MaterialAboutCard
import com.danielstone.materialaboutlibrary.model.MaterialAboutList
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutAboutBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager.loadString
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.widget.ListListener
import libcore.Libcore
import moe.matsuri.nb4a.plugin.Plugins
import androidx.core.net.toUri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File

class AboutFragment : ToolbarFragment(R.layout.layout_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutAboutBinding.bind(view)

        ViewCompat.setOnApplyWindowInsetsListener(view, ListListener)
        toolbar.setTitle(R.string.menu_about)

        parentFragmentManager.beginTransaction()
            .replace(R.id.about_fragment_holder, AboutContent())
            .commitAllowingStateLoss()

        runOnDefaultDispatcher {
            val license = view.context.assets.open("LICENSE").bufferedReader().readText()
            onMainDispatcher {
                binding.license.text = license
                Linkify.addLinks(binding.license, Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)
            }
        }
    }

    class AboutContent : MaterialAboutFragment() {

        private var pendingApkFile: File? = null

        val requestIgnoreBatteryOptimizations = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (resultCode, _) ->
            if (resultCode == Activity.RESULT_OK) {
                parentFragmentManager.beginTransaction()
                    .replace(R.id.about_fragment_holder, AboutContent())
                    .commitAllowingStateLoss()
            }
        }

        private val requestInstallPermission = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            val apk = pendingApkFile
            pendingApkFile = null
            if (apk != null && canInstallPackages()) {
                installApk(apk)
            }
        }

        override fun getMaterialAboutList(activityContext: Context): MaterialAboutList {
            return MaterialAboutList.Builder()
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_update_24)
                                .text(R.string.app_version)
                                .subText(SagerNet.appVersionNameForDisplay)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/behindflower/NekoBoxForAndroid/releases"
                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_release)
                                .setOnClickAction {
                                    checkUpdate(false)
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .text(R.string.check_update_preview)
                                .setOnClickAction {
                                    checkUpdate(true)
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_layers_24)
                                .text(getString(R.string.version_x, "sing-box"))
                                .subText(Libcore.versionBox())
                                .setOnClickAction { }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_card_giftcard_24)
                                .text(R.string.donate)
                                .subText(R.string.donate_info)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://matsuridayo.github.io/index_docs/#donate"
                                    )
                                }
                                .build())
                        .apply {
                            PackageCache.awaitLoadSync()
                            for ((_, pkg) in PackageCache.installedPluginPackages) {
                                try {
                                    val pluginId =
                                        pkg.providers?.get(0)?.loadString(Plugins.METADATA_KEY_ID)
                                    if (pluginId.isNullOrBlank()) continue
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_nfc_24)
                                            .text(
                                                getString(
                                                    R.string.version_x,
                                                    pluginId
                                                ) + " (${Plugins.displayExeProvider(pkg.packageName)})"
                                            )
                                            .subText("v" + pkg.versionName)
                                            .setOnClickAction {
                                                startActivity(Intent().apply {
                                                    action =
                                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                                                    data = Uri.fromParts(
                                                        "package", pkg.packageName, null
                                                    )
                                                })
                                            }
                                            .build())
                                } catch (e: Exception) {
                                    Logs.w(e)
                                }
                            }
                        }
                        .apply {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val pm = app.getSystemService(Context.POWER_SERVICE) as PowerManager
                                if (!pm.isIgnoringBatteryOptimizations(app.packageName)) {
                                    addItem(
                                        MaterialAboutActionItem.Builder()
                                            .icon(R.drawable.ic_baseline_running_with_errors_24)
                                            .text(R.string.ignore_battery_optimizations)
                                            .subText(R.string.ignore_battery_optimizations_sum)
                                            .setOnClickAction {
                                                requestIgnoreBatteryOptimizations.launch(
                                                    Intent(
                                                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                                        "package:${app.packageName}".toUri()
                                                    )
                                                )
                                            }
                                            .build())
                                }
                            }
                        }
                        .build())
                .addCard(
                    MaterialAboutCard.Builder()
                        .outline(true)
                        .title(R.string.project)
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_baseline_sanitizer_24)
                                .text(R.string.github)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://github.com/behindflower/NekoBoxForAndroid"

                                    )
                                }
                                .build())
                        .addItem(
                            MaterialAboutActionItem.Builder()
                                .icon(R.drawable.ic_qu_shadowsocks_foreground)
                                .text(R.string.telegram)
                                .setOnClickAction {
                                    requireContext().launchCustomTab(
                                        "https://t.me/behindflower"
                                    )
                                }
                                .build())
                        .build())
                .build()

        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            view.findViewById<RecyclerView>(R.id.mal_recyclerview).apply {
                overScrollMode = RecyclerView.OVER_SCROLL_NEVER
            }
        }

        fun checkUpdate(checkPreview: Boolean) {
            runOnIoDispatcher {
                try {
                    val client = Libcore.newHttpClient().apply {
                        modernTLS()
                        trySocks5(
                            DataStore.mixedPort,
                            DataStore.mixedInboundUser,
                            DataStore.mixedInboundPass
                        )
                    }
                    try {
                        val response = client.newRequest().apply {
                            if (checkPreview) {
                                setURL("https://api.github.com/repos/behindflower/NekoBoxForAndroid/releases/tags/preview")
                            } else {
                                setURL("https://api.github.com/repos/behindflower/NekoBoxForAndroid/releases/latest")
                            }
                        }.execute()
                        val release = JSONObject(Util.getStringBox(response.contentString))
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
                            if (!isAdded) return@runOnMainDispatcher
                            if (haveUpdate) {
                                val context = context ?: return@runOnMainDispatcher
                                if (apkAsset != null) {
                                    val downloadUrl = apkAsset.getStr("browser_download_url")
                                        ?: return@runOnMainDispatcher openReleasePage(releaseUrl)
                                    MaterialAlertDialogBuilder(context)
                                        .setTitle(R.string.update_dialog_title)
                                        .setMessage(
                                            context.getString(
                                                R.string.update_dialog_message,
                                                SagerNet.appVersionNameForDisplay,
                                                releaseName
                                            )
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
                                            context.getString(R.string.update_no_apk_for_abi, abis)
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
            val context = context ?: return
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, releaseUrl.toUri()))
            }
        }

        private fun downloadAndInstall(downloadUrl: String, releaseName: String, releaseUrl: String) {
            if (!isAdded) return
            val context = context ?: return
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
                        DataStore.mixedInboundPass
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
                        val ctx = this@AboutContent.context ?: return@runOnMainDispatcher
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
                            "package:${app.packageName}".toUri()
                        )
                    )
                }
                return
            }
            installApk(apkFile)
        }

        private fun installApk(apkFile: File) {
            val context = context ?: return
            if (!apkFile.isFile) {
                Toast.makeText(app, R.string.error_title, Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                context, BuildConfig.APPLICATION_ID + ".cache", apkFile
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

    }

}
