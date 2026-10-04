package io.nekohasekai.sagernet.bg

import android.content.Context
import android.system.Os
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.filterIsInstance
import io.nekohasekai.sagernet.ktx.getStr
import io.nekohasekai.sagernet.ktx.readableMessage
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val MAX_HTTP_JSON_BYTES = 10L * 1024 * 1024
private const val MAX_RULE_ASSET_BYTES = 256L * 1024 * 1024

/**
 * sing-box 格式的路由資料庫（geoip.db / geosite.db）。
 * 手動更新（設定頁的按鈕）與背景自動更新都走這裡：同一份抓取與落地邏輯只准存在一處，
 * 兩邊各寫一次早晚會分岔。遠端 rule-set 的更新間隔由核心自己的 update_interval 管，不在此。
 */
object RuleAssets {

    val MANAGED = listOf("geoip.db", "geosite.db")

    data class Provider(
        val repoByFileName: Map<String, String>,
    ) {
        constructor(
            geoipRepo: String,
            geositeRepo: String = geoipRepo,
        ) : this(
            mapOf(
                "geoip.db" to geoipRepo,
                "geosite.db" to geositeRepo,
            ),
        )
    }

    val providers = listOf(
        Provider(
            "SagerNet/sing-geoip",
            "SagerNet/sing-geosite",
        ),
        Provider(
            "soffchen/sing-geoip",
            "soffchen/sing-geosite",
        ),
        Provider(
            "Chocolate4U/Iran-sing-box-rules",
        ),
        Provider(
            "L11R/antizapret-sing-box-geo",
        ),
    )

    enum class Outcome { UPDATED, UP_TO_DATE }

    fun assetsDir(): File = SagerNet.application.getExternalFilesDir(null) ?: SagerNet.application.filesDir

    fun versionFileOf(file: File) = File(file.parentFile, "${file.nameWithoutExtension}.version.txt")

    /**
     * release 資產該不該被抓：同名 `.db` 或 `.db.xz` 都認，其他一律不認，
     * 免得把 changelog 之類的檔案當成資料庫蓋掉路由資料。
     * 判斷抽成純函數是因為本倉的 unit test 裡 org.json 是 android.jar 的佔位實作
     * （isReturnDefaultValues=true，put() 不落地），測不出 JSON 內容。
     */
    fun matchesReleaseAsset(assetName: String?, fileName: String): Boolean =
        assetName == fileName || assetName == "$fileName.xz"

    fun pickReleaseAsset(assets: List<JSONObject>, fileName: String): JSONObject? = assets.find {
        matchesReleaseAsset(it.getStr("name"), fileName)
    }

    suspend fun updateAsset(file: File, versionFile: File, localVersion: String): Outcome {
        if (DataStore.rulesProvider == 4) {
            return updateCustomAsset(file, versionFile)
        }
        val fileName = file.name
        val repo = providers[DataStore.rulesProvider].repoByFileName[fileName]
            ?: error("unknown rule asset $fileName")

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
            var response = client.newRequest().apply {
                setURL("https://api.github.com/repos/$repo/releases/latest")
            }.execute()

            val release = JSONObject(Util.getStringBox(response.getContentStringLimited(MAX_HTTP_JSON_BYTES)))
            val tagName = release.optString("tag_name")

            if (tagName == localVersion) return Outcome.UP_TO_DATE

            val releaseAssets = release.getJSONArray("assets").filterIsInstance<JSONObject>()
            val assetToDownload = pickReleaseAsset(releaseAssets, fileName)
                ?: error("File $fileName not found in release ${release["url"]}")
            val downloadName = assetToDownload.getStr("name") ?: error("Release asset is missing a name")
            val browserDownloadUrl = assetToDownload.getStr("browser_download_url")
                ?: error("Release asset $downloadName is missing a download URL")

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            val cacheFile = File(file.parentFile, fileName + ".tmp")
            cacheFile.parentFile?.mkdirs()

            try {
                response.writeToLimited(cacheFile.canonicalPath, MAX_RULE_ASSET_BYTES)

                if (downloadName.endsWith(".xz")) {
                    val unpackedFile = File(file.parentFile, file.nameWithoutExtension + ".unxz.tmp")
                    try {
                        // Libcore.unxz enforces the same 256 MB cap (defaultUnxzFileLimit)
                        // and fails before writing if exceeded, so no extra size check here.
                        Libcore.unxz(cacheFile.absolutePath, unpackedFile.absolutePath)
                        replaceAssetFile(unpackedFile, file)
                    } finally {
                        if (unpackedFile.exists()) unpackedFile.delete()
                    }
                } else {
                    replaceAssetFile(cacheFile, file)
                }

                versionFile.writeText(tagName)
            } finally {
                if (cacheFile.exists()) cacheFile.delete()
            }
            return Outcome.UPDATED
        } finally {
            client.close()
        }
    }

    suspend fun updateCustomAsset(file: File, versionFile: File): Outcome {
        val fileName = file.name
        val url: String = if (fileName == "geoip.db") {
            DataStore.rulesGeoipUrl
        } else if (fileName == "geosite.db") {
            DataStore.rulesGeositeUrl
        } else {
            return Outcome.UP_TO_DATE
        }
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
                setURL(url)
            }.execute()
            val cacheFile = File(file.parentFile, fileName + ".tmp")
            cacheFile.parentFile?.mkdirs()
            try {
                response.writeToLimited(cacheFile.canonicalPath, MAX_RULE_ASSET_BYTES)
                replaceAssetFile(cacheFile, file)

                val currentDate = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                versionFile.writeText(currentDate)
            } finally {
                if (cacheFile.exists()) cacheFile.delete()
            }
            return Outcome.UPDATED
        } finally {
            client.close()
        }
    }

    private fun replaceAssetFile(tempFile: File, targetFile: File) {
        try {
            Os.rename(tempFile.absolutePath, targetFile.absolutePath)
        } catch (e: Exception) {
            error("Unable to save the route asset: ${e.readableMessage}")
        }
    }

    /**
     * 背景自動更新。自訂來源（providers[4]）沒有可比對的版本號，抓一次就是一整檔，
     * 所以只在檔不存在時補下載，避免每天重灌同一個資料庫。
     */
    suspend fun updateManagedAutomatically(): Int {
        val dir = assetsDir()
        var updated = 0
        for (name in MANAGED) {
            val file = File(dir, name)
            val versionFile = versionFileOf(file)
            val localVersion = if (versionFile.isFile) versionFile.readText().trim() else ""
            if (DataStore.rulesProvider == 4 && file.isFile) {
                Logs.d("rule asset: custom source without a version, keeping existing $name")
                continue
            }
            try {
                if (updateAsset(file, versionFile, localVersion) == Outcome.UPDATED) updated++
            } catch (e: Exception) {
                Logs.e("rule asset: auto-update $name failed: ${e.readableMessage}")
            }
        }
        return updated
    }
}

/**
 * 排程：每 24 小時一次，要有網路且非計費網路才跑。開關預設關，
 * 由用戶在「全域設定 → 路由資料庫自動更新」自己打開。
 */
object RuleAssetUpdater {

    private const val WORK_NAME = "RuleAssetUpdater"

    fun reconfigure() {
        val wm = RemoteWorkManager.getInstance(app)
        if (!DataStore.autoUpdateRuleAssets) {
            wm.cancelUniqueWork(WORK_NAME)
            return
        }
        wm.enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequest.Builder(RuleAssetsAutoUpdateTask::class.java, 24, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .build(),
        )
    }
}

class RuleAssetsAutoUpdateTask(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val updated = RuleAssets.updateManagedAutomatically()
        Logs.d("rule asset auto-update: $updated file(s) updated")
        Result.success()
    } catch (e: Exception) {
        Logs.e("rule asset auto-update failed: ${e.readableMessage}")
        Result.retry()
    }
}
