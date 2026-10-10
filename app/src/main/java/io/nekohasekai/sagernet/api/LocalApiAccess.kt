package io.nekohasekai.sagernet.api

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.AtomicFile
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.readBytesBounded
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.security.SecureRandom

internal object LocalApiAccess {
    const val DEFAULT_PORT = 9091
    private const val FILE_NAME = "local-api.json"
    data class Config(val enabled: Boolean, val port: Int, val token: String)
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    @Synchronized
    fun read(context: Context): Config? {
        val input = try {
            file(context).openRead()
        } catch (_: FileNotFoundException) {
            return null
        }
        return input.use { input ->
            val bytes = input.readBytesBounded(4096)
            parse(String(bytes, Charsets.UTF_8))
        }
    }

    fun parse(text: String): Config {
        val json = JSONObject(text)
        require(json.keysSet() == setOf("enabled", "port", "token")) { "Invalid API configuration" }
        val port = json.integer("port")
        val token = json.text("token")
        require(port in 1024..65535 && token.matches(Regex("[0-9a-f]{64}"))) { "Invalid API configuration" }
        return Config(json.flag("enabled"), port.toInt(), token)
    }

    fun fresh() = Config(true, DEFAULT_PORT, ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) })

    @Synchronized
    fun write(context: Context, config: Config) {
        val file = file(context)
        val text = JSONObject().put("enabled", config.enabled).put("port", config.port).put("token", config.token).toString()
        parse(text)
        val output = file.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    fun sync(context: Context) = runOnDefaultDispatcher {
        val app = context.applicationContext
        val enabled = runCatching { read(app)?.enabled == true }.getOrDefault(false)
        onMainDispatcher {
            if (enabled) {
                try {
                    ContextCompat.startForegroundService(app, Intent(app, LocalApiService::class.java))
                } catch (_: IllegalStateException) {
                    Toast.makeText(app, R.string.local_api_start_failed, Toast.LENGTH_LONG).show()
                }
            } else {
                app.stopService(Intent(app, LocalApiService::class.java))
            }
        }
    }

    fun showSettings(activity: Activity) {
        runOnDefaultDispatcher {
            val config = runCatching { read(activity) }.getOrNull()
            onMainDispatcher {
                if (activity.isFinishing || activity.isDestroyed) return@onMainDispatcher
                val dialog = MaterialAlertDialogBuilder(activity).setTitle(R.string.local_api_title)
                    .setNegativeButton(android.R.string.cancel, null)
                if (config?.enabled != true) {
                    dialog.setMessage(R.string.local_api_warning)
                        .setPositiveButton(R.string.local_api_enable) { _, _ -> saveFromUi(activity, fresh()) }
                } else {
                    dialog.setMessage(activity.getString(R.string.local_api_enabled, config.port))
                        .setPositiveButton(R.string.local_api_copy_token) { _, _ ->
                            val clip = ClipData.newPlainText(activity.getString(R.string.local_api_title), config.token)
                            if (Build.VERSION.SDK_INT >= 24) {
                                clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                            }
                            activity.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
                            Toast.makeText(activity, R.string.local_api_token_copied, Toast.LENGTH_SHORT).show()
                        }
                        .setNegativeButton(R.string.local_api_disable) { _, _ -> saveFromUi(activity, config.copy(enabled = false)) }
                        .setNeutralButton(R.string.local_api_rotate) { _, _ ->
                            MaterialAlertDialogBuilder(activity).setTitle(R.string.local_api_rotate)
                                .setMessage(R.string.local_api_rotate_warning)
                                .setNegativeButton(android.R.string.cancel, null)
                                .setPositiveButton(R.string.local_api_rotate) { _, _ -> saveFromUi(activity, fresh().copy(port = config.port)) }.show()
                        }
                }
                dialog.show()
            }
        }
    }

    internal fun saveFromUi(activity: Activity, config: Config) = runOnDefaultDispatcher {
        val app = activity.applicationContext
        val saved = runCatching { write(app, config) }.isSuccess
        if (saved) sync(app).join()
        onMainDispatcher {
            if (activity.isFinishing || activity.isDestroyed) return@onMainDispatcher
            Toast.makeText(activity, if (saved) R.string.local_api_saved else R.string.local_api_start_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
