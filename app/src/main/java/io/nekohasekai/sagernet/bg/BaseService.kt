package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.Toast
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.root.RootLanSharing
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

private const val NETWORK_CHANGE_RESTART_DEBOUNCE_MS = 500L

// 超時自動切換的節流參數。以前这条监控没有任何退避：节点全挂时每隔几秒就把整组
// 代理逐个 reload 一遍，一个 60 节点的分组一轮就是几十次核心重建，手机发烫、耗电，
// 而且探测跑在 binder 的 Main 作用域上，每次 3 秒的阻塞 native 调用都会冻住界面。
private const val AUTO_SWITCH_PROBE_TIMEOUT_MS = 3000L
private const val AUTO_SWITCH_PROBE_SLACK_MS = 2000L
private const val AUTO_SWITCH_RELOAD_SETTLE_MS = 500L
private const val AUTO_SWITCH_MAX_PROBES_PER_ROUND = 5
private const val AUTO_SWITCH_MAX_BACKOFF_SECONDS = 300L

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle,
        Connecting(true, true, false),
        Connected(true, true, true),
        Stopping,
        Stopped,
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var timeoutMonitorJob: Job? = null

        // 超時自動切換的輪詢游標：記住上一輪試到第幾個，下一輪從這裡接續，
        // 不必每次都把整組從頭 reload 一遍。-1 表示還沒開始或已掃完整圈。
        var timeoutSwitchCursor = -1

        val receiver = broadcastReceiverWithSelf { self, ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats(self)
                Action.RELOAD -> service.reload(
                    intent.getLongExtra(Action.EXTRA_PROFILE_ID, -1L),
                )
                // Ported from own/54c76ba71 (LAN sharing group): a reload keeps the tunnel
                // object alive, so changes that need a brand new core (e.g. exposing the
                // inbound to the LAN) use this hard restart instead.
                Action.RESTART -> {
                    Logs.i("BaseService: Action.RESTART received, forcing stopRunner(restart = true)")
                    service.stopRunner(restart = true)
                }
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    // Only act once fully Connected: the close receiver is now registered during
                    // Connecting (so stop/reload aren't lost), but proxy.box is a lateinit that
                    // isn't built until proxy.init() finishes, so sleep()/wake() here during
                    // startup would throw UninitializedPropertyAccessException.
                    if (state == State.Connected) {
                        if (SagerNet.power.isDeviceIdleMode) {
                            proxy?.box?.sleep()
                        } else {
                            proxy?.box?.wake()
                            if (DataStore.wakeResetConnections) {
                                Libcore.resetAllConnections(true)
                            }
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    Libcore.resetAllConnections(true)
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, R.string.reset_connections_done, Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> service.stopRunner()
            }
        }
        var closeReceiverRegistered = false
        var networkRestartJob: Job? = null

        val binder = Binder(this)
        var connectingJob: Job? = null

        // The stop/reload decision core (pendingRestart + stopGeneration); see ServiceStopGate
        // for the invariants and threading contract.
        val stopGate = ServiceStopGate()

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            if (s == State.Stopping || s == State.Stopped) {
                stopGate.onEnterStopState()
            }
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) :
        ISagerNetService.Stub(),
        CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = ConcurrentHashMap<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String =
            data?.proxy?.displayProfileName ?: SagerNet.application.getString(R.string.idle)

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            if (!callbackIdMap.containsKey(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun resetTraffic(profileIds: LongArray) {
            launch(Dispatchers.Default) {
                data?.proxy?.looper?.resetTraffic(profileIds)
            }
        }

        override fun urlTest(): Int {
            val box = data?.proxy?.box ?: error("core not started")
            return try {
                Libcore.urlTest(
                    box,
                    DataStore.connectionTestURL,
                    DataStore.connectionTestTimeout,
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            callbacks.kill()
            coroutineContext[Job]?.cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun ensureForegroundNotification(profileName: String): ServiceNotification =
            data.notification ?: createNotification(profileName).also { data.notification = it }

        fun onBind(intent: Intent): IBinder? = if (intent.action == Action.SERVICE) data.binder else null

        fun reload(profileId: Long = -1L) {
            // Run off the main thread: refreshSuspend() does a DB read (PublicDatabase no longer
            // allows main-thread queries) and the selectedProxy/getById lookups below read the
            // config store + profile DB. The IPC-carried profileId (when >= 0) is authoritative
            // for the freshly-selected profile so we don't depend on the UI's async write-through
            // DB commit having landed yet.
            // Capture the stop generation now (on the receiver thread) so a stop that races the
            // async refresh below makes reloadInner() a no-op instead of reviving a stopped
            // service. This does NOT trip on Connecting->Connected progress, so a reload issued
            // while connecting still applies.
            val reloadStopGeneration = data.stopGate.captureGeneration()
            runOnDefaultDispatcher {
                try {
                    DataStore.configurationStore.refreshSuspend()
                    // Apply the IPC-carried selection authoritatively: 0L means "no profile"
                    // (explicit empty selection), a positive id must resolve to a real profile;
                    // a negative/absent id leaves selectedProxy as the refreshed snapshot value.
                    when {
                        profileId == 0L -> DataStore.selectedProxy = 0L
                        profileId > 0L && SagerDatabase.proxyDao.getById(profileId) != null ->
                            DataStore.selectedProxy = profileId
                    }
                    // 切換節點一律走完整 stop→start，不再走 in-place selector 快速路徑：
                    // 使用者要求「切換之後就得斷開再連接，不管上一個在測速還是在幹什麼」
                    // （2026-09-29 明示）。那條快速路徑既不重建核心、也不重置舊節點上
                    // 已經建立的連線，切了等於沒切。（NodeSwitchContractTest 用 grep 守這條，
                    // 所以這裡不要把那個 API 的名字寫回來。）
                    onMainDispatcher { reloadInner(reloadStopGeneration) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        stopRunner(
                            false,
                            "${(this@Interface as Context).getString(R.string.service_failed)}: ${e.readableMessage}",
                        )
                    }
                }
            }
        }

        private fun reloadInner(reloadStopGeneration: Long) {
            // A stop raced the async refresh: drop this stale reload so it can't restart a service
            // the user stopped. (A Connecting->Connected transition does NOT bump stopGeneration,
            // so an in-flight legitimate reload still applies.)
            if (data.stopGate.isStale(reloadStopGeneration)) return
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            // 這裡以前有一條「切換器群組只叫核心的換出口 API、不重建核心」的 in-place
            // 快速路徑，已依使用者要求移除：那條路徑不重建核心、也不重置舊節點上已建立的
            // 連線，換完節點常常還是走舊出口。現在切換一律 stopRunner(true) → startRunner()。
            // （NodeSwitchContractTest 用 grep 守這條，註解裡不要寫回那個 API 名字。）
            when (val s = data.state) {
                State.Stopped -> startRunner()
                else -> if (s.canStop) stopRunner(true) else Logs.w("Illegal state $s when invoking use")
            }
        }

        fun startTimeoutMonitor() {
            if (!DataStore.enableAutoSwitchTimeout) return

            data.timeoutMonitorJob?.cancel()
            // binder 的作用域是 Dispatchers.Main.immediate，探测是阻塞 native 呼叫，
            // 留在上面會每輪凍住界面 3 秒，所以整段監控改掛到 IO。
            data.timeoutMonitorJob = data.binder.launch(Dispatchers.IO) {
                var consecutiveFailures = 0
                while (isActive) {
                    val baseSeconds =
                        DataStore.autoSwitchTimeoutDuration.toLong().takeIf { it > 0 } ?: 10L
                    val waitSeconds = backoffSeconds(baseSeconds, consecutiveFailures)
                    delay(waitSeconds * 1000L)

                    if (!isActive) break

                    // 检查连接是否存活
                    if (isConnectionAlive()) {
                        if (consecutiveFailures > 0) {
                            Logs.d("超时监控：连接恢复，退避重置为 ${baseSeconds}s。")
                        }
                        consecutiveFailures = 0
                        continue // 连接存活，进入下次循环
                    }

                    // 未存活，切换到下一个可用代理并测试
                    val found = switchToNextAvailableProxy()
                    if (found) {
                        consecutiveFailures = 0
                    } else {
                        consecutiveFailures++
                        Logs.d(
                            "超时监控：本轮没有可用代理，连续失败 $consecutiveFailures 次，" +
                                "下次间隔 ${backoffSeconds(baseSeconds, consecutiveFailures)}s。",
                        )
                    }
                    // 切换可用代理后，监控继续（回到循环起点）
                }
            }
        }

        /**
         * 失敗次數越多等越久：base、2*base、4*base… 上限 [AUTO_SWITCH_MAX_BACKOFF_SECONDS]。
         * 目的是死節點不再被無間隔重試——以前是幾秒一趟整組 reload，手機直接發燙。
         */
        private fun backoffSeconds(baseSeconds: Long, consecutiveFailures: Int): Long {
            if (consecutiveFailures <= 0) return baseSeconds
            var factor = 1L
            repeat(consecutiveFailures.coerceAtMost(16)) { factor *= 2L }
            return (baseSeconds * factor).coerceAtMost(AUTO_SWITCH_MAX_BACKOFF_SECONDS)
        }

        private suspend fun isConnectionAlive(): Boolean {
            val box = data.proxy?.box ?: return false
            return try {
                // 外層包一層比 native 更宽的超时：native 自己有 3s，但取消不了它的話
                // 至少不會把這條監控永久卡死在一次呼叫上。
                val result = withTimeoutOrNull(AUTO_SWITCH_PROBE_TIMEOUT_MS + AUTO_SWITCH_PROBE_SLACK_MS) {
                    Libcore.urlTest(
                        box,
                        DataStore.connectionTestURL,
                        AUTO_SWITCH_PROBE_TIMEOUT_MS.toInt(),
                    )
                }
                result?.let { it > 0 } ?: false
            } catch (e: Exception) {
                Logs.d("Timeout check error: ${e.readableMessage}")
                false
            }
        }

        /**
         * 從上次的游標往後試，每輪最多 [AUTO_SWITCH_MAX_PROBES_PER_ROUND] 個，
         * 找到可用就返回 true。整組掃完都不通才返回 false（游標保留，下一輪繼續）。
         * 如果代理数量小于等于1，则不做测试直接返回 false。
         */
        private suspend fun switchToNextAvailableProxy(): Boolean {
            val currentProfile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            val groupProxies = SagerDatabase.proxyDao.getByGroup(currentProfile.groupId)

            if (groupProxies.size <= 1) return false

            val proxyCount = groupProxies.size
            var nextIndex = data.timeoutSwitchCursor.takeIf { it in 0 until proxyCount }
                ?: groupProxies.indexOfFirst { it.id == currentProfile.id }
            if (nextIndex < 0) nextIndex = 0

            var tried = 0
            while (tried < proxyCount && tried < AUTO_SWITCH_MAX_PROBES_PER_ROUND) {
                tried++
                nextIndex = (nextIndex + 1) % proxyCount
                data.timeoutSwitchCursor = nextIndex
                val candidate = groupProxies[nextIndex]
                DataStore.selectedProxy = candidate.id
                // reload() 自己會派發到 Default，不需要再繞 Main；繞 Main 只是多一次排程。
                reload()
                delay(AUTO_SWITCH_RELOAD_SETTLE_MS) // 等待 reload 生效

                if (isConnectionAlive()) {
                    Logs.d("切换到可用代理: ${candidate.id}")
                    return true
                } else {
                    Logs.d("代理不可用: ${candidate.id}，本轮已试 $tried/$proxyCount 个。")
                }
            }

            Logs.d("本轮试过 $tried 个代理均不可用，下一轮从游标 ${data.timeoutSwitchCursor} 继续。")
            return false
        }

        fun switchToNextProxy() {
            val currentProfile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return
            val groupProxies = SagerDatabase.proxyDao.getByGroup(currentProfile.groupId)

            if (groupProxies.isEmpty()) return

            val currentIndex = groupProxies.indexOfFirst { it.id == currentProfile.id }
            val nextIndex = if (currentIndex >= 0 && currentIndex < groupProxies.size - 1) {
                currentIndex + 1
            } else {
                0
            }

            if (nextIndex < groupProxies.size) {
                DataStore.selectedProxy = groupProxies[nextIndex].id
                runOnMainDispatcher {
                    reload()
                }
            }
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
            data.proxy!!.awaitExternalProcessesReady()
        }

        fun startRunner() {
            this as Context
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(Intent(this, javaClass))
            } else {
                startService(Intent(this, javaClass))
            }
        }

        suspend fun killProcesses() {
            data.timeoutMonitorJob?.cancel()
            data.timeoutMonitorJob = null
            runServiceTeardown(
                after = {
                    wakeLock?.apply {
                        release()
                        wakeLock = null
                    }
                    DefaultNetworkListener.stop(this@Interface)
                },
            ) {
                data.proxy?.closeAndPersist()

                runCatching {
                    Libcore.resetAllConnections(true)
                }
                runCatching {
                    Libcore.forceGc()
                }
            }
        }
        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            data.networkRestartJob?.cancel()
            data.networkRestartJob = null
            DataStore.baseService = null
            DataStore.vpnService = null
            DataStore.mixedInboundAuthed = false

            // A teardown already in progress merges this request (explicit stop cancels a
            // pending restart; see ServiceStopGate.onStopRequested) and we must return.
            if (data.stopGate.onStopRequested(restart, data.state == State.Stopping)) return
            data.notification?.destroy()
            data.notification = null
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    killProcesses()
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.proxy = null
                }

                // change the state
                data.changeState(State.Stopped, msg)
                // stop the service if nothing has bound to it. Re-read pendingRestart: an explicit
                // CLOSE that raced this teardown may have cleared it.
                if (data.stopGate.consumeRestart()) {
                    startRunner()
                } else {
                    // A startForegroundService request may have recreated the notification while
                    // teardown was in flight. If a later explicit CLOSE cancelled that restart,
                    // release the replacement notification and its screen-state receiver too.
                    data.notification?.destroy()
                    data.notification = null
                    stopSelf()
                }
            }
        }

        fun persistStats(receiver: BroadcastReceiver) {
            // Flush the final per-profile cumulative rx/tx so a hard ACTION_SHUTDOWN doesn't
            // drop the last session's bytes (normal teardown already persists via
            // TrafficLooper.stop()). Per-profile cumulative traffic reuses the existing
            // ProxyEntity.tx/rx columns - no new table / migration (see Plan 024 findings).
            //
            // Use goAsync() instead of blocking onReceive (the main thread) with runBlocking:
            // the system keeps the process alive until pending.finish(), so the flush still
            // lands on a hard shutdown without parking the main thread (~5s) near the ANR limit.
            // Snapshot the looper on the receiver thread so the async block can't race a
            // concurrent teardown that nulls data.proxy.
            val looper = data.proxy?.looper ?: return
            val pending = receiver.goAsync()
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    withTimeoutOrNull(5_000) { looper.persist() }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                } finally {
                    pending.finish()
                }
            }
        }

        // networks
        var upstreamInterfaceName: String?

        fun scheduleNetworkProfileRestart(oldName: String, newName: String) {
            data.networkRestartJob?.cancel()
            data.networkRestartJob = runOnMainDispatcher {
                delay(NETWORK_CHANGE_RESTART_DEBOUNCE_MS)
                data.networkRestartJob = null
                if (DataStore.baseService !== this@Interface) return@runOnMainDispatcher
                if (data.state != State.Connecting && data.state != State.Connected) {
                    return@runOnMainDispatcher
                }
                Logs.d("Restarting profile after network change: $oldName -> $newName")
                stopRunner(restart = true)
            }
        }

        suspend fun preInit() {
            DefaultNetworkListener.start(this) { network ->
                if (DataStore.baseService !== this@Interface) return@start
                SagerNet.underlyingNetwork = network
                DataStore.vpnService?.updateUnderlyingNetwork()
                val link = network?.let { SagerNet.connectivity.getLinkProperties(it) }
                    ?: return@start
                val oldName = upstreamInterfaceName
                val newName = link.interfaceName ?: return@start
                upstreamInterfaceName = newName
                when (
                    networkChangeAction(
                        oldName,
                        newName,
                        DataStore.restartProfileOnNetworkChange,
                        DataStore.networkChangeResetConnections,
                    )
                ) {
                    NetworkChangeAction.RESTART_PROFILE -> {
                        Logs.d("Network changed: $oldName -> $newName")
                        scheduleNetworkProfileRestart(requireNotNull(oldName), newName)
                    }

                    NetworkChangeAction.RESET_CONNECTIONS -> {
                        Logs.d("Network changed: $oldName -> $newName")
                        Libcore.resetAllConnections(true)
                    }

                    NetworkChangeAction.NONE -> Unit
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            Logs.d("BaseService.lateInit: this is VpnService=${this is VpnService}")
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }

            // Start root-based LAN sharing (hotspot/USB tethering)
            if (this is VpnService) {
                val context = this.applicationContext
                runOnDefaultDispatcher {
                    RootLanSharing.startClientSharing(context)
                }
            }
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            this as Context
            if (data.state != State.Stopped) {
                // startForegroundService() can race asynchronous teardown. Even when this start
                // cannot proceed immediately, it still has to promote the service before the
                // platform deadline and preserve the new request for after teardown.
                ensureForegroundNotification(
                    data.proxy?.displayProfileName ?: getString(R.string.app_name),
                )
                if (data.state == State.Stopping) {
                    data.stopGate.onStartRequestedDuringStop()
                }
                return Service.START_NOT_STICKY
            }

            // Do not spend the foreground-service promotion window on configuration refreshes or
            // database reads. The generic title is replaced after the selected profile resolves.
            ensureForegroundNotification(getString(R.string.app_name))

            // The IPC-carried profile id (when >= 0) is authoritative for a cold start triggered
            // right after a UI profile selection, so :bg does not depend on the UI's async
            // write-through DB commit having landed. -1 / absent => read selectedProxy from store.
            val ipcProfileId = intent?.getLongExtra(Action.EXTRA_PROFILE_ID, -1L) ?: -1L

            data.changeState(State.Connecting)
            // Register the CLOSE/RELOAD/SHUTDOWN receiver SYNCHRONOUSLY here, before the async
            // refresh below, so a stop/reload broadcast issued during the cold-start window is
            // delivered rather than lost (the receiver used to be registered only after the
            // off-main work, opening a drop window).
            registerCloseReceiver()
            // Read config off-main first (PublicDatabase no longer allows main-thread queries),
            // then run the existing connect logic on the main dispatcher. onStartCommand returns
            // synchronously; the null-profile short-circuit now lives inside the job.
            // Track the connect coroutine so stopRunner()/reload() can cancel an in-flight
            // start. Without this, data.connectingJob stays null and stopRunner's
            // cancelAndJoin() is a no-op: a superseded start's awaitExternalProcessesReady()
            // keeps polling a now-killed sidecar port for its full (60s for MasterDnsVPN)
            // window and then throws "sidecar listener not ready", surfacing a false
            // "connection failed" even though the live instance is already carrying traffic.
            data.connectingJob = runOnDefaultDispatcher {
                try {
                    DataStore.configurationStore.refreshSuspend()
                    when {
                        ipcProfileId == 0L -> DataStore.selectedProxy = 0L
                        ipcProfileId > 0L && SagerDatabase.proxyDao.getById(ipcProfileId) != null ->
                            DataStore.selectedProxy = ipcProfileId
                    }
                    val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                    onMainDispatcher {
                        // Assign connectingJob to the inner connect job from HERE (after
                        // onStartConnect returns it), not from inside onStartConnect, so a
                        // racing stopRunner() never sees this outer setup coroutine as the
                        // tracked job once the inner connect job exists.
                        data.connectingJob = onStartConnect(profile)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        stopRunner(
                            false,
                            "${getString(R.string.service_failed)}: ${e.readableMessage}",
                        )
                    }
                }
            }
            return Service.START_NOT_STICKY
        }

        private fun registerCloseReceiver() {
            this as Context
            val data = data
            if (data.closeReceiverRegistered) return
            val filter = IntentFilter().apply {
                addAction(Action.RELOAD)
                addAction(Action.RESTART)
                addAction(Intent.ACTION_SHUTDOWN)
                addAction(Action.CLOSE)
                // addAction(Action.SWITCH_WAKE_LOCK)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(Action.RESET_UPSTREAM_CONNECTIONS)
            }
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                data.receiver,
                filter,
                "$packageName.permission.SERVICE_ACCESS",
                null,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
            )
            data.closeReceiverRegistered = true
        }

        private fun onStartConnect(profile: ProxyEntity?): Job? {
            this as Context
            val data = data
            if (profile == null) {
                stopRunner(false, getString(R.string.profile_empty))
                return null
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot

            val connectingJob = GlobalScope.launch(
                Dispatchers.Main.immediate,
                start = CoroutineStart.LAZY,
            ) {
                if (!data.closeReceiverRegistered) {
                    val filter = IntentFilter().apply {
                        addAction(Action.RELOAD)
                        addAction(Action.RESTART)
                        addAction(Intent.ACTION_SHUTDOWN)
                        addAction(Action.CLOSE)
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                        addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                    }
                    androidx.core.content.ContextCompat.registerReceiver(
                        this@Interface,
                        data.receiver,
                        filter,
                        "$packageName.permission.SERVICE_ACCESS",
                        null,
                        androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
                    )
                    data.closeReceiverRegistered = true
                }

                data.changeState(State.Connecting)
                try {
                    ensureForegroundNotification(proxy.displayProfileName)
                        .postNotificationTitle(proxy.displayProfileName)

                    Executable.killAll()
                    preInit()
                    onDefaultDispatcher { proxy.init() }
                    DataStore.currentProfile = profile.id

                    proxy.processes = GuardedProcessPool {
                        Logs.w(it)
                        stopRunner(false, it.readableMessage)
                    }

                    startProcesses()
                    data.changeState(State.Connected)
                    startTimeoutMonitor()

                    lateInit()
                } catch (_: CancellationException) {
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false,
                        "${getString(R.string.service_failed)}: ${exc.readableMessage}",
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            data.connectingJob = connectingJob
            connectingJob.start()
            return connectingJob
        }
    }
}
