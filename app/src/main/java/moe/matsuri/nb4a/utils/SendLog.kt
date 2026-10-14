package moe.matsuri.nb4a.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.redactDiagnostic
import io.nekohasekai.sagernet.ktx.readBytesLimited
import io.nekohasekai.sagernet.ktx.MAX_SUBSCRIPTION_BYTES
import io.nekohasekai.sagernet.utils.CrashHandler
import java.io.File
import java.io.FileInputStream
import java.io.IOException

object SendLog {
    // Create full log and send
    fun sendLog(context: Context, title: String) {
        val logFile = File.createTempFile(
            "$title ",
            ".log",
            File(app.cacheDir, "log").also { it.mkdirs() })

        var report = CrashHandler.buildReportHeader()

        report += "Logcat: \n\n"

        logFile.writeText(redactDiagnostic(report))

        try {
            val process = Runtime.getRuntime().exec(arrayOf("logcat", "-d"))
            try {
                val text = process.inputStream.use { it.readBytesLimited(MAX_SUBSCRIPTION_BYTES).toString(Charsets.UTF_8) }
                logFile.appendText(redactDiagnostic(text))
            } finally { process.destroy() }
            logFile.appendText("\n")
        } catch (e: Exception) {
            Logs.w(e)
            logFile.appendText(redactDiagnostic("Export logcat error: " + CrashHandler.formatThrowable(e)))
        }

        logFile.appendText("\n")
        logFile.appendText(redactDiagnostic(getNekoLog(MAX_SUBSCRIPTION_BYTES.toLong()).toString(Charsets.UTF_8)))

        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).setType("text/x-log")
                    .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    .putExtra(
                        Intent.EXTRA_STREAM, FileProvider.getUriForFile(
                            context, BuildConfig.APPLICATION_ID + ".cache", logFile
                        )
                    ), context.getString(R.string.abc_shareactionprovider_share_with)
            )
        )
    }

    // Get log bytes from neko.log
    fun getNekoLog(max: Long): ByteArray {
        return try {
            val file = File(
                SagerNet.application.cacheDir,
                "neko.log"
            )
            val len = file.length()
            val stream = FileInputStream(file)
            if (max in 1 until len) {
                stream.skip(len - max) // TODO string?
            }
            stream.use { it.readBytes() }
        } catch (e: Exception) {
            e.stackTraceToString().toByteArray()
        }
    }
}
