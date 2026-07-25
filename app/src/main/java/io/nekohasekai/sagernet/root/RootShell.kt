package io.nekohasekai.sagernet.root

import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.TimeUnit

/**
 * Minimal root command runner backed by the `su` binary.
 */
object RootShell {

    data class Result(val code: Int, val output: String) {
        val success: Boolean get() = code == 0
    }

    fun exec(command: String, timeoutSeconds: Long = 30): Result {
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                Logs.e("RootShell: timed out: $command")
                Result(-1, output)
            } else {
                val result = Result(process.exitValue(), output)
                if (!result.success) {
                    Logs.w("RootShell: '$command' exited ${result.code}: ${output.trim()}")
                }
                result
            }
        } catch (e: Exception) {
            Logs.e("RootShell: failed to run '$command'", e)
            Result(-1, e.message ?: e.javaClass.simpleName)
        }
    }
}
