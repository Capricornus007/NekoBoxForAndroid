package io.nekohasekai.sagernet.bg

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.Notification
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import moe.matsuri.nb4a.utils.Util
import java.util.concurrent.TimeUnit

internal data class SubscriptionWorkSchedule(
    val intervalMinutes: Long,
    val initialDelaySeconds: Long,
)

internal data class SubscriptionScheduleInput(
    val lastUpdated: Int,
    val autoUpdateDelay: Int,
)

/**
 * [remindsExpiry] caps the period and the first run at a day: expiry reminders need a daily run
 * even when no subscription auto-updates or every interval is longer. The update loop still honors
 * each subscription's own interval.
 */
internal fun computeSubscriptionWorkSchedule(
    subscriptions: List<SubscriptionScheduleInput>,
    nowSeconds: Long = System.currentTimeMillis() / 1000L,
    remindsExpiry: Boolean = false,
): SubscriptionWorkSchedule? {
    if (subscriptions.isEmpty()) return if (remindsExpiry) SubscriptionWorkSchedule(24 * 60L, 0L) else null

    val intervalMinutes = subscriptions
        .minOf { it.autoUpdateDelay.toLong() }
        .coerceAtLeast(15L)
        .let { if (remindsExpiry) it.coerceAtMost(24 * 60L) else it }
    val initialDelaySeconds = subscriptions.minOf { subscription ->
        val dueAt = subscription.lastUpdated.toLong() +
            subscription.autoUpdateDelay.toLong().coerceAtLeast(15L) * 60L
        dueAt - nowSeconds
    }.coerceAtLeast(0L)

    return SubscriptionWorkSchedule(
        intervalMinutes,
        if (remindsExpiry) initialDelaySeconds.coerceAtMost(24 * 3600L) else initialDelaySeconds,
    )
}

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"
    private const val EXPIRY_NOTIFICATION_ID_BASE = 1000
    private const val EXPIRY_WARNING_SECONDS = 3 * 24 * 3600L
    private const val DAY_SECONDS = 24 * 3600L

    /**
     * Reminds about a subscription whose `expire=` is within three days, once a day, and once more
     * after it passed. Runs from the periodic worker and after every successful update.
     */
    fun notifyExpiry(group: ProxyGroup, nowSeconds: Long = System.currentTimeMillis() / 1000L) {
        val subscription = group.subscription ?: return
        val expiry = subscription.expiry() ?: return
        if (!expiryReminderDue(expiry, subscription.expiryNotifiedAt ?: 0, nowSeconds)) return
        val context = app
        val text = if (nowSeconds < expiry) {
            context.getString(R.string.subscription_expiring_message, group.displayName(), Util.timeStamp2Text(expiry * 1000))
        } else {
            context.getString(R.string.subscription_expired_message, group.displayName())
        }
        val notification = NotificationCompat.Builder(context, "service-subscription")
            .setContentTitle(context.getString(R.string.subscription_expiring_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_service_active)
            .setContentIntent(SagerNet.configureIntent(context))
            .setAutoCancel(true)
            .build()
        if (!NotificationManagerCompat.from(context).post((EXPIRY_NOTIFICATION_ID_BASE + group.id).toInt(), notification)) return
        subscription.expiryNotifiedAt = nowSeconds.toInt()
        // Write the stamp onto the current row so a concurrent group edit or update is kept.
        val stored = SagerDatabase.groupDao.getById(group.id) ?: return
        stored.subscription?.expiryNotifiedAt = nowSeconds.toInt()
        SagerDatabase.groupDao.updateGroup(stored)
    }

    internal fun expiryReminderDue(expiry: Long, notifiedAt: Int, nowSeconds: Long): Boolean = when {
        // One reminder after expiry, whenever the worker next runs.
        nowSeconds >= expiry -> notifiedAt < expiry

        expiry - nowSeconds <= EXPIRY_WARNING_SECONDS -> nowSeconds - notifiedAt >= DAY_SECONDS

        else -> false
    }

    /** Posts when notifications are allowed; false when the permission or channel blocks it. */
    private fun NotificationManagerCompat.post(id: Int, notification: Notification): Boolean {
        if (!areNotificationsEnabled()) return false
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(app, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            notify(id, notification)
            true
        } catch (e: SecurityException) {
            Logs.w("notification skipped", e)
            false
        }
    }

    suspend fun reconfigureUpdater() {
        RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)

        val all = SagerDatabase.groupDao.subscriptions().mapNotNull { it.subscription }
        val subscriptions = all.filter { it.autoUpdate!! }

        val schedule = computeSubscriptionWorkSchedule(
            subscriptions.map { sub ->
                SubscriptionScheduleInput(
                    lastUpdated = sub.lastUpdated ?: 0,
                    autoUpdateDelay = sub.autoUpdateDelay ?: 1440,
                )
            },
            remindsExpiry = all.any { it.expiry() != null },
        ) ?: return

        // main process
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, schedule.intervalMinutes, TimeUnit.MINUTES)
                .apply {
                    if (schedule.initialDelaySeconds > 0) {
                        setInitialDelay(schedule.initialDelaySeconds, TimeUnit.SECONDS)
                    }
                }
                .build(),
        )
    }

    class UpdateTask(
        appContext: Context,
        params: WorkerParameters,
    ) : CoroutineWorker(appContext, params) {

        private val nm = NotificationManagerCompat.from(applicationContext)

        private val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions =
                SagerDatabase.groupDao.subscriptions()
                    .mapNotNull { group -> group.subscription?.let { group to it } }
                    .filter { (_, sub) -> sub.autoUpdate!! }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { (_, sub) -> !sub.updateWhenConnectedOnly!! }
            }

            var attempted = false
            var failed = false
            if (subscriptions.isNotEmpty()) {
                val nowSeconds = System.currentTimeMillis() / 1000L
                for ((profile, subscription) in subscriptions) {
                    val lastUpdated = (subscription.lastUpdated ?: 0).toLong()
                    val delaySeconds =
                        (subscription.autoUpdateDelay ?: 1440).toLong().coerceAtLeast(15L) * 60L
                    if (nowSeconds - lastUpdated < delaySeconds) {
                        Logs.d("work: not updating " + profile.displayName())
                        continue
                    }
                    Logs.d("work: updating " + profile.displayName())

                    notification.setContentText(
                        applicationContext.getString(
                            R.string.subscription_update_message,
                            profile.displayName(),
                        ),
                    )
                    notifyProgress()

                    attempted = true
                    if (!GroupUpdater.executeUpdate(profile, false)) {
                        failed = true
                    }
                }
            }

            try {
                nm.cancel(2)
            } catch (e: SecurityException) {
                Logs.w("subscription notification cancel skipped", e)
            }

            // Expiry reminders cover every subscription, including ones that never auto-update.
            SagerDatabase.groupDao.subscriptions().forEach { notifyExpiry(it) }

            return if (attempted && failed) Result.retry() else Result.success()
        }

        private fun notifyProgress() {
            nm.post(2, notification.build())
        }
    }
}
