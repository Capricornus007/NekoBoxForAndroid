package io.nekohasekai.sagernet.api

import android.app.Application
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ProxyService
import io.nekohasekai.sagernet.bg.TailscaleSessionController
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ApiRuntimeTest {
    @Test
    fun apiDisconnectDoesNotOverwriteUiStateButItsOwnStateBecomesUnknown() {
        ConfigBuilderTestEnv.reset()
        var state = BaseService.State.Stopped
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication()) { state }
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            DataStore.serviceState = BaseService.State.Connected
            assertEquals(BaseService.State.Stopped, runtime.serviceState())
            state = BaseService.State.Stopping
            assertEquals(BaseService.State.Stopping, runtime.serviceState())
            runtime.onServiceDisconnected()
            assertEquals(BaseService.State.Connected, DataStore.serviceState)
            assertEquals(BaseService.State.Idle, runtime.serviceState())
        } finally {
            runtime.close()
        }
    }

    @Test
    fun uiModeChangeInvalidatesTheOldBinderUntilTheReplacementConnects() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        var state = BaseService.State.Stopped
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication()) { state }
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            assertEquals(ProxyService::class.java.name, fixture.bindings.last().className)
            assertEquals(BaseService.State.Stopped, runtime.serviceState())
            fixture.bindImmediately = false
            DataStore.serviceMode = Key.MODE_VPN
            assertEquals(BaseService.State.Idle, runtime.serviceState())
            assertEquals("service_unavailable", (runCatching { runtime.binder() }.exceptionOrNull() as ApiFailure).code)
            runCurrent()
            assertEquals(VpnService::class.java.name, fixture.bindings.last().className)
            assertEquals(BaseService.State.Idle, runtime.serviceState())
            state = BaseService.State.Connected
            fixture.completeBinding()
            assertEquals(BaseService.State.Connected, runtime.serviceState())
            runtime.close()
            val bindings = fixture.bindings.size
            DataStore.serviceMode = Key.MODE_PROXY
            runCurrent()
            assertEquals(bindings, fixture.bindings.size)
            assertEquals(BaseService.State.Idle, runtime.serviceState())
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun aReversedModeChangeKeepsTheMatchingLiveBinding() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication()) { BaseService.State.Stopped }
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            DataStore.serviceMode = Key.MODE_VPN
            assertEquals(BaseService.State.Idle, runtime.serviceState())
            DataStore.serviceMode = Key.MODE_PROXY
            assertEquals(BaseService.State.Stopped, runtime.serviceState())
            runCurrent()
            assertEquals(1, fixture.bindings.size)
            assertEquals(BaseService.State.Stopped, runtime.serviceState())
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun lateBindingFromTheOldModeCannotMakeTheReplacementLookStopped() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication()) { BaseService.State.Stopped }
        fixture.bindImmediately = false
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            DataStore.serviceMode = Key.MODE_VPN
            runCurrent()
            fixture.completeBinding()
            assertEquals(BaseService.State.Idle, runtime.serviceState())
            fixture.completeBinding()
            assertEquals(BaseService.State.Stopped, runtime.serviceState())
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun bulkPreferenceReplacementAlsoInvalidatesAMismatchedBinding() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication()) { BaseService.State.Stopped }
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            DataStore.configurationStore.replaceAllDurable(listOf(KeyValuePair(Key.SERVICE_MODE).put(Key.MODE_VPN)))
            assertEquals(BaseService.State.Idle, runtime.serviceState())
            runCurrent()
            assertEquals(VpnService::class.java.name, fixture.bindings.last().className)
            assertEquals(BaseService.State.Stopped, runtime.serviceState())
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun oversizedTailscaleInputsAreRejectedBeforeIdsOrRequestSlotsAreConsumed() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication())
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            val profile = ProxyEntity(id = 42, uuid = "fixture").putBean(TailscaleBean().apply { initializeDefaultValues() })
            val session = runtime.openTailscale(profile, false).getLong("sessionId")
            for ((peer, timeout, exit) in listOf(
                Triple("p".repeat(257), 1000, null),
                Triple("p".repeat(257), null, ""),
                Triple("peer", null, "e".repeat(4097)),
            )) {
                val failure = runCatching { runtime.tailscaleRequest(session, peer, timeout, exit) }.exceptionOrNull()
                assertTrue(failure is ApiFailure)
                assertEquals("invalid_parameters", (failure as ApiFailure).code)
            }
            assertFalse(fixture.calls.any { it.first == "pingTailscalePeer" || it.first == "setTailscaleExitNode" })
            val accepted = runtime.tailscaleRequest(session, "p".repeat(256), 1000, null).getLong("requestId")
            assertEquals(session + 1, accepted)
            runtime.tailscaleRequest(session, "p".repeat(256), null, "e".repeat(4096))
            assertEquals(1, fixture.calls.count { it.first == "setTailscaleExitNode" })
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun closedSessionsFinishPendingRequestsAndCannotReopenFromLateStatus() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (stage in listOf("closed", "error")) {
                val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication())
                val runtime = ApiRuntime(fixture.context)
                try {
                    runtime.connect()
                    val profile = ProxyEntity(id = 42, uuid = "fixture").putBean(TailscaleBean().apply { initializeDefaultValues() })
                    val session = runtime.openTailscale(profile, false).getLong("sessionId")
                    val ping = runtime.tailscaleRequest(session, "peer", 1000, null).getLong("requestId")
                    val exit = runtime.tailscaleRequest(session, "peer", null, "").getLong("requestId")
                    fixture.callback.cbTailscaleStatus(session, 1, """{"stage":"$stage"}""")
                    runCurrent()
                    for (id in listOf(ping, exit)) {
                        val result = runtime.tailscaleResult(id)
                        assertTrue(result.getBoolean("done"))
                        assertEquals("unavailable", result.getString("state"))
                        assertFalse(result.has("outcome"))
                    }
                    assertEquals("session_closed", (runCatching { runtime.tailscaleRequest(session, "peer", 1000, null) }.exceptionOrNull() as ApiFailure).code)
                    fixture.callback.cbTailscaleStatus(session, 2, """{"stage":"observing"}""")
                    fixture.callback.cbTailscaleResult(session, ping, """{"kind":"ping","done":false}""")
                    fixture.callback.cbTailscaleResult(session, exit, """{"kind":"exit","outcome":"saved-for-next-start"}""")
                    runCurrent()
                    assertEquals(stage, runtime.tailscaleStatus(session).getJSONObject("status").getString("stage"))
                    assertTrue(runtime.tailscaleResult(ping).getBoolean("done"))
                    assertEquals("saved-for-next-start", runtime.tailscaleResult(exit).getString("outcome"))
                    runtime.closeTailscale(session)
                    assertEquals("not_found", (runCatching { runtime.tailscaleResult(ping) }.exceptionOrNull() as ApiFailure).code)
                    assertEquals(exit + 1, runtime.openTailscale(profile, false).getLong("sessionId"))
                } finally {
                    runtime.close()
                }
            }
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun missingCallbacksExpireWithoutClaimingAnExitWasRolledBack() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication())
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            val profile = ProxyEntity(id = 42, uuid = "fixture").putBean(TailscaleBean().apply { initializeDefaultValues() })
            val session = runtime.openTailscale(profile, false).getLong("sessionId")
            val exit = runtime.tailscaleRequest(session, "peer", null, "").getLong("requestId")
            val pings = List(3) { runtime.tailscaleRequest(session, "peer", 1000, null).getLong("requestId") }
            assertEquals("pending", runtime.tailscaleResult(exit).getString("state"))
            ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
            runtime.tailscaleRequest(session, "peer", 1000, null)
            for (id in pings + exit) {
                val result = runtime.tailscaleResult(id)
                assertTrue(result.getBoolean("done"))
                assertEquals("tailscale:result-timeout", result.getString("errorCode"))
                assertFalse(result.has("outcome"))
            }
            assertEquals(4, fixture.calls.count { it.first == "cancelTailscaleRequest" })
            fixture.callback.cbTailscaleResult(session, pings.first(), """{"kind":"ping","done":false}""")
            fixture.callback.cbTailscaleResult(session, exit, """{"kind":"exit","outcome":"applied-and-saved"}""")
            runCurrent()
            assertTrue(runtime.tailscaleResult(pings.first()).getBoolean("done"))
            assertEquals("applied-and-saved", runtime.tailscaleResult(exit).getString("outcome"))
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun admissionMatchesControllerSessionAndInFlightRequestLimits() = runTest {
        ConfigBuilderTestEnv.reset()
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val fixture = ApiServiceFixture(RuntimeEnvironment.getApplication())
        val runtime = ApiRuntime(fixture.context)
        try {
            runtime.connect()
            val profile = ProxyEntity(id = 42, uuid = "fixture").putBean(TailscaleBean().apply { initializeDefaultValues() })
            val session = runtime.openTailscale(profile, false).getLong("sessionId")
            runtime.openTailscale(profile, false)
            assertEquals(TailscaleSessionController.MAX_OWNER_SESSIONS, fixture.calls.count { it.first == "observeTailscale" })
            assertEquals("busy", (runCatching { runtime.openTailscale(profile, false) }.exceptionOrNull() as ApiFailure).code)
            val requests = List(TailscaleSessionController.MAX_SESSION_REQUESTS) { runtime.tailscaleRequest(session, "peer", 1000, null).getLong("requestId") }
            assertEquals("busy", (runCatching { runtime.tailscaleRequest(session, "peer", 1000, null) }.exceptionOrNull() as ApiFailure).code)
            fixture.callback.cbTailscaleResult(session, requests.first(), """{"kind":"ping","done":true}""")
            runCurrent()
            runtime.tailscaleRequest(session, "peer", 1000, null)
            assertEquals(TailscaleSessionController.MAX_SESSION_REQUESTS + 1, fixture.calls.count { it.first == "pingTailscalePeer" })
        } finally {
            runtime.close()
            Dispatchers.resetMain()
        }
    }
}
