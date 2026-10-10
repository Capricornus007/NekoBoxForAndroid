package io.nekohasekai.sagernet.api

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import libcore.LocalAPIServer

class LocalApiService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var runtime: ApiRuntime
    private var server: LocalAPIServer? = null
    private var configuration: LocalApiAccess.Config? = null
    private val startLock = Mutex()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        runtime = ApiRuntime(this)
        runtime.connect()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.local_api_title), NotificationManager.IMPORTANCE_LOW))
        }
        val disable = PendingIntent.getService(this, 0, Intent(this, LocalApiService::class.java).setAction(DISABLE), PendingIntent.FLAG_IMMUTABLE)
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_service_active).setContentTitle(getString(R.string.local_api_title))
                .setContentText(getString(R.string.local_api_notification)).setContentIntent(SagerNet.configureIntent(this))
                .setOngoing(true).setOnlyAlertOnce(true).addAction(0, getString(R.string.local_api_disable), disable).build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            startLock.withLock {
                try {
                    val config = withContext(Dispatchers.IO) { LocalApiAccess.read(this@LocalApiService) }
                    if (intent?.action == DISABLE && config != null) {
                        withContext(Dispatchers.IO) { LocalApiAccess.write(this@LocalApiService, config.copy(enabled = false)) }
                        stopSelf()
                        return@launch
                    }
                    if (config?.enabled != true) {
                        stopSelf()
                        return@launch
                    }
                    if (config != configuration) {
                        server?.close()
                        server = null
                        var opened: LocalAPIServer? = null
                        try {
                            withContext(Dispatchers.IO) { opened = Libcore.startLocalAPI(config.port, config.token, AppApi(this@LocalApiService, runtime)) }
                            server = opened
                            opened = null
                            configuration = config
                        } finally {
                            opened?.close()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    Toast.makeText(this@LocalApiService, R.string.local_api_start_failed, Toast.LENGTH_LONG).show()
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        server?.close()
        server = null
        runtime.close()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "local-api"
        private const val NOTIFICATION_ID = -9091
        private const val DISABLE = "io.nekohasekai.sagernet.api.DISABLE"
    }
}
