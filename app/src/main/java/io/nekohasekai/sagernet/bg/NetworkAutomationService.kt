package io.nekohasekai.sagernet.bg

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.AUTOMATION_NOTIFICATION_CHANNEL
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.NetworkAutomationActivity
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Watches the underlying network for network automation, so connect rules work with the app closed
 * and the VPN down. It runs while automation is on, a connect rule exists and no manual stop paused it,
 * and keeps running next to the VPN: Android blocks most foreground service starts from the
 * background, and a watcher that never stops never needs one. That costs a second, silent
 * notification while connected.
 *
 * The system restarts it after its process dies, as far as Android lets it start from the
 * background. Nothing brings it back after a force stop; opening the app does.
 *
 * Only the watcher itself decides to stop, after reading the settings in its own process: another
 * process may not have seen the latest pause or rules yet.
 */
class NetworkAutomationService : Service() {

    companion object {
        // ServiceNotification uses 1; other notifications use tags or ids from 1000 up.
        private const val NOTIFICATION_ID = 2

        /** Asks the service process to read the automation settings again. */
        const val ACTION_RECHECK = "io.nekohasekai.sagernet.AUTOMATION_RECHECK"

        /** Boolean extra on [ACTION_RECHECK] and on watcher starts: decide the current network anew. */
        const val EXTRA_REEVALUATE = "io.nekohasekai.sagernet.EXTRA_REEVALUATE"

        // The watcher instance in the foreground. Main thread only.
        private var live: NetworkAutomationService? = null

        /**
         * Starts the watcher when the settings this process sees want it, and otherwise asks the
         * service process to read them again: there the watcher stops itself if they agree.
         */
        fun sync(context: Context, reevaluate: Boolean = false) {
            if (!NetworkAutomation.monitorWanted()) {
                context.sendBroadcast(
                    Intent(ACTION_RECHECK).setPackage(context.packageName).putExtra(EXTRA_REEVALUATE, reevaluate),
                )
                return
            }
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, NetworkAutomationService::class.java).putExtra(EXTRA_REEVALUATE, reevaluate),
                )
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException: this process is in the background.
                Logs.w("network automation: watcher start refused", e)
                NetworkAutomation.blocked = NetworkAutomation.Blocked.WATCHER_START
            }
        }

        /** Shows the current status on the running watcher's notification, from any thread. */
        fun refresh() {
            runOnMainDispatcher { live?.showStatus() }
        }

        private fun notification(context: Context): Notification {
            val status = NetworkAutomation.blocked?.message
                ?: if (NetworkAutomation.unprotected) R.string.network_automation_name_hidden else R.string.network_automation_watching
            val text = context.getText(status)
            return NotificationCompat.Builder(context, AUTOMATION_NOTIFICATION_CHANNEL)
                .setSmallIcon(R.drawable.ic_service_active)
                .setContentTitle(context.getText(R.string.network_automation))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(
                    PendingIntent.getActivity(
                        context,
                        0,
                        Intent(context, NetworkAutomationActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setSilent(true)
                .setOngoing(true)
                .setShowWhen(false)
                .build()
        }
    }

    // Work of this instance; cancelled when it is destroyed, so none of it outlives the instance.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastStartId = 0
    private var recheckRegistered = false
    private val recheck = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            check(lastStartId, reevaluate = false, watch = false)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        // Promoted before any database work: a start through startForegroundService has a deadline.
        val promoted = try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification(this), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification(this))
            }
            // For an app restricted in the background, Android ignores the call without an exception.
            Build.VERSION.SDK_INT < 29 || foregroundServiceType != ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE
        } catch (e: RuntimeException) {
            Logs.w("network automation: watcher refused the foreground", e)
            false
        }
        if (!promoted) {
            // A restart after the process died, which Android did not let into the foreground.
            NetworkAutomation.blocked = NetworkAutomation.Blocked.WATCHER_START
            stopSelf(startId)
            return START_NOT_STICKY
        }
        live = this
        if (!recheckRegistered) {
            ContextCompat.registerReceiver(this, recheck, IntentFilter(ACTION_RECHECK), ContextCompat.RECEIVER_NOT_EXPORTED)
            recheckRegistered = true
        }
        check(startId, intent?.getBooleanExtra(EXTRA_REEVALUATE, false) == true, watch = true)
        return START_STICKY
    }

    /**
     * Reads the settings in this process, which records the pause, then keeps or stops the watcher.
     * A stop names the start it answers, so a newer start wins. [watch] (re)registers the listener,
     * which reports the current network at once.
     */
    private fun check(startId: Int, reevaluate: Boolean, watch: Boolean) = scope.launch {
        val read = runCatching { DataStore.configurationStore.refreshSuspend() }
            .onFailure { Logs.w("network automation: settings unreadable", it) }
            .isSuccess
        if (!read || !NetworkAutomation.monitorWanted()) {
            stopSelf(startId)
            return@launch
        }
        if (!watch) return@launch
        // Versions before the watcher enabled the boot receiver only for a start at boot.
        if (!BootReceiver.enabled) BootReceiver.enabled = true
        if (NetworkAutomation.blocked == NetworkAutomation.Blocked.WATCHER_START) NetworkAutomation.blocked = null
        if (reevaluate) NetworkAutomation.reevaluate()
        DefaultNetworkListener.start(this@NetworkAutomationService) { NetworkAutomation.onNetwork(it) }
        showStatus()
    }

    private fun showStatus() {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(NOTIFICATION_ID, notification(this))
        } catch (e: SecurityException) {
            Logs.w("network automation: notification update skipped", e)
        }
    }

    override fun onDestroy() {
        // None of this instance's work runs from now on, and it removes only its own listener.
        scope.cancel()
        if (live === this) {
            // Android takes the notice down with the stopped watcher, but a status update between the
            // stop and now may have posted it again. A later watcher owns the notice instead.
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
            live = null
        }
        if (recheckRegistered) unregisterReceiver(recheck)
        runOnMainDispatcher { DefaultNetworkListener.stop(this@NetworkAutomationService) }
        super.onDestroy()
    }
}
