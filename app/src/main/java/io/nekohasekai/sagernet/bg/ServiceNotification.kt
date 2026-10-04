package io.nekohasekai.sagernet.bg

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
import android.os.Build
import android.text.format.Formatter
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.SwitchActivity
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.utils.RegionExtractor
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * User can customize visibility of notification since Android 8.
 * The default visibility:
 *
 * Android 8.x: always visible due to system limitations
 * VPN:         always invisible because of VPN notification/icon
 * Other:       always visible
 *
 * See also: https://github.com/aosp-mirror/platform_frameworks_base/commit/070d142993403cc2c42eca808ff3fafcee220ac4
 */
class ServiceNotification(
    private val service: BaseService.Interface, title: String,
    channel: String, visible: Boolean = false,
) : BroadcastReceiver() {
    companion object {
        const val notificationId = 1
        val flags =
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0) or PendingIntent.FLAG_UPDATE_CURRENT

        fun genTitle(ent: ProxyEntity): String {
            val gn = if (DataStore.showGroupInNotification)
                runCatching { SagerDatabase.groupDao.getById(ent.groupId)?.displayName() }.getOrNull() else null
            val profileName = ent.displayName()
            return if (!gn.isNullOrBlank() && !profileName.startsWith("$gn · ")) {
                "$gn · $profileName"
            } else {
                profileName
            }
        }
    }

    var listenPostSpeed = true

    private var lastTitle: String? = null
    private var lastText: String? = null
    private var lastBigText: String? = null
    private var lastRegion: String? = null
    private var lastDirectSpeed: String? = null

    private fun applyLiveUpdateCapsule(
        builder: NotificationCompat.Builder,
        state: BaseService.State,
        region: String? = null,
        directSpeed: String? = null,
    ) {
        val color = when (state) {
            BaseService.State.Connected -> 0xFF4CAF50.toInt() // 绿色 (Green #4CAF50)
            BaseService.State.Connecting -> 0xFFFFB300.toInt() // 黄色 (Yellow #FFB300)
            else -> 0xFF9E9E9E.toInt() // 灰色 (Gray #9E9E9E)
        }
        builder.color = color

        val resolvedRegion = if (!region.isNullOrBlank()) {
            region
        } else {
            val cachedGeo = LandingIpManager.getCachedInfo()?.country
            val profile = service.data.proxy?.profile
            val leafNode = if (profile != null) ActiveOutboundTracker.getActiveLeafNodeDisplay(profile) else null
            val nodeName = leafNode ?: profile?.displayName() ?: service.data.proxy?.displayProfileName
            RegionExtractor.extractRegion(nodeName, cachedGeo).ifBlank { "已连接" }
        }

        val displayChipText = if (state == BaseService.State.Connected) {
            resolvedRegion
        } else if (state == BaseService.State.Connecting) {
            "连接中"
        } else {
            "未连接"
        }

        // 谷歌原生 Android 16 实况岛 / Rich Ongoing Notifications (Live Updates) 规范
        // 收起状态：挖孔屏旁保持显示状态颜色和地区汉字（如“日本”）
        builder.extras.apply {
            putBoolean("android.requestPromotedOngoing", true)
            putCharSequence("android.shortCriticalText", displayChipText)
            putString("capsule_text", displayChipText)
            putInt("capsule_color", color)
            putString("live_activity_status", if (state == BaseService.State.Connected) "active" else "pending")
        }

        // 展开卡片状态：将原“日本”位置（subText）替换为直连速度
        if (showDirectSpeed && state == BaseService.State.Connected && !directSpeed.isNullOrBlank()) {
            builder.setSubText("直连: $directSpeed")
        } else {
            builder.setSubText(displayChipText)
        }
    }

    suspend fun postStateUpdate(state: BaseService.State) {
        useBuilder {
            applyLiveUpdateCapsule(it, state, lastRegion, lastDirectSpeed)
        }
        update()
    }

    suspend fun postNotificationSpeedUpdate(stats: SpeedDisplayData) {
        val ctx = service as Context
        val currentProfile = service.data.proxy?.profile

        val proxySpeed = "↑${Formatter.formatFileSize(ctx, stats.txRateProxy)}/s ↓${Formatter.formatFileSize(ctx, stats.rxRateProxy)}/s"
        val directSpeed = "↑${Formatter.formatFileSize(ctx, stats.txRateDirect)}/s ↓${Formatter.formatFileSize(ctx, stats.rxRateDirect)}/s"

        val showGroup = DataStore.showGroupInNotification
        val group = if (currentProfile != null) {
            runCatching { SagerDatabase.groupDao.getById(currentProfile.groupId) }.getOrNull()
        } else null

        val leafNode = if (currentProfile != null) {
            ActiveOutboundTracker.getActiveLeafNodeDisplay(currentProfile)
        } else null
        val strategyName = if (currentProfile != null) {
            ActiveOutboundTracker.getStrategyDisplayName(currentProfile)
        } else ""

        val texts = ActiveOutboundTracker.buildNotificationTexts(
            profile = currentProfile,
            leafNode = leafNode,
            strategyName = strategyName,
            groupName = group?.displayName(),
            showGroup = showGroup,
            showDirectSpeed = showDirectSpeed,
            proxySpeed = proxySpeed,
            directSpeed = directSpeed,
        )

        val cachedGeo = LandingIpManager.getCachedInfo()?.country
        val nodeName = leafNode ?: currentProfile?.displayName() ?: service.data.proxy?.displayProfileName
        val currentRegion = RegionExtractor.extractRegion(nodeName, cachedGeo).ifBlank { "已连接" }

        val isChanged = (texts.title != lastTitle) ||
                        (texts.collapsedText != lastText) ||
                        (texts.bigText != lastBigText) ||
                        (currentRegion != lastRegion) ||
                        (directSpeed != lastDirectSpeed)
        if (!isChanged) return

        lastTitle = texts.title
        lastText = texts.collapsedText
        lastBigText = texts.bigText
        lastRegion = currentRegion
        lastDirectSpeed = directSpeed

        useBuilder {
            if (texts.title.isNotBlank()) {
                it.setContentTitle(texts.title)
            }
            it.setStyle(NotificationCompat.BigTextStyle().bigText(texts.bigText))
            it.setContentText(texts.collapsedText)
            applyLiveUpdateCapsule(it, service.data.state, currentRegion, directSpeed)
        }
        update()
    }

    suspend fun postNotificationTitle(newTitle: String) {
        if (newTitle == lastTitle) return
        lastTitle = newTitle
        useBuilder {
            it.setContentTitle(newTitle)
        }
        update()
    }

    suspend fun postNotificationWakeLockStatus(acquired: Boolean) {
        updateActions()
        useBuilder {
            it.priority =
                if (acquired) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW
        }
        update()
    }

    private val showDirectSpeed get() = DataStore.showDirectSpeed

    private val builder = NotificationCompat.Builder(service as Context, channel)
        .setWhen(0)
        .setTicker(service.getString(R.string.forward_success))
        .setContentTitle(title)
        .setOnlyAlertOnce(true)
        .setContentIntent(SagerNet.configureIntent(service))
        .setSmallIcon(R.drawable.ic_throne_tile)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
        .setOngoing(true)
        .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

    private val buildLock = Mutex()

    private suspend fun useBuilder(f: (NotificationCompat.Builder) -> Unit) {
        buildLock.withLock {
            f(builder)
        }
    }

    init {
        service as Context

        Theme.apply(app)
        Theme.apply(service)
        applyLiveUpdateCapsule(builder, service.data.state)

        service.registerReceiver(this, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        runOnMainDispatcher {
            updateActions()
            show()
        }
    }

    private suspend fun updateActions() {
        service as Context
        useBuilder {
            it.clearActions()

            val closeAction = NotificationCompat.Action.Builder(
                R.drawable.ic_service_stopped, service.getText(R.string.stop), PendingIntent.getBroadcast(
                    service, 0, Intent(Action.CLOSE).setPackage(service.packageName), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(closeAction)

            val switchAction = NotificationCompat.Action.Builder(
                R.drawable.ic_baseline_compare_arrows_24, service.getString(R.string.action_switch), PendingIntent.getActivity(
                    service, 1, Intent(service, SwitchActivity::class.java), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(switchAction)

            val resetUpstreamAction = NotificationCompat.Action.Builder(
                R.drawable.ic_baseline_refresh_24, service.getString(R.string.action_reset),
                PendingIntent.getBroadcast(
                    service, 2, Intent(Action.RESET_UPSTREAM_CONNECTIONS).setPackage(service.packageName), flags
                )
            ).setShowsUserInterface(false).build()
            it.addAction(resetUpstreamAction)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (service.data.state == BaseService.State.Connected) {
            listenPostSpeed = intent.action == Intent.ACTION_SCREEN_ON
        }
    }


    private suspend fun show() =
        useBuilder {
            try {
                if (Build.VERSION.SDK_INT >= 34) {
                    (service as Service).startForeground(
                        notificationId,
                        it.build(),
                        FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
                    )
                } else {
                    (service as Service).startForeground(notificationId, it.build())
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "startForeground: $e",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private suspend fun update() = useBuilder {
        NotificationManagerCompat.from(service as Service).notify(notificationId, it.build())
    }

    fun destroy() {
        listenPostSpeed = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            (service as Service).stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            (service as Service).stopForeground(true)
        }
        service.unregisterReceiver(this)
    }
}
