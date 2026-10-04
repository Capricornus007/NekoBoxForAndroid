package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher

class SagerConnection(
    private var connectionId: Int,
    private var listenForDeath: Boolean = false,
) : ServiceConnection,
    IBinder.DeathRecipient {

    companion object {
        val serviceClass
            get() = when (DataStore.serviceMode) {
                Key.MODE_PROXY -> ProxyService::class
                Key.MODE_VPN -> VpnService::class
                else -> throw UnknownError()
            }.java

        const val CONNECTION_ID_SHORTCUT = 0
        const val CONNECTION_ID_TILE = 1
        const val CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND = 2
        const val CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND = 3
        const val CONNECTION_ID_RESTART_BG = 4
        const val CONNECTION_ID_SHARE_CONNECTION = 5
        const val CONNECTION_ID_TAILSCALE_SETTINGS = 6
        const val CONNECTION_ID_TAILSCALE_STATUS = 7

        var restartingApp = false
    }

    interface Callback {
        // smaller ISagerNetServiceCallback

        fun cbSpeedUpdate(stats: SpeedDisplayData) {}
        fun cbTrafficUpdate(data: TrafficData) {}
        fun cbTrafficUpdateList(data: List<TrafficData>) {
            data.forEach { cbTrafficUpdate(it) }
        }
        fun cbSelectorUpdate(id: Long) {}
        fun cbTailscaleStatus(sessionId: Long, sequence: Long, json: String) {}
        fun cbTailscaleResult(sessionId: Long, requestId: Long, json: String) {}

        fun stateChanged(state: BaseService.State, profileName: String?, msg: String?)

        fun missingPlugin(profileName: String, pluginName: String) {}

        fun onServiceConnected(service: ISagerNetService)

        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() {}
        fun onBinderDied() {}
    }

    private var connectionActive = false
    private var callbackRegistered = false
    private var callback: Callback? = null

    @Volatile private var epoch = 0L
    private var serviceCallback = newServiceCallback(epoch)
    private fun newServiceCallback(callbackEpoch: Long) = object : ISagerNetServiceCallback.Stub() {
        private fun deliver(block: (Callback) -> Unit) {
            runOnMainDispatcher {
                if (epoch == callbackEpoch && service != null) callback?.let(block)
            }
        }

        override fun cbTailscaleStatus(sessionId: Long, sequence: Long, json: String) = deliver { it.cbTailscaleStatus(sessionId, sequence, json) }

        override fun cbTailscaleResult(sessionId: Long, requestId: Long, json: String) = deliver { it.cbTailscaleResult(sessionId, requestId, json) }

        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            if (state < 0) return // skip private
            val s = BaseService.State.values()[state]
            runOnMainDispatcher {
                if (epoch != callbackEpoch || service == null) return@runOnMainDispatcher
                DataStore.serviceState = s
                callback?.stateChanged(s, profileName, msg)
            }
        }

        override fun cbSpeedUpdate(stats: SpeedDisplayData) {
            deliver { it.cbSpeedUpdate(stats) }
        }

        override fun cbTrafficUpdate(stats: TrafficData) {
            deliver { it.cbTrafficUpdate(stats) }
        }

        override fun cbTrafficUpdateList(stats: MutableList<TrafficData>) {
            deliver { it.cbTrafficUpdateList(stats) }
        }

        override fun cbSelectorUpdate(id: Long) {
            deliver { it.cbSelectorUpdate(id) }
        }

        override fun missingPlugin(profileName: String, pluginName: String) {
            deliver { it.missingPlugin(profileName, pluginName) }
        }
    }

    private var binder: IBinder? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    @Volatile private var tailscaleBinding: Pair<ISagerNetService, ISagerNetServiceCallback>? = null

    var service: ISagerNetService? = null

    fun observeTailscale(sessionId: Long, profileId: Long, expectedIdentity: String) {
        val binding = checkNotNull(tailscaleBinding)
        binding.first.observeTailscale(binding.second, sessionId, profileId, expectedIdentity)
    }

    fun startTailscaleCheck(sessionId: Long, profileId: Long, expectedIdentity: String) {
        val binding = checkNotNull(tailscaleBinding)
        binding.first.startTailscaleCheck(binding.second, sessionId, profileId, expectedIdentity)
    }

    fun closeTailscaleSession(sessionId: Long) {
        val binding = tailscaleBinding ?: return
        binding.first.closeTailscaleSession(binding.second, sessionId)
    }

    fun pingTailscalePeer(sessionId: Long, requestId: Long, peerId: String, timeoutMs: Int) {
        val binding = checkNotNull(tailscaleBinding)
        binding.first.pingTailscalePeer(binding.second, sessionId, requestId, peerId, timeoutMs)
    }

    fun setTailscaleExitNode(sessionId: Long, requestId: Long, peerId: String, expectedSavedSelection: String) {
        val binding = checkNotNull(tailscaleBinding)
        binding.first.setTailscaleExitNode(binding.second, sessionId, requestId, peerId, expectedSavedSelection)
    }

    fun cancelTailscaleRequest(sessionId: Long, requestId: Long) {
        val binding = tailscaleBinding ?: return
        binding.first.cancelTailscaleRequest(binding.second, sessionId, requestId)
    }

    fun updateConnectionId(id: Int) {
        connectionId = id
        try {
            service?.registerCallback(serviceCallback, id)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        epoch++
        serviceCallback = newServiceCallback(epoch)
        this.binder = binder
        val service = ISagerNetService.Stub.asInterface(binder)!!
        this.service = service
        try {
            if (listenForDeath || connectionId == CONNECTION_ID_TAILSCALE_STATUS) {
                val connectedEpoch = epoch
                deathRecipient = IBinder.DeathRecipient { handleDeath(connectedEpoch, binder) }.also { binder.linkToDeath(it, 0) }
            }
            check(!callbackRegistered)
            service.registerCallback(serviceCallback, connectionId)
            callbackRegistered = true
            tailscaleBinding = service to serviceCallback
            DataStore.serviceState = BaseService.State.values()[service.state]
        } catch (e: RemoteException) {
            e.printStackTrace()
        }
        callback?.onServiceConnected(service)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        tailscaleBinding = null
        epoch++
        unregisterCallback()
        callback?.onServiceDisconnected()
        service = null
        binder = null
    }

    override fun binderDied() = handleDeath(epoch, binder)

    private fun handleDeath(connectedEpoch: Long, deadBinder: IBinder?) {
        runOnMainDispatcher {
            if (epoch != connectedEpoch || binder !== deadBinder) return@runOnMainDispatcher
            tailscaleBinding = null
            epoch++
            service = null
            callbackRegistered = false
            if (!restartingApp) callback?.onBinderDied()
        }
    }

    private fun unregisterCallback() {
        val service = service
        if (service != null && callbackRegistered) {
            try {
                service.unregisterCallback(serviceCallback)
            } catch (_: RemoteException) {
            }
        }
        callbackRegistered = false
    }

    fun connect(context: Context, callback: Callback?) {
        if (connectionActive) return
        connectionActive = true
        check(this.callback == null)
        this.callback = callback
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        context.bindService(intent, this, Context.BIND_AUTO_CREATE)
    }

    fun disconnect(context: Context) {
        tailscaleBinding = null
        epoch++
        unregisterCallback()
        if (connectionActive) {
            try {
                context.unbindService(this)
            } catch (_: IllegalArgumentException) {
            } // ignore
        }
        connectionActive = false
        if (listenForDeath || connectionId == CONNECTION_ID_TAILSCALE_STATUS) {
            try {
                deathRecipient?.let { binder?.unlinkToDeath(it, 0) }
            } catch (_: NoSuchElementException) {
            }
        }
        binder = null
        deathRecipient = null
        service = null
        callback = null
    }
}
