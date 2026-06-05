package com.boristul.zybcvpn.ktx

import libcore.Libcore
import java.io.InputStream
import java.io.OutputStream

object Logs {

    private fun write(level: String, message: String) {
        val formatted = "[$level] [${mkTag()}] $message"
        runCatching {
            Libcore.nekoLogPrintln(formatted)
        }.onFailure {
            System.err.println(formatted)
        }
    }

    private fun mkTag(): String {
        val stackTrace = Thread.currentThread().stackTrace
        return stackTrace[4].className.substringAfterLast(".")
    }

    // level int use logrus.go

    fun d(message: String) {
        write("Debug", message)
    }

    fun d(message: String, exception: Throwable) {
        write("Debug", message + "\n" + exception.stackTraceToString())
    }

    fun i(message: String) {
        write("Info", message)
    }

    fun i(message: String, exception: Throwable) {
        write("Info", message + "\n" + exception.stackTraceToString())
    }

    fun w(message: String) {
        write("Warning", message)
    }

    fun w(message: String, exception: Throwable) {
        write("Warning", message + "\n" + exception.stackTraceToString())
    }

    fun w(exception: Throwable) {
        write("Warning", exception.stackTraceToString())
    }

    fun e(message: String) {
        write("Error", message)
    }

    fun e(message: String, exception: Throwable) {
        write("Error", message + "\n" + exception.stackTraceToString())
    }

    fun e(exception: Throwable) {
        write("Error", exception.stackTraceToString())
    }

}

fun InputStream.use(out: OutputStream) {
    use { input ->
        out.use { output ->
            input.copyTo(output)
        }
    }
}
