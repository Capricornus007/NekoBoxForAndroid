package moe.matsuri.nb4a.utils

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.os.Build
import android.webkit.WebView
import com.google.gson.GsonBuilder
import com.google.gson.ToNumberPolicy
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File
import java.io.RandomAccessFile

object JavaUtil {
    @JvmStatic
    fun handleWebviewDir(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        try {
            val processName = Application.getProcessName()
            val directories = if (Build.MANUFACTURER.contains("HUAWEI")) {
                listOf("app_webview", "app_hws_webview")
            } else {
                listOf("app_webview")
            }
            val suffixes = if (processName != BuildConfig.APPLICATION_ID) {
                val suffix = processName.ifEmpty { context.packageName }
                WebView.setDataDirectorySuffix(suffix)
                listOf("_$suffix")
            } else {
                listOf("", "_$processName")
            }
            directories.asSequence().flatMap { directory ->
                suffixes.asSequence().map { suffix ->
                    File(context.dataDir, "$directory$suffix/webview_data.lock")
                }
            }.firstOrNull { it.exists() }?.let(::tryLockOrRecreateFile)
        } catch (e: Exception) {
            Logs.e(e)
        }
    }

    private fun tryLockOrRecreateFile(file: File) {
        try {
            RandomAccessFile(file, "rw").use { access ->
                val lock = access.channel.tryLock()
                if (lock != null) {
                    lock.close()
                } else {
                    createFile(file, file.delete())
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            createFile(file, file.exists() && file.delete())
        }
    }

    private fun createFile(file: File, deleted: Boolean) {
        try {
            if (deleted && !file.exists()) file.createNewFile()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @SuppressLint("PrivateApi")
    @JvmStatic
    fun getProcessName(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) return Application.getProcessName()
        return try {
            Class.forName("android.app.ActivityThread")
                .getDeclaredMethod("currentProcessName").invoke(null) as? String
                ?: BuildConfig.APPLICATION_ID
        } catch (_: Exception) {
            BuildConfig.APPLICATION_ID
        }
    }

    @JvmField
    val gson = GsonBuilder()
        .setPrettyPrinting()
        .setNumberToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
        .setLenient()
        .disableHtmlEscaping()
        .create()
}
