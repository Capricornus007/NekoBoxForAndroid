package io.nekohasekai.sagernet.bg

import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class SagerConnectionTailscaleTest {
    private class Service {
        val binder = Binder()
        lateinit var callback: ISagerNetServiceCallback
        val calls = mutableListOf<Pair<String, List<Any?>>>()
        val api = Proxy.newProxyInstance(ISagerNetService::class.java.classLoader, arrayOf(ISagerNetService::class.java)) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder

                "getState" -> BaseService.State.Stopped.ordinal

                "registerCallback" -> {
                    callback = args!![0] as ISagerNetServiceCallback
                    null
                }

                "unregisterCallback" -> null

                else -> {
                    calls += method.name to args.orEmpty().toList()
                    null
                }
            }
        } as ISagerNetService
        init {
            binder.attachInterface(api, "io.nekohasekai.sagernet.aidl.ISagerNetService")
        }
    }

    @Test
    fun stateUpdatesWithoutUiCallbackStillRequireConnectedService() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = true
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        connection.connect(context, null)
        try {
            val service = Service()
            connection.onServiceConnected(null, service.binder)
            service.callback.stateChanged(BaseService.State.Connecting.ordinal, null, null)
            assertEquals(BaseService.State.Stopped, DataStore.serviceState)
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            service.callback.stateChanged(-1, null, null)
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            service.callback.stateChanged(BaseService.State.Connected.ordinal, null, null)
            connection.service = null
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            connection.service = service.api
        } finally {
            connection.disconnect(context)
            DataStore.serviceState = BaseService.State.Idle
            Dispatchers.resetMain()
        }
    }

    @Test
    fun staleStateCallbacksCannotChangeCacheOrUiAfterReconnect() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = true
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val received = mutableListOf<BaseService.State>()
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        connection.connect(
            context,
            object : SagerConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
                    assertEquals(state, DataStore.serviceState)
                    received += state
                }
                override fun onServiceConnected(service: ISagerNetService) = Unit
            },
        )
        try {
            val first = Service()
            connection.onServiceConnected(null, first.binder)
            first.callback.stateChanged(BaseService.State.Connected.ordinal, null, null)
            connection.onServiceDisconnected(null)
            val second = Service()
            connection.onServiceConnected(null, second.binder)
            runCurrent()
            assertEquals(BaseService.State.Stopped, DataStore.serviceState)
            assertTrue(received.isEmpty())
            first.callback.stateChanged(BaseService.State.Connected.ordinal, null, null)
            second.callback.stateChanged(BaseService.State.Connecting.ordinal, null, null)
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            assertEquals(listOf(BaseService.State.Connecting), received)
            connection.disconnect(context)
            second.callback.stateChanged(BaseService.State.Connected.ordinal, null, null)
            runCurrent()
            assertEquals(BaseService.State.Connecting, DataStore.serviceState)
            assertEquals(listOf(BaseService.State.Connecting), received)
        } finally {
            connection.disconnect(context)
            DataStore.serviceState = BaseService.State.Idle
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lateCallbacksAreDroppedInsideMainDeliveryAndReconnectDoesNotReplay() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int) = true
            override fun unbindService(connection: ServiceConnection) = Unit
        }
        val received = mutableListOf<String>()
        val connection = SagerConnection(SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        connection.connect(
            context,
            object : SagerConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = Unit
                override fun onServiceConnected(service: ISagerNetService) = Unit
                override fun cbTailscaleStatus(sessionId: Long, sequence: Long, json: String) {
                    received += json
                }
                override fun cbTailscaleResult(sessionId: Long, requestId: Long, json: String) {
                    received += json
                }
            },
        )
        try {
            val first = Service()
            connection.onServiceConnected(null, first.binder)
            connection.observeTailscale(1, 42, "identity")
            connection.startTailscaleCheck(1, 42, "identity")
            connection.setTailscaleExitNode(1, 2, "peer", "old")
            assertTrue(first.calls.all { it.second.first() === first.callback })
            first.callback.cbTailscaleStatus(1, 1, "old-status")
            first.callback.cbTailscaleResult(1, 2, "old-result")
            connection.onServiceDisconnected(null)
            val second = Service()
            connection.onServiceConnected(null, second.binder)
            runCurrent()
            assertTrue(received.isEmpty())
            assertTrue(second.calls.isEmpty())
            first.callback.cbTailscaleStatus(1, 2, "late-old-status")
            second.callback.cbTailscaleStatus(3, 1, "new-status")
            connection.pingTailscalePeer(3, 4, "peer", 1_000)
            connection.cancelTailscaleRequest(3, 4)
            connection.closeTailscaleSession(3)
            runCurrent()
            assertEquals(listOf("new-status"), received)
            assertTrue(second.calls.all { it.second.first() === second.callback })
            connection.binderDied()
            second.callback.cbTailscaleResult(3, 4, "after-death")
            runCurrent()
            assertEquals(listOf("new-status"), received)
        } finally {
            connection.disconnect(context)
            Dispatchers.resetMain()
        }
    }
}
