package io.nekohasekai.sagernet.bg

import android.os.Binder
import android.os.IBinder
import android.os.RemoteCallbackList
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.TailscaleProfileStore
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TailscaleSessionControllerTest {
    private lateinit var data: BaseService.Data
    private lateinit var profile: ProxyEntity

    private class Client(val binder: IBinder = Binder()) {
        val statuses = LinkedBlockingQueue<JSONObject>()
        val results = LinkedBlockingQueue<JSONObject>()
        val statusEvents = LinkedBlockingQueue<Triple<Long, Long, JSONObject>>()
        val resultEvents = LinkedBlockingQueue<Triple<Long, Long, JSONObject>>()

        @Volatile var onStatus: ((Long, JSONObject) -> Unit)? = null

        @Volatile var onResult: ((Long, JSONObject) -> Unit)? = null
        val callback = Proxy.newProxyInstance(
            ISagerNetServiceCallback::class.java.classLoader,
            arrayOf(ISagerNetServiceCallback::class.java),
        ) { _, method, args ->
            when (method.name) {
                "asBinder" -> binder

                "cbTailscaleStatus" -> {
                    val json = JSONObject(args!![2] as String)
                    statuses.add(json)
                    statusEvents.add(Triple(args[0] as Long, args[1] as Long, json))
                    onStatus?.invoke(args[0] as Long, json)
                    null
                }

                "cbTailscaleResult" -> {
                    val json = JSONObject(args!![2] as String)
                    results.add(json)
                    resultEvents.add(Triple(args[0] as Long, args[1] as Long, json))
                    onResult?.invoke(args[0] as Long, json)
                    null
                }

                else -> null
            }
        } as ISagerNetServiceCallback

        fun status(): JSONObject = checkNotNull(statuses.poll(5, TimeUnit.SECONDS))
        fun result(): JSONObject = checkNotNull(results.poll(5, TimeUnit.SECONDS))
    }

    // The default sink writes through the native core, which JVM tests do not load.
    private val originalLogSink = Logs.sink

    @Before
    fun setup() {
        Logs.sink = {}
        ConfigBuilderTestEnv.reset()
        data = newData()
        profile = ProxyEntity(groupId = 1).putBean(TailscaleBean().apply { initializeDefaultValues() })
        profile.id = ConfigBuilderTestEnv.io { SagerDatabase.proxyDao.addProxy(profile) }
    }

    private fun newData(): BaseService.Data {
        lateinit var result: BaseService.Data
        val service = Proxy.newProxyInstance(
            BaseService.Interface::class.java.classLoader,
            arrayOf(BaseService.Interface::class.java),
        ) { _, method, _ ->
            if (method.name == "getData") result else error("Unexpected service operation: ${method.name}")
        } as BaseService.Interface
        result = BaseService.Data(service)
        return result
    }

    @After
    fun cleanup() {
        runBlocking { data.tailscale.drain() }
        data.binder.close()
        Logs.sink = originalLogSink
    }

    private fun open(client: Client) {
        data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        data.binder.observeTailscale(client.callback, 1, profile.id, profile.uuid)
    }

    @Test
    fun passiveOpenAndPingNeverStartANode() {
        val client = Client()
        open(client)
        val status = client.status()
        assertEquals("not-running", status.getString("stage"))
        assertEquals("none", status.getString("source"))
        assertTrue(status.isNull("node"))
        assertNull(data.proxy)
        data.binder.pingTailscalePeer(client.callback, 1, 1, "peer", 100)
        assertEquals("tailscale:not-running", client.result().getString("errorCode"))
        assertEquals(BaseService.State.Stopped, data.state)
    }

    @Test
    fun sameSessionAndConnectionIdsBelongToDifferentBinders() {
        val first = Client()
        val second = Client()
        open(first)
        open(second)
        first.status()
        second.status()
        data.binder.closeTailscaleSession(first.callback, 1)
        data.binder.pingTailscalePeer(second.callback, 1, 1, "peer", 100)
        assertEquals("ping", second.result().getString("kind"))
        assertNull(first.results.poll())
        assertEquals(2, data.binder.callbackIdMap.size)
    }

    @Test
    fun registrationAndUnregisterUseBinderIdentityNotWrapperIdentity() {
        val original = Client()
        val alias = Client(original.binder)
        open(original)
        original.status()
        data.binder.registerCallback(alias.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        assertEquals(1, data.binder.callbackIdMap.size)
        data.binder.unregisterCallback(alias.callback)
        assertTrue(data.binder.callbackIdMap.isEmpty())
        data.binder.pingTailscalePeer(original.callback, 1, 1, "peer", 100)
        assertNull(original.results.poll(100, TimeUnit.MILLISECONDS))
        data.binder.unregisterCallback(original.callback)
    }

    @Test
    fun callbackDeathUsesTheSameCleanupAndDoesNotAffectAnotherOwner() {
        val first = Client()
        val second = Client()
        open(first)
        open(second)
        first.status()
        second.status()
        val field = BaseService.Binder::class.java.getDeclaredField("callbacks").apply { isAccessible = true }

        @Suppress("UNCHECKED_CAST")
        val callbacks = field.get(data.binder) as RemoteCallbackList<ISagerNetServiceCallback>
        callbacks.onCallbackDied(first.callback)
        assertFalse(data.binder.callbackIdMap.containsKey(first.binder))
        data.binder.pingTailscalePeer(second.callback, 1, 1, "peer", 100)
        assertEquals("ping", second.result().getString("kind"))
        data.binder.pingTailscalePeer(first.callback, 1, 1, "peer", 100)
        assertNull(first.results.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun drainClosesAdmissionBeforeJoiningSessions() {
        val client = Client()
        open(client)
        client.status()
        runBlocking { data.tailscale.drain() }
        assertEquals("tailscale:runtime-changed", client.status().getString("errorCode"))
        data.binder.startTailscaleCheck(client.callback, 2, profile.id, profile.uuid)
        assertEquals("tailscale:not-stopped", client.status().getString("errorCode"))
        assertNull(data.proxy)
    }

    @Test
    fun stoppedBoundModeCannotStartTemporaryNodeWhileOtherModeStarts() {
        val otherMode = newData()
        otherMode.state = BaseService.State.Connecting
        try {
            val client = Client()
            data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
            data.binder.startTailscaleCheck(client.callback, 1, profile.id, profile.uuid)
            assertEquals("tailscale:not-stopped", client.status().getString("errorCode"))
            assertNull(data.proxy)
        } finally {
            otherMode.state = BaseService.State.Stopped
            otherMode.binder.close()
        }
    }

    @Test
    fun modeHandoffDrainsAcceptedSaveBeforeStartupReadsProfile() = runBlocking {
        val client = Client()
        open(client)
        client.status()
        val otherMode = newData()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val field = TailscaleSessionController::class.java.getDeclaredField("sessions").apply { isAccessible = true }
        val session = (field.get(data.tailscale) as Map<*, *>).values.single()!!
        val owner = session.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(session) as Job
        // Simulate an accepted temporary-node finalizer under the real session parent, without JNI.
        val finalizer = CoroutineScope(owner + Dispatchers.IO).launch {
            finalizeTailscaleExit("", {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                object : TailscaleExitChange {
                    override fun savedValue() = "100.64.0.2"
                    override fun commit() = Unit
                    override fun rollback() = Unit
                }
            }, { TailscaleProfileStore.compareAndSetExit(profile.id, profile.uuid, "", it) }, {}, {})
        }
        var startup: Deferred<String>? = null
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            otherMode.state = BaseService.State.Connecting
            startup = async(Dispatchers.IO) {
                TailscaleSessionController.drainAll()
                TailscaleProfileStore.read(profile.id).tailscaleBean!!.exitNode.orEmpty()
            }
            delay(100)
            assertFalse(startup.isCompleted)
            release.countDown()
            assertEquals("100.64.0.2", startup.await())
            assertTrue(finalizer.isCompleted)
        } finally {
            release.countDown()
            startup?.join()
            finalizer.join()
            otherMode.state = BaseService.State.Stopped
            otherMode.binder.close()
        }
    }

    // Exercise the real owner/admission/completion path with bounded fake native work.
    private fun request(client: Client, id: Long, kind: String, block: suspend (Any, Any?) -> Unit) {
        TailscaleSessionController::class.java.declaredMethods.single { it.name == "request" }.apply {
            isAccessible = true
            invoke(data.tailscale, client.callback, 1L, id, kind, block)
        }
    }

    private fun publish(session: Any, id: Long, json: JSONObject) {
        TailscaleSessionController::class.java.declaredMethods.single { it.name == "result" }.apply {
            isAccessible = true
            invoke(data.tailscale, session, id, json)
        }
    }

    private fun exitJson(result: TailscaleExitResult) = JSONObject().put("kind", "exit")
        .put("outcome", result.outcome).put("savedExit", result.savedExit)
        .put("errorCode", result.errorCode).put("message", "")

    private fun terminal(client: Client, id: Long): JSONObject {
        val event = checkNotNull(client.resultEvents.poll(5, TimeUnit.SECONDS))
        assertEquals(1L, event.first)
        assertEquals(id, event.second)
        return event.third
    }

    @Test
    fun cancelledQueuedExitAcknowledgesOnceWithoutApplying() = runBlocking {
        val client = Client()
        open(client)
        client.status()
        val gate = Mutex(locked = true)
        val queued = CompletableDeferred<Unit>()
        val applications = AtomicInteger()
        request(client, 11, "exit") { _, _ ->
            queued.complete(Unit)
            gate.withLock { applications.incrementAndGet() }
        }
        queued.await()
        data.binder.cancelTailscaleRequest(client.callback, 1, 11)
        data.binder.cancelTailscaleRequest(client.callback, 1, 11)
        assertEquals("cancelled-before-apply", terminal(client, 11).getString("outcome"))
        gate.unlock()
        request(client, 11, "exit") { _, _ -> applications.incrementAndGet() }
        assertNull(client.resultEvents.poll(100, TimeUnit.MILLISECONDS))
        assertEquals(0, applications.get())
    }

    @Test
    fun cancelledPingAcknowledgesDoneAfterProducerCleanup() = runBlocking {
        val client = Client()
        open(client)
        client.status()
        val entered = CompletableDeferred<Unit>()
        val closed = AtomicBoolean()
        request(client, 12, "ping") { session, _ ->
            try {
                publish(session, 12, JSONObject().put("kind", "ping").put("done", false).put("sample", JSONObject()))
                entered.complete(Unit)
                awaitCancellation()
            } finally {
                closed.set(true)
            }
        }
        entered.await()
        assertFalse(terminal(client, 12).getBoolean("done"))
        data.binder.cancelTailscaleRequest(client.callback, 1, 12)
        val result = terminal(client, 12)
        assertTrue(result.getBoolean("done"))
        assertEquals("tailscale:cancelled", result.getString("errorCode"))
        assertTrue(closed.get())
        assertNull(client.resultEvents.poll(100, TimeUnit.MILLISECONDS))
    }

    private fun cancelledAdmittedExit(failSave: Boolean) = runBlocking {
        val client = Client()
        open(client)
        client.status()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finalizations = AtomicInteger()
        request(client, 13, "exit") { session, _ ->
            finalizeTailscaleExit("", {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                object : TailscaleExitChange {
                    override fun savedValue() = "100.64.0.2"
                    override fun commit() {
                        finalizations.incrementAndGet()
                    }
                    override fun rollback() {
                        finalizations.incrementAndGet()
                    }
                }
            }, {
                if (failSave) error("disk")
                TailscaleProfileStore.compareAndSetExit(profile.id, profile.uuid, "", it)
            }, {}, {}, completed = { publish(session, 13, exitJson(it)) })
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            data.binder.cancelTailscaleRequest(client.callback, 1, 13)
            data.binder.cancelTailscaleRequest(client.callback, 1, 13)
            assertNull(client.resultEvents.poll(100, TimeUnit.MILLISECONDS))
            release.countDown()
            val result = terminal(client, 13)
            assertEquals(if (failSave) "failed-rolled-back" else "applied-and-saved", result.getString("outcome"))
            assertEquals(1, finalizations.get())
            assertEquals(
                if (failSave) "" else "100.64.0.2",
                ConfigBuilderTestEnv.io {
                    TailscaleProfileStore.read(profile.id).tailscaleBean!!.exitNode
                },
            )
            assertNull(client.resultEvents.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun cancelledAdmittedExitAcknowledgesActualCommit() = cancelledAdmittedExit(false)

    @Test
    fun cancelledAdmittedExitAcknowledgesActualRollback() = cancelledAdmittedExit(true)

    @Test
    fun replacedOwnerDoesNotReceiveLateRequestCompletion() = runBlocking {
        val client = Client()
        open(client)
        client.status()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        request(client, 14, "exit") { session, _ ->
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
                publish(session, 14, exitJson(TailscaleExitResult("applied-and-saved", "100.64.0.2")))
            }
        }
        entered.await()
        data.binder.unregisterCallback(client.callback)
        val replacement = Client()
        open(replacement)
        replacement.status()
        release.complete(Unit)
        data.tailscale.drain()
        assertNull(client.resultEvents.poll(100, TimeUnit.MILLISECONDS))
        assertNull(replacement.resultEvents.poll(100, TimeUnit.MILLISECONDS))
    }

    @Test
    fun lifecycleTerminalIsSequencedAndFreshPassiveOwnerSurvivesSameDrain() = runBlocking {
        val first = Client()
        val second = Client()
        open(first)
        val firstStatus = checkNotNull(first.statusEvents.poll(5, TimeUnit.SECONDS))
        val otherMode = newData()
        otherMode.binder.registerCallback(second.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        otherMode.binder.observeTailscale(second.callback, 1, profile.id, profile.uuid)
        second.status()
        val sessionsField = TailscaleSessionController::class.java.getDeclaredField(
            "sessions",
        ).apply { isAccessible = true }
        val oldSession = (sessionsField.get(otherMode.tailscale) as Map<*, *>).values.single()!!
        val owner = oldSession.javaClass.getDeclaredField("job").apply { isAccessible = true }.get(oldSession) as Job
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = CoroutineScope(owner + Dispatchers.IO).launch {
            withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        otherMode.state = BaseService.State.Connecting
        val reobserved = CountDownLatch(1)
        val notices = AtomicInteger()
        first.onStatus = { id, json ->
            if (json.optString("errorCode") == "tailscale:runtime-changed") {
                notices.incrementAndGet()
                if (id == 1L) {
                    data.binder.closeTailscaleSession(first.callback, 1)
                    data.binder.observeTailscale(first.callback, 2, profile.id, profile.uuid)
                    // Also force a fresh owner on the controller whose old native work still drains.
                    otherMode.binder.observeTailscale(second.callback, 2, profile.id, profile.uuid)
                    reobserved.countDown()
                }
            }
        }
        val drain = async(Dispatchers.IO) { TailscaleSessionController.drainAll() }
        try {
            assertTrue(reobserved.await(5, TimeUnit.SECONDS))
            val closed = checkNotNull(first.statusEvents.poll(5, TimeUnit.SECONDS))
            assertEquals(1L, closed.first)
            assertTrue(closed.second > firstStatus.second)
            assertEquals("closed", closed.third.getString("stage"))
            assertEquals("none", closed.third.getString("source"))
            assertTrue(closed.third.isNull("node"))
            assertFalse(drain.isCompleted)
            assertEquals(BaseService.State.Stopped, data.state)
            val waiting = checkNotNull(first.statusEvents.poll(5, TimeUnit.SECONDS))
            assertEquals(2L, waiting.first)
            assertEquals("starting", waiting.third.getString("stage"))
            assertEquals("none", waiting.third.getString("source"))
            assertTrue(waiting.third.isNull("node"))
            assertNull(data.proxy)
            assertNull(otherMode.proxy)
            release.complete(Unit)
            drain.await()
            // No state callback is delivered to the old bound service; only global admission resumes.
            otherMode.state = BaseService.State.Stopped
            TailscaleSessionController.resumeAllAdmission()
            var oldBindingEvent: Triple<Long, Long, JSONObject>
            do {
                oldBindingEvent = checkNotNull(first.statusEvents.poll(5, TimeUnit.SECONDS))
            } while (oldBindingEvent.third.getString("stage") == "starting")
            assertEquals(2L, oldBindingEvent.first)
            assertEquals("not-running", oldBindingEvent.third.getString("stage"))
            assertEquals(1, notices.get())
            assertEquals(BaseService.State.Stopped, data.state)
            var event: Triple<Long, Long, JSONObject>
            do {
                event = checkNotNull(second.statusEvents.poll(5, TimeUnit.SECONDS))
            } while (event.first != 2L || event.third.getString("stage") == "starting")
            assertEquals("not-running", event.third.getString("stage"))
            assertEquals("none", event.third.getString("source"))
            otherMode.binder.pingTailscalePeer(second.callback, 2, 1, "peer", 100)
            val result = checkNotNull(second.resultEvents.poll(5, TimeUnit.SECONDS))
            assertEquals(2L, result.first)
            assertEquals("tailscale:not-running", result.third.getString("errorCode"))
            assertNull(data.proxy)
            assertNull(otherMode.proxy)
        } finally {
            release.complete(Unit)
            drain.join()
            pending.join()
            otherMode.state = BaseService.State.Stopped
            otherMode.binder.close()
        }
    }

    @Test
    fun lifecycleDrainStillJoinsCancelledAdmittedRequest() = runBlocking {
        val client = Client()
        open(client)
        client.status()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val committed = AtomicBoolean()
        request(client, 15, "exit") { session, _ ->
            finalizeTailscaleExit(
                "",
                {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    object : TailscaleExitChange {
                        override fun savedValue() = "100.64.0.2"
                        override fun commit() {
                            committed.set(true)
                        }
                        override fun rollback() = Unit
                    }
                },
                { TailscaleProfileStore.compareAndSetExit(profile.id, profile.uuid, "", it) },
                {},
                {},
                completed = { publish(session, 15, exitJson(it)) },
            )
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        data.binder.cancelTailscaleRequest(client.callback, 1, 15)
        val drain = async(Dispatchers.IO) { data.tailscale.drain() }
        try {
            delay(100)
            assertFalse(drain.isCompleted)
            assertFalse(committed.get())
            release.countDown()
            drain.await()
            assertTrue(committed.get())
            assertEquals(
                "100.64.0.2",
                ConfigBuilderTestEnv.io { TailscaleProfileStore.read(profile.id).tailscaleBean!!.exitNode },
            )
            assertEquals("tailscale:runtime-changed", client.status().getString("errorCode"))
        } finally {
            release.countDown()
            drain.join()
        }
    }

    @Test
    fun explicitFreshAttemptAfterTerminalFailureSeesReleasedTemporaryReservation() {
        val client = Client()
        data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        val reservationAtTerminal = LinkedBlockingQueue<Boolean>()
        client.onStatus = { id, json ->
            if (json.getString("stage") == "error") {
                val field = TailscaleSessionController::class.java.getDeclaredField(
                    "temporaryOwner",
                ).apply { isAccessible = true }
                val registryLock = TailscaleSessionController::class.java.getDeclaredField("registryLock").apply {
                    isAccessible = true
                }.get(null)
                reservationAtTerminal.add(synchronized(registryLock) { field.get(null) == null })
            }
            if (id == 1L && json.getString("stage") == "error") {
                data.binder.closeTailscaleSession(client.callback, 1)
                data.binder.startTailscaleCheck(client.callback, 2, profile.id, "stale-identity")
            }
        }
        data.binder.startTailscaleCheck(client.callback, 1, profile.id, "stale-identity")
        val first = checkNotNull(client.statusEvents.poll(5, TimeUnit.SECONDS))
        val retry = checkNotNull(client.statusEvents.poll(5, TimeUnit.SECONDS))
        assertEquals(1L, first.first)
        assertEquals(2L, retry.first)
        assertEquals("tailscale:unavailable", retry.third.getString("errorCode"))
        // Stale identity fails before the busy check; inspect the reservation itself at delivery.
        assertEquals(true, reservationAtTerminal.poll(5, TimeUnit.SECONDS))
        assertEquals(true, reservationAtTerminal.poll(5, TimeUnit.SECONDS))
        assertNull(client.statusEvents.poll(100, TimeUnit.MILLISECONDS))
        assertNull(data.proxy)
    }

    @Test
    fun nativeDrainDoesNotJoinBlockedStatusBinderDelivery() = runBlocking {
        val client = Client()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        client.onStatus = { _, json ->
            if (json.getString("stage") == "not-running") {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        open(client)
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            withTimeout(2_000) { data.tailscale.drain() }
        } finally {
            release.countDown()
        }
        assertEquals("not-running", client.status().getString("stage"))
        assertEquals("tailscale:runtime-changed", client.status().getString("errorCode"))
    }

    private data class DeliveryCounts(val sessions: Int, val statuses: Int, val results: Int)

    private fun deliveryCounts(): DeliveryCounts {
        val lock = TailscaleSessionController::class.java.getDeclaredField("lock").apply {
            isAccessible = true
        }.get(data.tailscale)
        return synchronized(lock) {
            val sessions = TailscaleSessionController::class.java.getDeclaredField(
                "deliverySessions",
            ).apply { isAccessible = true }
                .get(data.tailscale) as Set<*>
            var statuses = 0
            var results = 0
            for (session in sessions.filterNotNull()) {
                val sender = session.javaClass.getDeclaredField(
                    "delivery",
                ).apply { isAccessible = true }.get(session) as Job
                if (!sender.isCompleted) statuses++
                val requests = session.javaClass.getDeclaredField(
                    "requests",
                ).apply { isAccessible = true }.get(session) as Map<*, *>
                results += requests.size
            }
            DeliveryCounts(sessions.size, statuses, results)
        }
    }

    private suspend fun awaitReleasedDeliveryBudget() = withTimeout(5_000) {
        while (deliveryCounts() != DeliveryCounts(0, 0, 0)) delay(10)
    }

    private fun blockStatus(close: (Client, Int) -> Unit) = runBlocking {
        val client = Client()
        val entered = LinkedBlockingQueue<Unit>()
        val release = CountDownLatch(1)
        client.onStatus = { _, json ->
            if (json.getString("stage") == "not-running") {
                entered.add(Unit)
                check(release.await(15, TimeUnit.SECONDS))
            }
        }
        try {
            repeat(2) { attempt ->
                // Deliberately reuse the same owner and ID: resource accounting must use instances.
                open(client)
                assertNotNull(entered.poll(5, TimeUnit.SECONDS))
                close(client, attempt)
            }
            data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
            for (id in 2L..12L) {
                data.binder.observeTailscale(client.callback, id, profile.id, profile.uuid)
                assertEquals(2, deliveryCounts().sessions)
                data.binder.closeTailscaleSession(client.callback, id)
            }
            withTimeout(2_000) { data.tailscale.drain() }
            assertEquals(DeliveryCounts(2, 2, 0), deliveryCounts())
            assertNull(entered.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        awaitReleasedDeliveryBudget()
        data.tailscale.resumeAdmission()
        client.statuses.clear()
        data.binder.observeTailscale(client.callback, 99, profile.id, profile.uuid)
        assertEquals("not-running", client.status().getString("stage"))
    }

    @Test
    fun blockedStatusCloseAndSameIdReplacementRetainOwnerBudget() = blockStatus { client, _ ->
        data.binder.closeTailscaleSession(client.callback, 1)
    }

    @Test
    fun blockedStatusUnregisterAndDeathRetainOwnerBudget() = blockStatus { client, attempt ->
        if (attempt == 0) {
            data.binder.unregisterCallback(client.callback)
        } else {
            val field = BaseService.Binder::class.java.getDeclaredField("callbacks").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val callbacks = field.get(data.binder) as RemoteCallbackList<ISagerNetServiceCallback>
            callbacks.onCallbackDied(client.callback)
        }
    }

    @Test
    fun sameIdUpgradeRetainsBothBlockedStatusSenders() = runBlocking {
        val client = Client()
        val entered = LinkedBlockingQueue<Unit>()
        val release = CountDownLatch(1)
        client.onStatus = { _, _ ->
            entered.add(Unit)
            check(release.await(15, TimeUnit.SECONDS))
        }
        open(client)
        try {
            assertNotNull(entered.poll(5, TimeUnit.SECONDS))
            data.tailscale.stopAdmission()
            // Upgrade the existing passive ID, but reject native start at the admission barrier.
            data.binder.startTailscaleCheck(client.callback, 1, profile.id, profile.uuid)
            assertNotNull(entered.poll(5, TimeUnit.SECONDS))
            assertEquals(DeliveryCounts(2, 2, 0), deliveryCounts())
            withTimeout(2_000) { data.tailscale.drain() }
            data.binder.closeTailscaleSession(client.callback, 1)
            assertEquals(DeliveryCounts(2, 2, 0), deliveryCounts())
            assertNull(data.proxy)
        } finally {
            release.countDown()
        }
        awaitReleasedDeliveryBudget()
    }

    @Test
    fun blockedLifecycleNoticeRetainsBudgetAfterExplicitClose() = runBlocking {
        val client = Client()
        val entered = LinkedBlockingQueue<Unit>()
        val release = CountDownLatch(1)
        client.onStatus = { _, json ->
            if (json.optString("errorCode") == "tailscale:runtime-changed") {
                entered.add(Unit)
                check(release.await(15, TimeUnit.SECONDS))
            }
        }
        try {
            repeat(2) {
                data.tailscale.resumeAdmission()
                open(client)
                assertEquals("not-running", client.status().getString("stage"))
                withTimeout(2_000) { data.tailscale.drain() }
                assertNotNull(entered.poll(5, TimeUnit.SECONDS))
                data.binder.closeTailscaleSession(client.callback, 1)
                client.statuses.clear()
            }
            data.tailscale.resumeAdmission()
            for (id in 2L..12L) {
                data.binder.observeTailscale(client.callback, id, profile.id, profile.uuid)
                assertEquals(2, deliveryCounts().sessions)
                data.binder.closeTailscaleSession(client.callback, id)
            }
            assertEquals(DeliveryCounts(2, 2, 0), deliveryCounts())
            assertNull(client.statuses.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        awaitReleasedDeliveryBudget()
    }

    @Test
    fun blockedResultsRetainRequestAndSessionBudgetsWithoutHoldingNativeDrain() = runBlocking {
        val client = Client()
        val entered = LinkedBlockingQueue<Unit>()
        val release = CountDownLatch(1)
        client.onResult = { _, _ ->
            entered.add(Unit)
            check(release.await(15, TimeUnit.SECONDS))
        }
        data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        try {
            for (sessionId in 1L..2L) {
                data.binder.observeTailscale(client.callback, sessionId, profile.id, profile.uuid)
                assertEquals("not-running", client.status().getString("stage"))
                for (id in 1L..4L) {
                    data.binder.pingTailscalePeer(client.callback, sessionId, id, "peer", 100)
                    assertNotNull(entered.poll(5, TimeUnit.SECONDS))
                }
                // Completed native Jobs must not free the four still-blocked request senders.
                data.binder.pingTailscalePeer(client.callback, sessionId, 5, "peer", 100)
                assertNull(entered.poll(100, TimeUnit.MILLISECONDS))
                data.binder.closeTailscaleSession(client.callback, sessionId)
            }
            for (id in 3L..12L) {
                data.binder.observeTailscale(client.callback, id, profile.id, profile.uuid)
                assertEquals(2, deliveryCounts().sessions)
                data.binder.closeTailscaleSession(client.callback, id)
            }
            withTimeout(2_000) { data.tailscale.drain() }
            withTimeout(2_000) { while (deliveryCounts().statuses != 0) delay(10) }
            assertEquals(DeliveryCounts(2, 0, 8), deliveryCounts())
            assertNull(entered.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        awaitReleasedDeliveryBudget()
        data.tailscale.resumeAdmission()
        data.binder.observeTailscale(client.callback, 99, profile.id, profile.uuid)
        assertEquals("not-running", client.status().getString("stage"))
        data.binder.pingTailscalePeer(client.callback, 99, 1, "peer", 100)
        assertNotNull(entered.poll(5, TimeUnit.SECONDS))
    }

    @Test
    fun blockedStatusesAcrossOwnersRetainControllerBudget() = runBlocking {
        val entered = LinkedBlockingQueue<Unit>()
        val release = CountDownLatch(1)
        val clients = List(4) {
            Client().also { client ->
                client.onStatus = { _, _ ->
                    entered.add(Unit)
                    check(release.await(15, TimeUnit.SECONDS))
                }
                data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
            }
        }
        try {
            for (client in clients) {
                for (id in 1L..2L) {
                    data.binder.observeTailscale(client.callback, id, profile.id, profile.uuid)
                    assertNotNull(entered.poll(5, TimeUnit.SECONDS))
                    data.binder.closeTailscaleSession(client.callback, id)
                }
            }
            repeat(8) {
                val newcomer = Client()
                data.binder.registerCallback(newcomer.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
                data.binder.observeTailscale(newcomer.callback, 1, profile.id, profile.uuid)
                assertEquals(8, deliveryCounts().sessions)
                data.binder.closeTailscaleSession(newcomer.callback, 1)
                data.binder.unregisterCallback(newcomer.callback)
            }
            withTimeout(2_000) { data.tailscale.drain() }
            assertEquals(DeliveryCounts(8, 8, 0), deliveryCounts())
            assertNull(entered.poll(100, TimeUnit.MILLISECONDS))
        } finally {
            release.countDown()
        }
        awaitReleasedDeliveryBudget()
    }

    @Test
    fun staleIdentityReturnsPrivateErrorWithoutStarting() {
        val client = Client()
        data.binder.registerCallback(client.callback, SagerConnection.CONNECTION_ID_TAILSCALE_STATUS)
        data.binder.observeTailscale(client.callback, 1, profile.id, "old-identity")
        assertEquals("error", client.status().getString("stage"))
        assertNull(data.proxy)
    }
}
