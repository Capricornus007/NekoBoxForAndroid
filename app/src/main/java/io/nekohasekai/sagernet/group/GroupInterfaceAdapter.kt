package io.nekohasekai.sagernet.group

import android.app.Dialog
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ui.ThemedActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/**
 * Shows the dialog [build] creates on [activity] and waits until it closes. [build] receives a
 * callback that records the answer; the result is [dismissed] when the dialog closes without one,
 * when the activity is gone or goes away, or when building it fails. The activity is referenced
 * only while the dialog shows.
 */
internal suspend fun <T> awaitDialog(
    activity: WeakReference<out ComponentActivity>,
    dismissed: T,
    build: (ComponentActivity, answer: (T) -> Unit) -> Dialog,
): T = withContext(Dispatchers.Main.immediate) {
    val host = activity.get()?.takeUnless { it.isFinishing || it.isDestroyed } ?: return@withContext dismissed
    suspendCancellableCoroutine { continuation ->
        var result = dismissed
        val dialog = try {
            build(host) { result = it }
        } catch (e: Exception) {
            Logs.w(e)
            continuation.resume(dismissed)
            return@suspendCancellableCoroutine
        }
        // Destroying the activity removes the dialog's window without dismissing it.
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) dialog.dismiss()
        }
        dialog.setOnDismissListener {
            host.lifecycle.removeObserver(observer)
            if (continuation.isActive) continuation.resume(result)
        }
        host.lifecycle.addObserver(observer)
        continuation.invokeOnCancellation { host.runOnUiThread { dialog.dismiss() } }
        try {
            dialog.show()
        } catch (e: Exception) {
            Logs.w(e)
            host.lifecycle.removeObserver(observer)
            if (continuation.isActive) continuation.resume(dismissed)
        }
    }
}

class GroupInterfaceAdapter(activity: ThemedActivity) : GroupManager.Interface {

    // A pending update or dialog must not keep a finished activity alive.
    private val activity = WeakReference(activity)

    override suspend fun confirm(message: String): Boolean = awaitDialog(activity, false) { context, answer ->
        MaterialAlertDialogBuilder(context).setTitle(R.string.confirm)
            .setMessage(message)
            .setPositiveButton(R.string.yes) { _, _ -> answer(true) }
            .setNegativeButton(R.string.no, null)
            .create()
    }

    override suspend fun alert(message: String) = awaitDialog(activity, Unit) { context, _ ->
        MaterialAlertDialogBuilder(context).setTitle(R.string.ooc_warning)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok, null)
            .create()
    }

    override suspend fun onUpdateSuccess(
        group: ProxyGroup,
        changed: Int,
        added: List<String>,
        updated: Map<String, String>,
        deleted: List<String>,
        duplicate: List<String>,
        byUser: Boolean,
    ) {
        val context = activity.get() ?: return
        if (changed == 0 && duplicate.isEmpty()) {
            if (byUser) {
                onMainDispatcher {
                    if (context.isFinishing || context.isDestroyed) return@onMainDispatcher
                    try {
                        context.snackbar(
                            context.getString(
                                R.string.group_no_difference,
                                group.displayName(),
                            ),
                        ).show()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Logs.w(e)
                    }
                }
            }
        } else {
            var status = ""
            if (added.isNotEmpty()) {
                status += context.getString(
                    R.string.group_added,
                    added.joinToString("\n", postfix = "\n\n"),
                )
            }
            if (updated.isNotEmpty()) {
                status += context.getString(
                    R.string.group_changed,
                    updated.map { it }.joinToString("\n", postfix = "\n\n") {
                        if (it.key == it.value) it.key else "${it.key} => ${it.value}"
                    },
                )
            }
            if (deleted.isNotEmpty()) {
                status += context.getString(
                    R.string.group_deleted,
                    deleted.joinToString("\n", postfix = "\n\n"),
                )
            }
            if (duplicate.isNotEmpty()) {
                status += context.getString(
                    R.string.group_duplicate,
                    duplicate.joinToString("\n", postfix = "\n\n"),
                )
            }

            onMainDispatcher {
                if (context.isFinishing || context.isDestroyed) return@onMainDispatcher
                try {
                    context.snackbar(
                        context.getString(R.string.group_updated, group.name, changed),
                    ).show()
                    delay(1000L)
                    if (context.isFinishing || context.isDestroyed) return@onMainDispatcher

                    MaterialAlertDialogBuilder(context).setTitle(
                        context.getString(
                            R.string.group_diff,
                            group.displayName(),
                        ),
                    ).setMessage(status.trim()).setPositiveButton(android.R.string.ok, null).show()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                }
            }
        }
    }

    override suspend fun onUpdateFailure(group: ProxyGroup, message: String) {
        val context = activity.get() ?: return
        onMainDispatcher {
            if (context.isFinishing || context.isDestroyed) return@onMainDispatcher
            try {
                context.snackbar(message).show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logs.w(e)
            }
        }
    }
}
