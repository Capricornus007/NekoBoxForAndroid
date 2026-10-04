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
import io.nekohasekai.sagernet.bg.proto.TAILSCALE_READY_TIMEOUT_MS
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.tailscale.pruneTailscaleState
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap

// Kill switch reconnect backoff while the VPN interface is held with no core behind it.
private const val KILL_SWITCH_RETRY_INITIAL_MS = 5_000L
private const val KILL_SWITCH_RETRY_MAX_MS = 60_000L

// Binder error for a profile that is not part of the running configuration; callers fall
// back to a test instance of their own.
const val PROFILE_NOT_RUNNING = "profile not in running configuration"

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
        ;

        val ownsTailscaleState: Boolean get() = started || this == Stopping
    }

    interface ExpectedException

    class Data internal constructor(private val service: Interface) {
        @Volatile var state = State.Stopped

        @Volatile var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null

        val receiver = broadcastReceiverWithSelf { self, ctx, intent ->
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> service.persistStats(self)

                Action.RELOAD -> service.reload(
                    intent.getLongExtra(Action.EXTRA_PROFILE_ID, -1L),
                )

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
                        Toast.makeText(ctx, "Reset upstream connections done", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                else -> {
                    // A stop the user asked for pauses network automation until the next start.
                    if (!intent.getBooleanExtra(Action.EXTRA_AUTOMATED, false)) DataStore.automationPaused = true
                    service.stopRunner()
                }
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        internal val tailscale = TailscaleSessionController(this) { binder.callbackIdMap.containsKey(it) }
        var connectingJob: Job? = null

        // Kill switch reconnect while stopGate.holdTun keeps the interface up with no core behind it.
        var retryJob: Job? = null
        var retryDelayMs = KILL_SWITCH_RETRY_INITIAL_MS

        // The stop/reload decision core (pendingRestart, stopGeneration, holdTun); see
        // ServiceStopGate for the invariants and threading contract.
        val stopGate = ServiceStopGate()

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            if (s == State.Stopping || s == State.Stopped) {
                stopGate.onEnterStopState()
            }
            if (s == State.Connecting || s == State.Stopping) TailscaleSessionController.stopAllAdmission()
            state = s
            if (s == State.Connected || s == State.Stopped) TailscaleSessionController.resumeAllAdmission()
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(private var data: Data? = null) :
        ISagerNetService.Stub(),
        CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback) {
                releaseCallback(callback)
            }
        }

        val callbackIdMap = ConcurrentHashMap<IBinder, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (id == SagerConnection.CONNECTION_ID_RESTART_BG) {
                Runtime.getRuntime().exit(0)
                return
            }
            synchronized(callbackIdMap) {
                if (!callbackIdMap.containsKey(cb.asBinder()) && !callbacks.register(cb)) return
                callbackIdMap[cb.asBinder()] = id
            }
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
            releaseCallback(cb)
            callbacks.unregister(cb)
        }

        private fun releaseCallback(cb: ISagerNetServiceCallback) {
            synchronized(callbackIdMap) { callbackIdMap.remove(cb.asBinder()) }
            data?.tailscale?.releaseOwner(cb.asBinder())
        }

        override fun observeTailscale(cb: ISagerNetServiceCallback, sessionId: Long, profileId: Long, expectedIdentity: String) {
            data?.tailscale?.open(cb, sessionId, profileId, expectedIdentity, false)
        }

        override fun startTailscaleCheck(cb: ISagerNetServiceCallback, sessionId: Long, profileId: Long, expectedIdentity: String) {
            data?.tailscale?.open(cb, sessionId, profileId, expectedIdentity, true)
        }

        override fun closeTailscaleSession(cb: ISagerNetServiceCallback, sessionId: Long) {
            data?.tailscale?.closeSession(cb, sessionId)
        }

        override fun pingTailscalePeer(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long, peerId: String, timeoutMs: Int) {
            data?.tailscale?.ping(cb, sessionId, requestId, peerId, timeoutMs)
        }

        override fun setTailscaleExitNode(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long, peerId: String, expectedSavedSelection: String) {
            data?.tailscale?.setExit(cb, sessionId, requestId, peerId, expectedSavedSelection)
        }

        override fun cancelTailscaleRequest(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long) {
            data?.tailscale?.cancelRequest(cb, sessionId, requestId)
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

        private fun runningProxy() = data?.takeIf { it.state == State.Connected }?.proxy?.takeIf { it.isInitialized() }
            ?: error("core not started")

        // Right after Connected a node may still be logging in or selecting its exit node. Only
        // the nodes on the tested profile's path are awaited, so another member's broken node
        // does not block the test.
        private fun awaitTailscaleNodes(proxy: ProxyInstance, profileId: Long) {
            val nodes = proxy.config.profileTailscaleNodes[profileId] ?: setOf(profileId)
            for (nodeId in nodes) {
                val endpoint = proxy.config.tailscaleEndpoints[nodeId] ?: continue
                val deadline = SystemClock.elapsedRealtime() + TAILSCALE_READY_TIMEOUT_MS
                while (true) {
                    check(data?.state == State.Connected && data?.proxy === proxy) { "core not started" }
                    try {
                        Libcore.tailscaleWaitReady(
                            proxy.box,
                            endpoint.tag,
                            proxy.tailscaleReadiness.get(nodeId, endpoint.waitForExitNode),
                            1_000,
                        )
                        break
                    } catch (e: Exception) {
                        if (SystemClock.elapsedRealtime() >= deadline) throw e
                        Thread.sleep(100)
                    }
                }
            }
        }

        override fun urlTestProfile(profileId: Long): Int {
            val proxy = runningProxy()
            val tag = proxy.config.profileTagMap[profileId]
                ?: proxy.config.tailscaleEndpoints[profileId]?.tag
                ?: error(PROFILE_NOT_RUNNING)
            return try {
                awaitTailscaleNodes(proxy, profileId)
                Libcore.urlTestOutbound(proxy.box, tag, DataStore.connectionTestURL, DataStore.connectionTestTimeout)
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        override fun tailscalePeers(profileId: Long): String {
            val proxy = runningProxy()
            val endpoint = proxy.config.tailscaleEndpoints[profileId] ?: error(PROFILE_NOT_RUNNING)
            // Only the login matters for listing peers; the exit node may be the one being replaced.
            return try {
                Libcore.tailscaleWaitReady(proxy.box, endpoint.tag, false, TAILSCALE_READY_TIMEOUT_MS)
                Libcore.tailscalePeers(proxy.box, endpoint.tag)
            } catch (e: Exception) {
                error(e.readableMessage)
            }
        }

        override fun runningTailscaleProfiles(): String = JSONArray(runningProxy().config.tailscaleEndpoints.keys.toList()).toString()

        override fun connections(includeClosed: Boolean): String = data?.proxy?.takeIf { it.isInitialized() }?.box?.connections(includeClosed) ?: "[]"

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            data?.tailscale?.destroy()
            callbackIdMap.clear()
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

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
                    // Compute the in-place selector decision here (off the main thread) so
                    // reloadInner() does no DAO reads on the UI thread (Plan 027). null tag =>
                    // no fast-path (fall through to the state machine). Only a profile switch
                    // carries an id; a reload without one comes from a settings change, and the
                    // in-place select would leave DNS, rules and app routing as they were.
                    val selectorTag = if (profileId > 0L) resolveSelectorReloadTag() else null
                    onMainDispatcher { reloadInner(reloadStopGeneration, selectorTag) }
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

        private fun reloadInner(reloadStopGeneration: Long, selectorTag: String?) {
            // A stop raced the async refresh: drop this stale reload so it can't restart a service
            // the user stopped. (A Connecting->Connected transition does NOT bump stopGeneration,
            // so an in-flight legitimate reload still applies.)
            if (data.stopGate.isStale(reloadStopGeneration)) return
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            val s = data.state
            // Only take the in-place selector fast-path when fully Connected: during Connecting
            // data.proxy is set but proxy.init() may not have built config/box yet, and during
            // Stopping the box is being torn down — touching them would throw or act on a dead
            // instance. In those states fall through to the state machine below. selectorTag was
            // resolved off the main thread by the caller (null => no fast-path).
            if (s == State.Connected && selectorTag != null && selectorTag.isNotBlank()) {
                // select from GUI
                data.proxy!!.box.selectOutbound(selectorTag)
                // or select from webui
                // => selector_OnProxySelected
                return
            }
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        // Off-main-thread resolver for reloadInner's in-place selector fast-path. Returns the
        // outbound tag to select, or null if the selector fast-path does not apply. Does all the
        // DAO reads (proxy/group) so reloadInner touches no DB on the UI thread.
        fun resolveSelectorReloadTag(): String? {
            if (data.state != State.Connected) return null
            if (!canReloadSelector()) return null
            val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return null
            val proxy = data.proxy ?: return null
            val tag = proxy.config.profileTagMap[ent.id] ?: ""
            return tag.ifBlank { null }
        }

        fun canReloadSelector(): Boolean {
            val proxy = data.proxy ?: return false
            val running = proxy.lastSelectorGroupId
            if (running < 0L) return false
            val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            // Mirrors ConfigBuilder.buildConfig()'s selectorGroupId derivation
            // (TYPE_CONFIG/type==0 early exit; else group.isSelector -> group.id).
            // Keep in sync with ConfigBuilder.kt:106-119,179,1194.
            if (ent.type == ProxyEntity.TYPE_CONFIG &&
                (ent.requireBean() as? ConfigBean)?.type == 0
            ) {
                return false
            }
            val group = SagerDatabase.groupDao.getById(ent.groupId) ?: return false
            val newSelectorGroupId = if (group.isSelector) group.id else -1L
            // A switch between manual and automatic selection needs a rebuilt config; in automatic
            // mode the in-place select is a no-op (urltest owns the choice) so no restart happens.
            return newSelectorGroupId == running && group.autoSelect == proxy.lastAutoSelect
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
            runServiceTeardown(
                after = {
                    wakeLock?.apply {
                        release()
                        wakeLock = null
                    }
                    // Network automation keeps evaluating while the kill switch holds the tun.
                    if (!data.stopGate.holdTun) DefaultNetworkListener.stop(this@Interface)
                },
            ) {
                TailscaleSessionController.drainAll()
                data.proxy?.closeAndPersist()
            }
        }

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            DataStore.baseService = null
            DataStore.vpnService = null
            DataStore.mixedInboundAuthed = false

            // Kill switch: a restart or a failure (every stop that carries a message) keeps the VPN
            // interface up so nothing leaks while the core is down. An explicit stop releases it.
            val hold = this is VpnService && DataStore.killSwitch && (restart || msg != null)
            // A teardown already in progress merges this request (explicit stop cancels a
            // pending restart and releases the hold, so that teardown ends stopped rather than
            // blocking; see ServiceStopGate.onStopRequested) and we must return.
            if (data.stopGate.onStopRequested(restart, hold, data.state == State.Stopping)) return
            // Job.cancel() is the member; the wildcard import also brings the extension into scope.
            //noinspection MemberExtensionConflict
            data.retryJob?.cancel()
            // Blocked: the failure case. The service stays in the foreground with the tun held and
            // no core, reports Connecting (stoppable, reload-able) and retries with backoff.
            val mayBlock = hold && !restart
            if (!mayBlock) {
                data.notification?.destroy()
                data.notification = null
            }
            this as Service

            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                coroutineScope {
                    killProcesses()
                    data.proxy = null
                }
                // A stop merged after the teardown kept the tun released the hold: a second pass
                // closes the tun and unregisters the network listener.
                if (data.stopGate.holdReleased()) killProcesses()
                // VpnService drops the hold when it ended up without an interface: then nothing
                // blocks, and the service stops normally instead of claiming protection.
                val blocked = mayBlock && data.stopGate.holdTun
                if (!blocked) {
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                    data.notification?.destroy()
                    data.notification = null
                }

                if (blocked) {
                    // startForegroundService demands a notification; a failure ahead of
                    // onStartConnect has not created one yet.
                    val title = getString(R.string.kill_switch_blocking)
                    val notification = data.notification
                    if (notification == null) data.notification = createNotification(title) else notification.postNotificationTitle(title)
                    // Keep socket protection for the UI process so connection tests still leave
                    // through the underlying network while the tun drops everything else.
                    DataStore.vpnService = this@Interface as VpnService
                    Libcore.serveProtect(true)
                    data.changeState(State.Connecting, msg)
                    scheduleKillSwitchRetry()
                    return@runOnMainDispatcher
                }
                Libcore.serveProtect(false)

                // change the state
                data.changeState(State.Stopped, msg)
                // The core is gone: identities of profiles deleted while it ran can go now.
                runOnDefaultDispatcher { pruneTailscaleState() }
                // stop the service if nothing has bound to it. Re-read pendingRestart: an explicit
                // CLOSE that raced this teardown may have cleared it.
                if (data.stopGate.consumeRestart()) {
                    startRunner()
                } else {
                    stopSelf()
                }
            }
        }

        // Doubling backoff capped at a minute; no jitter and no failure classification, so a
        // deterministic failure (no profile, bad config) retries once a minute until stopped.
        private fun scheduleKillSwitchRetry() {
            val delayMs = data.retryDelayMs
            data.retryDelayMs = (delayMs * 2).coerceAtMost(KILL_SWITCH_RETRY_MAX_MS)
            data.retryJob = runOnMainDispatcher {
                delay(delayMs)
                if (data.stopGate.holdTun && data.state == State.Connecting && data.proxy == null) stopRunner(true)
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

        suspend fun preInit() {
            DefaultNetworkListener.start(this) {
                NetworkAutomation.onNetwork(it, serviceRunning = true)
                SagerNet.connectivity.getLinkProperties(it)?.also { link ->
                    SagerNet.underlyingNetwork = it
                    DataStore.vpnService?.updateUnderlyingNetwork()
                    //
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        upstreamInterfaceName = link.interfaceName
                    }
                    if (oldName != null && upstreamInterfaceName != null && oldName != upstreamInterfaceName) {
                        Logs.d("Network changed: $oldName -> $upstreamInterfaceName")
                        if (DataStore.networkChangeResetConnections) {
                            Libcore.resetAllConnections(true)
                        }
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
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
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            DataStore.baseService = this

            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            this as Context

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
            // keeps polling a now-killed sidecar port until its readiness deadline
            // and then throws "sidecar listener not ready", surfacing a false
            // "connection failed" even though the live instance is already carrying traffic.
            data.connectingJob = runOnDefaultDispatcher {
                try {
                    TailscaleSessionController.drainAll()
                    DataStore.configurationStore.refreshSuspend()
                    when {
                        ipcProfileId == 0L -> DataStore.selectedProxy = 0L

                        ipcProfileId > 0L && SagerDatabase.proxyDao.getById(ipcProfileId) != null ->
                            DataStore.selectedProxy = ipcProfileId
                    }
                    val proxy = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)?.let {
                        ProxyInstance(it, this@Interface)
                    }
                    onMainDispatcher {
                        // Assign connectingJob to the inner connect job from HERE (after
                        // onStartConnect returns it), not from inside onStartConnect, so a
                        // racing stopRunner() never sees this outer setup coroutine as the
                        // tracked job once the inner connect job exists.
                        data.connectingJob = onStartConnect(proxy)
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
                addAction(Intent.ACTION_SHUTDOWN)
                addAction(Action.CLOSE)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                addAction(Action.RESET_UPSTREAM_CONNECTIONS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    data.receiver,
                    filter,
                    "$packageName.SERVICE",
                    null,
                    Context.RECEIVER_EXPORTED,
                )
            } else {
                registerReceiver(
                    data.receiver,
                    filter,
                    "$packageName.SERVICE",
                    null,
                )
            }
            data.closeReceiverRegistered = true
        }

        private fun onStartConnect(proxy: ProxyInstance?): Job? {
            this as Context
            val data = data
            if (proxy == null) { // gracefully shutdown: https://stackoverflow.com/q/47337857/2245107
                data.notification = createNotification("")
                stopRunner(false, getString(R.string.profile_empty))
                return null
            }

            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot

            return runOnMainDispatcher {
                try {
                    // Reuse the title computed during ProxyInstance construction (off the main
                    // thread); calling genTitle() here would do a groupDao read on the main thread.
                    // A kill-switch hold left the previous notification alive; replace it.
                    data.notification?.destroy()
                    data.notification = createNotification(proxy.displayProfileName)

                    Executable.killAll() // clean up old processes
                    preInit()
                    // buildConfig() (via proxy.init()) does synchronous group/profile DAO reads;
                    // run it off the main thread so it works with the main-thread-DB allowance
                    // removed (Plan 027). init() is suspend and does not touch the UI.
                    onDefaultDispatcher { proxy.init() }
                    DataStore.currentProfile = proxy.profile.id

                    proxy.processes = GuardedProcessPool {
                        Logs.w(it)
                        stopRunner(false, it.readableMessage)
                    }

                    startProcesses()
                    data.stopGate.holdTun = false
                    data.retryDelayMs = KILL_SWITCH_RETRY_INITIAL_MS
                    data.changeState(State.Connected)

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    // Carries a message so the kill switch treats it as a failure and holds the tun.
                    stopRunner(false, e.readableMessage)
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
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
        }
    }
}
