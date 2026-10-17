package io.nekohasekai.sagernet.ktx

import libcore.Libcore
import io.nekohasekai.sagernet.database.DataStore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun print(message: String) = Libcore.nekoLogPrintln(redactDiagnostic(message))

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // level int use logrus.go

    fun d(message: String) {
        if (DataStore.logLevel in 3..4) print("[Debug] [${mkTag()}] $message")
    }

    fun d(message: String, exception: Throwable) {
        if (DataStore.logLevel in 3..4) print("[Debug] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        print("[Info] [${mkTag()}] $message")
    }

    fun i(message: String, exception: Throwable) {
        print("[Info] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        print("[Warning] [${mkTag()}] $message")
    }

    fun w(message: String, exception: Throwable) {
        print("[Warning] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        print("[Warning] [${mkTag()}] " + exception.stackTraceToString())
    }

    fun e(message: String) {
        print("[Error] [${mkTag()}] $message")
    }

    fun e(message: String, exception: Throwable) {
        print("[Error] [${mkTag()}] $message" + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        print("[Error] [${mkTag()}] " + exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}
