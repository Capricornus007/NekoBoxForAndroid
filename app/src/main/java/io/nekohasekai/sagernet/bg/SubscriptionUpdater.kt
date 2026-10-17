package io.nekohasekai.sagernet.bg

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy.UPDATE
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkerParameters
import androidx.work.multiprocess.RemoteWorkManager
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import java.util.concurrent.TimeUnit

object SubscriptionUpdater {

    private const val WORK_NAME = "SubscriptionUpdater"

    suspend fun reconfigureUpdater() {

        val subscriptions = SagerDatabase.groupDao.subscriptions()
            .filter { it.subscription!!.autoUpdate }
        if (subscriptions.isEmpty()) {
            RemoteWorkManager.getInstance(app).cancelUniqueWork(WORK_NAME)
            return
        }

        val minDelay = subscriptions.minOf { maxOf(15L, it.subscription!!.autoUpdateDelay.toLong()) }
        val now = System.currentTimeMillis() / 1000L
        val minInitDelay = subscriptions.minOf {
            subscriptionDelaySeconds(now, it.subscription!!.lastUpdated.toLong(), it.subscription!!.autoUpdateDelay)
        }

        // main process
        RemoteWorkManager.getInstance(app).enqueueUniquePeriodicWork(
            WORK_NAME,
            UPDATE,
            PeriodicWorkRequest.Builder(UpdateTask::class.java, minDelay, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .apply {
                    if (minInitDelay > 0) setInitialDelay(minInitDelay, TimeUnit.SECONDS)
                }
                .build()
        )
    }

    class UpdateTask(
        appContext: Context, params: WorkerParameters
    ) : CoroutineWorker(appContext, params) {

        val nm = NotificationManagerCompat.from(applicationContext)

        val notification = NotificationCompat.Builder(applicationContext, "service-subscription")
            .setWhen(0)
            .setTicker(applicationContext.getString(R.string.forward_success))
            .setContentTitle(applicationContext.getString(R.string.subscription_update))
            .setSmallIcon(R.drawable.ic_service_active)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)

        override suspend fun doWork(): Result {
            var subscriptions =
                SagerDatabase.groupDao.subscriptions().filter { it.subscription!!.autoUpdate }
            if (!DataStore.serviceState.connected) {
                Logs.d("work: not connected")
                subscriptions = subscriptions.filter { !it.subscription!!.updateWhenConnectedOnly }
            }

            var failed = false
            try {
            for (profile in subscriptions) {
                val subscription = profile.subscription!!

                if (subscriptionDelaySeconds(System.currentTimeMillis() / 1000, subscription.lastUpdated.toLong(), subscription.autoUpdateDelay) > 0) {
                    Logs.d("work: not updating " + profile.displayName())
                    continue
                }
                Logs.d("work: updating " + profile.displayName())

                notification.setContentText(
                    applicationContext.getString(
                        R.string.subscription_update_message, profile.displayName()
                    )
                )
                nm.notify(2, notification.build())

                try {
                    if (!GroupUpdater.executeUpdate(profile, false)) failed = true
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    Logs.w(failure)
                    failed = true
                }
            }

            } finally { nm.cancel(2) }
            return if (failed && runAttemptCount < 3) Result.retry() else if (failed) Result.failure() else Result.success()
        }
    }

}