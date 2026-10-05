package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal interface TailscaleExitChange {
    fun savedValue(): String
    fun commit()
    fun rollback()
}

internal data class TailscaleExitResult(val outcome: String, val savedExit: String, val errorCode: String = "")

/** Native methods have their own deadlines; cancellation must never detach a JNI finalizer. */
internal suspend fun finalizeTailscaleExit(
    oldExit: String,
    begin: () -> TailscaleExitChange,
    save: (String) -> Unit,
    applied: (Boolean) -> Unit,
    rolledBack: () -> Unit,
    completed: (TailscaleExitResult) -> Unit = {},
): TailscaleExitResult = withContext(NonCancellable) {
    withTimeout(30_000) {
        val change = try {
            begin()
        } catch (e: Exception) {
            val outcome = if (e.message?.startsWith("tailscale:diverged:") == true) {
                TailscaleExitResult("diverged", oldExit, "tailscale:apply-diverged")
            } else {
                TailscaleExitResult("failed-unchanged", oldExit, "tailscale:apply-failed")
            }
            return@withTimeout outcome.also(completed)
        }
        val saved = change.savedValue()
        applied(saved.isNotEmpty())
        try {
            save(saved)
        } catch (e: Exception) {
            return@withTimeout (
                try {
                    change.rollback()
                    rolledBack()
                    TailscaleExitResult(
                        if (e.message == "tailscale:conflict") "conflict" else "failed-rolled-back",
                        oldExit,
                        if (e.message == "tailscale:conflict") "tailscale:conflict" else "tailscale:save-failed",
                    )
                } catch (_: Exception) {
                    TailscaleExitResult("diverged", oldExit, "tailscale:rollback-failed")
                }
                ).also(completed)
        }
        val outcome = try {
            change.commit()
            TailscaleExitResult("applied-and-saved", saved)
        } catch (_: Exception) {
            // The DB is authoritative after this point. Never undo a durable or newer choice.
            TailscaleExitResult("diverged", saved, "tailscale:commit-failed")
        }
        // Publish while still shielded, before cancellation can discard the return value.
        outcome.also(completed)
    }
}
