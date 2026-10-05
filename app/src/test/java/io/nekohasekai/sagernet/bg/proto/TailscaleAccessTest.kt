package io.nekohasekai.sagernet.bg.proto

import android.app.Application
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleAccessTest {
    // The default sink writes through the native core, which JVM tests do not load.
    private val originalLogSink = Logs.sink

    @Before
    fun stubLogs() {
        Logs.sink = {}
    }

    @After
    fun restoreLogs() {
        Logs.sink = originalLogSink
    }

    @Test
    fun stoppingBinderRefusesProbeEvenWhenCacheIsIdle() = runTest {
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Idle
        var probed = false
        var serviceCalls = 0
        val binder = binder { BaseService.State.Stopping }
        val failure = runCatching {
            TailscaleAccess.run({ binder }, listOf(1), { serviceCalls++ }) {
                probed = true
                0
            }
        }.exceptionOrNull()
        assertEquals(0, serviceCalls)
        assertNotNull(failure)
        assertFalse(probed)
    }

    @Test
    fun serviceQueryCompletesWhileUnrelatedLoginProbeHoldsSerialization() = runTest {
        ConfigBuilderTestEnv.reset()
        val stopped = binder { BaseService.State.Stopped }
        val connected = binder { BaseService.State.Connected }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val login = async {
            TailscaleAccess.run({ stopped }, listOf(1), { -1 }) {
                entered.complete(Unit)
                release.await()
                1
            }
        }
        entered.await()
        var serviceCalls = 0
        var probes = 0
        val query = async {
            TailscaleAccess.run({ connected }, listOf(2), {
                serviceCalls++
                42
            }) {
                probes++
                -1
            }
        }
        try {
            runCurrent()
            assertFalse(login.isCompleted)
            assertTrue("Service query must not wait for login probe", query.isCompleted)
            assertEquals(42, query.await())
            assertEquals(1, serviceCalls)
            assertEquals(0, probes)
        } finally {
            release.complete(Unit)
        }
        login.await()
    }

    @Test
    fun queuedProbeRechecksServiceAfterSerialization() = runTest {
        ConfigBuilderTestEnv.reset()
        var state = BaseService.State.Stopped
        val binder = binder { state }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var probes = 0
        val first = async {
            TailscaleAccess.run({ binder }, listOf(1), { -1 }) {
                probes++
                entered.complete(Unit)
                release.await()
                1
            }
        }
        entered.await()
        val second = async {
            runCatching {
                TailscaleAccess.run({ binder }, listOf(1), { -1 }) {
                    probes++
                    2
                }
            }
        }
        runCurrent()
        state = BaseService.State.Stopping
        release.complete(Unit)
        assertEquals(1, first.await())
        assertNotNull(second.await().exceptionOrNull())
        assertEquals(1, probes)
    }

    @Test
    fun managementTemporaryProbeRefusesEveryNonStoppedState() = runTest {
        for (state in BaseService.State.values().filter { it != BaseService.State.Stopped }) {
            var started = false
            val failure = runCatching {
                TailscaleAccess.whileStopped({ state }) { started = true }
            }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(started)
        }
    }

    @Test
    fun managementTemporaryProbeUsesExistingGuardAndRechecksAfterServiceStart() = runTest {
        var state = BaseService.State.Stopped
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            TailscaleAccess.run({ binder { state } }, listOf(1), { Unit }) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        var started = false
        val queued = async {
            runCatching { TailscaleAccess.whileStopped({ state }) { started = true } }
        }
        runCurrent()
        state = BaseService.State.Connecting
        release.complete(Unit)
        first.await()
        assertNotNull(queued.await().exceptionOrNull())
        assertFalse(started)
    }

    private fun binder(state: () -> BaseService.State) = Proxy.newProxyInstance(
        ISagerNetService::class.java.classLoader,
        arrayOf(ISagerNetService::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "getState" -> state().ordinal
            else -> error("Unexpected service call: ${method.name}")
        }
    } as ISagerNetService
}
