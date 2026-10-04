package io.nekohasekai.sagernet.bg

import android.os.IBinder
import android.os.SystemClock
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.BoxInstance
import io.nekohasekai.sagernet.bg.proto.TAILSCALE_LOGIN_TIMEOUT_MS
import io.nekohasekai.sagernet.bg.proto.TailscaleAccess
import io.nekohasekai.sagernet.bg.proto.TailscaleReadinessIntent
import io.nekohasekai.sagernet.bg.proto.TailscaleSessionInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.TailscaleProfileStore
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import libcore.Libcore
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/** Owns only Tailscale management resources, never the running service's box. */
internal class TailscaleSessionController(
    private val data: BaseService.Data,
    private val registered: (IBinder) -> Boolean,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = mutableMapOf<Pair<IBinder, Long>, Session>()
    private val retiringSessions = mutableMapOf<Pair<IBinder, Long>, Session>()

    // Eight retained sessions per controller, two per Binder owner, each with at most four
    // request senders. Authority can end immediately; a blocked Binder call still owns its slot.
    private val deliverySessions = mutableSetOf<Session>()

    // Retain closing jobs until their finalizers complete so lifecycle drains cannot miss them.
    private val closing = mutableSetOf<Job>()
    private var destroyed = false
    private var draining = false

    init {
        synchronized(registryLock) {
            draining = controllers.any { it.data.state == BaseService.State.Connecting || it.data.state == BaseService.State.Stopping }
            controllers.add(this)
        }
    }

    // A service-mode handoff can leave both service objects bound in this process.
    companion object {
        private val registryLock = Any()
        private val controllers = mutableSetOf<TailscaleSessionController>()
        private val mutation = Mutex()
        private var temporaryOwner: Session? = null
        private fun snapshot() = synchronized(registryLock) { controllers.toList() }

        fun stopAllAdmission() {
            snapshot().forEach { it.stopAdmission() }
        }

        fun resumeAllAdmission() {
            val current = snapshot()
            if (current.none { it.data.state == BaseService.State.Connecting || it.data.state == BaseService.State.Stopping }) {
                current.forEach { it.resumeAdmission() }
            }
        }

        suspend fun drainAll() = withContext(NonCancellable + Dispatchers.IO) {
            // Capture every old owner before any terminal callback can open a replacement.
            val batches = snapshot().map { it to it.beginDrain() }
            batches.forEach { (controller, batch) -> controller.finishDrain(batch) }
        }
    }

    private fun managementState(): BaseService.State {
        val states = snapshot().map { it.data.state }
        return states.firstOrNull { it == BaseService.State.Stopping || it == BaseService.State.Connecting }
            ?: states.firstOrNull { it == BaseService.State.Connected } ?: BaseService.State.Stopped
    }

    private fun runningData(): BaseService.Data? = DataStore.baseService?.data?.takeIf { it.state == BaseService.State.Connected }
        ?: snapshot().firstOrNull { it.data.state == BaseService.State.Connected }?.data

    private class Session(val cb: ISagerNetServiceCallback, val id: Long, val profileId: Long, val identity: String, val temporary: Boolean, val sequence: AtomicLong) {
        val key = cb.asBinder() to id
        lateinit var job: Job
        val statuses = Channel<StatusDelivery>(Channel.CONFLATED)
        lateinit var delivery: Job
        val requests = mutableMapOf<Long, Request>()
        var lastRequest = 0L
        var accepting = true

        @Volatile var completionStatus: StatusDelivery? = null

        @Volatile var target: Target? = null

        @Volatile var generation = 0L

        @Volatile var source = "none"

        @Volatile var savedExit = ""
    }

    private data class StatusDelivery(val json: String, val terminal: Boolean = false)

    private class Request(val kind: String) {
        lateinit var job: Job
        lateinit var delivery: Job

        // Native ping emits at most five samples, followed by one terminal acknowledgement.
        val events = Channel<JSONObject>(6)
        var terminal: JSONObject? = null
    }

    private data class Drain(val sessions: List<Session>, val jobs: List<Job>)

    private data class Target(val owner: BaseService.Data, val instance: BoxInstance, val snapshot: ProxyEntity, val tag: String, val intent: TailscaleReadinessIntent, val temporary: Boolean) {
        val generation get() = intent.generation
    }

    fun open(cb: ISagerNetServiceCallback, sessionId: Long, profileId: Long, identity: String, temporary: Boolean) {
        synchronized(lock) {
            if (destroyed || !registered(cb.asBinder()) || sessionId <= 0 || profileId <= 0 || identity.length > 128) return
            val mayStartTemporary = temporary && !draining && managementState() == BaseService.State.Stopped
            val key = cb.asBinder() to sessionId
            if (retiringSessions.containsKey(key)) return
            val previous = sessions[key]
            if (previous != null) {
                if (previous.profileId != profileId || previous.identity != identity) return
                if (!temporary || previous.temporary || previous.target != null) return
            }
            if (deliverySessions.size >= 8 || deliverySessions.count { it.key.first == cb.asBinder() } >= 2) return
            previous?.let { retire(it) }
            val s = Session(cb, sessionId, profileId, identity, temporary, previous?.sequence ?: AtomicLong())
            deliverySessions.add(s)
            val temporaryBusy = mayStartTemporary && synchronized(registryLock) {
                if (temporaryOwner != null) {
                    true
                } else {
                    temporaryOwner = s
                    false
                }
            }
            sessions[key] = s
            s.delivery = scope.launch(start = CoroutineStart.LAZY) {
                for (event in s.statuses) {
                    val owned = synchronized(lock) {
                        !destroyed && registered(s.key.first) &&
                            if (event.terminal) retiringSessions[s.key] === s else sessions[s.key] === s
                    }
                    if (owned) runCatching { s.cb.cbTailscaleStatus(s.id, s.sequence.incrementAndGet(), event.json) }
                }
            }
            s.delivery.invokeOnCompletion {
                synchronized(lock) {
                    if (retiringSessions[s.key] === s) retiringSessions.remove(s.key)
                    releaseDeliveryBudget(s)
                }
            }
            s.job = scope.launch(start = CoroutineStart.LAZY) {
                supervisorScope {
                    try {
                        previous?.job?.join()
                        val profile = read(s)
                        s.savedExit = profile.tailscaleBean!!.exitNode.orEmpty()
                        if (temporaryBusy) {
                            status(s, "error", code = "tailscale:busy")
                            return@supervisorScope
                        }
                        if (temporary && !mayStartTemporary && managementState() != BaseService.State.Connected) {
                            status(s, "error", code = "tailscale:not-stopped")
                            return@supervisorScope
                        }
                        while (!temporary && (synchronized(lock) { draining } || managementState() == BaseService.State.Connecting || managementState() == BaseService.State.Stopping)) {
                            status(s, "starting")
                            delay(250)
                        }
                        val running = runningData()
                        val proxy = running?.proxy
                        if (running != null && proxy != null && proxy.isInitialized()) {
                            val endpoint = proxy.config.tailscaleEndpoints[profileId]
                            if (endpoint == null) {
                                status(s, "not-running")
                                awaitCancellation()
                            }
                            val snapshot = proxy.config.trafficMap.values.flatten().first { it.id == profileId }
                            checkRuntime(s, snapshot)
                            observe(s, Target(running, proxy, snapshot, endpoint.tag, proxy.tailscaleReadiness, false))
                        } else if (mayStartTemporary && managementState() == BaseService.State.Stopped) {
                            status(s, "starting")
                            withTimeout(TAILSCALE_LOGIN_TIMEOUT_MS) {
                                TailscaleAccess.whileStopped(::managementState) {
                                    TailscaleSessionInstance(read(s)).runSession { instance ->
                                        val endpoint = instance.config.tailscaleEndpoints.getValue(profileId)
                                        observe(s, Target(data, instance, instance.profile, endpoint.tag, TailscaleReadinessIntent(), true))
                                    }
                                }
                            }
                        } else {
                            status(s, if (managementState() == BaseService.State.Stopped) "not-running" else "starting")
                            awaitCancellation()
                        }
                    } catch (_: TimeoutCancellationException) {
                        status(s, "closed", code = "tailscale:expired")
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // The status reply carries only a code; keep the cause for diagnosis.
                        Logs.w("Tailscale status session failed", e)
                        status(s, "error", code = "tailscale:unavailable")
                    } finally {
                        withContext(NonCancellable) {
                            synchronized(lock) { s.accepting = false }
                            drainRequests(s)
                            s.target = null
                        }
                    }
                }
            }
            s.job.invokeOnCompletion {
                synchronized(registryLock) { if (temporaryOwner === s) temporaryOwner = null }
                s.completionStatus?.let { s.statuses.trySend(it) }
                synchronized(lock) { releaseDeliveryBudget(s) }
            }
            s.delivery.start()
            s.job.start()
        }
    }

    private fun current(s: Session): Boolean = synchronized(lock) {
        !destroyed && sessions[s.key] === s && registered(s.key.first)
    }

    private fun read(s: Session): ProxyEntity = TailscaleProfileStore.read(s.profileId).also {
        check(it.uuid == s.identity && it.tailscaleBean!!.exitNode.orEmpty().length <= 4096) { "tailscale:conflict" }
    }

    private fun checkRuntime(s: Session, snapshot: ProxyEntity): ProxyEntity = read(s).also {
        check(snapshot.uuid == s.identity && TailscaleProfileStore.sameRuntime(it.tailscaleBean!!, snapshot.tailscaleBean!!)) { "tailscale:conflict" }
    }

    private fun valid(s: Session, t: Target): Boolean = current(s) && s.target === t &&
        if (t.temporary) {
            managementState() == BaseService.State.Stopped
        } else {
            t.owner.state == BaseService.State.Connected && t.owner.proxy === t.instance
        }

    private suspend fun observe(s: Session, target: Target) {
        synchronized(lock) {
            check(!draining && current(s)) { "tailscale:closing" }
            s.target = target
            s.generation = target.generation
            s.source = if (target.temporary) "temporary" else "running"
        }
        try {
            val stream = Libcore.observeTailscaleStatus(target.instance.box, target.tag)
            try {
                status(s, "observing", Libcore.tailscaleStatus(target.instance.box, target.tag))
                while (currentCoroutineContext()[Job]?.isActive != false && valid(s, target)) {
                    val json = stream.next(500)
                    if (json.isNotEmpty()) {
                        s.savedExit = checkRuntime(s, target.snapshot).tailscaleBean!!.exitNode.orEmpty()
                        status(s, "observing", json)
                    }
                }
            } finally {
                stream.close()
            }
        } finally {
            // Requests are children of the owner, not runProbe. Join them before its box closes.
            synchronized(lock) { s.accepting = false }
            withContext(NonCancellable) { drainRequests(s) }
            s.target = null
        }
    }

    private fun status(s: Session, stage: String, node: String? = null, code: String = "", terminal: Boolean = false) {
        val json = JSONObject().put("version", 1).put("profileId", s.profileId).put("identity", s.identity)
            .put("generation", s.generation).put("source", if (terminal) "none" else s.source)
            .put("stage", stage).put("savedExit", s.savedExit).put("node", node?.let { JSONObject(it) } ?: JSONObject.NULL)
            .put("errorCode", code).put("message", "").toString()
        val event = StatusDelivery(json, terminal)
        if (!terminal && (stage == "closed" || stage == "error")) {
            // A fresh explicit check may follow immediately; release native ownership first.
            s.completionStatus = event
        } else {
            s.statuses.trySend(event)
        }
    }

    private fun result(s: Session, requestId: Long, json: JSONObject) {
        synchronized(lock) {
            val request = s.requests[requestId] ?: return
            if (request.terminal != null) return
            if (json.getString("kind") == "exit" || json.optBoolean("done")) {
                // Retain the authoritative outcome even when cancellation made the Job inactive.
                request.terminal = json
            } else if (request.job.isActive) {
                request.events.trySend(json)
            }
        }
    }

    private fun request(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long, kind: String, block: suspend (Session, Target?) -> Unit) {
        synchronized(lock) {
            val s = sessions[cb.asBinder() to sessionId] ?: return
            if (destroyed || draining || !s.accepting || !registered(cb.asBinder()) || !s.job.isActive || requestId <= s.lastRequest || s.requests.size >= 4) return
            val target = s.target
            if (target != null && !valid(s, target)) return
            s.lastRequest = requestId
            val request = Request(kind)
            s.requests[requestId] = request
            request.delivery = scope.launch(start = CoroutineStart.LAZY) {
                for (json in request.events) {
                    val owned = synchronized(lock) { current(s) && s.requests[requestId] === request }
                    if (owned) runCatching { s.cb.cbTailscaleResult(s.id, requestId, json.toString()) }
                }
            }
            request.delivery.invokeOnCompletion {
                synchronized(lock) {
                    if (s.requests[requestId] === request) s.requests.remove(requestId)
                    releaseDeliveryBudget(s)
                }
            }
            request.job = scope.launch(s.job + Dispatchers.IO, start = CoroutineStart.LAZY) { block(s, target) }
            request.job.invokeOnCompletion {
                // Also runs when a queued lazy body never entered. No Binder call in this handler.
                scope.launch {
                    val terminal = synchronized(lock) {
                        request.terminal ?: if (request.kind == "ping") {
                            pingResult(true, code = "tailscale:cancelled")
                        } else {
                            exitResult(TailscaleExitResult("cancelled-before-apply", s.savedExit))
                        }
                    }
                    request.events.trySend(terminal)
                    request.events.close()
                }
            }
            request.delivery.start()
            request.job.start()
        }
    }

    fun ping(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long, peerId: String, timeoutMs: Int) {
        if (peerId.length > 256) return
        request(cb, sessionId, requestId, "ping") { s, t ->
            if (t == null) {
                result(s, requestId, pingResult(true, code = "tailscale:not-running"))
                return@request
            }
            var code = ""
            try {
                checkRuntime(s, t.snapshot)
                val timeout = timeoutMs.coerceIn(1, 10_000)
                val stream = Libcore.startTailscalePeerPing(t.instance.box, t.tag, peerId, timeout)
                try {
                    val deadline = SystemClock.elapsedRealtime() + timeout
                    var samples = 0
                    while (samples < 5 && SystemClock.elapsedRealtime() < deadline && valid(s, t)) {
                        currentCoroutineContext().ensureActive()
                        val remaining = (deadline - SystemClock.elapsedRealtime()).coerceIn(1, 500).toInt()
                        val json = stream.next(remaining)
                        if (json.isNotEmpty()) {
                            samples++
                            result(s, requestId, pingResult(false, JSONObject(json)))
                        }
                    }
                    if (samples < 5 && SystemClock.elapsedRealtime() >= deadline) code = "tailscale:ping-timeout"
                } finally {
                    stream.close()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message != "EOF") code = "tailscale:ping-failed"
            }
            result(s, requestId, pingResult(true, code = code))
        }
    }

    private fun pingResult(done: Boolean, sample: JSONObject? = null, code: String = "") = JSONObject()
        .put("kind", "ping").put("done", done).put("sample", sample ?: JSONObject.NULL).put("errorCode", code).put("message", "")

    fun setExit(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long, peerId: String, expectedExit: String) {
        if (peerId.length > 256 || expectedExit.length > 4096) return
        request(cb, sessionId, requestId, "exit") { s, t ->
            if (t == null) {
                result(
                    s,
                    requestId,
                    JSONObject().put("kind", "exit").put("outcome", "failed-unchanged")
                        .put("savedExit", s.savedExit).put("errorCode", "tailscale:not-running").put("message", ""),
                )
                return@request
            }
            val outcome = mutation.withLock {
                currentCoroutineContext().ensureActive()
                if (!valid(s, t)) return@withLock TailscaleExitResult("cancelled-before-apply", s.savedExit)
                val profile = try {
                    checkRuntime(s, t.snapshot).also {
                        check(it.tailscaleBean!!.exitNode.orEmpty() == expectedExit) { "tailscale:conflict" }
                    }
                } catch (_: Exception) {
                    return@withLock TailscaleExitResult("conflict", s.savedExit, "tailscale:conflict")
                }
                val oldIntent = t.intent.get(s.profileId, t.snapshot.tailscaleBean!!.exitNode.orEmpty().isNotEmpty())
                // This is the application admission point shared with stop and owner cleanup.
                val admitted = synchronized(lock) { !draining && valid(s, t) && s.requests[requestId]?.job?.isActive == true }
                if (!admitted) return@withLock TailscaleExitResult("cancelled-before-apply", s.savedExit)
                finalizeTailscaleExit(
                    profile.tailscaleBean!!.exitNode.orEmpty(),
                    begin = {
                        val change = Libcore.setTailscaleExitNode(t.instance.box, t.tag, peerId)
                        object : TailscaleExitChange {
                            override fun savedValue() = change.savedValue()
                            override fun commit() {
                                change.commit()
                            }
                            override fun rollback() {
                                change.rollback()
                            }
                        }
                    },
                    save = { value ->
                        TailscaleProfileStore.compareAndSetRuntimeExit(s.profileId, s.identity, expectedExit, value, t.snapshot.tailscaleBean)
                        s.savedExit = value
                    },
                    applied = { t.intent.set(s.profileId, it) },
                    rolledBack = { t.intent.set(s.profileId, oldIntent) },
                    completed = { finishExit(s, requestId, t, it) },
                )
            }
            if (synchronized(lock) { s.requests[requestId]?.terminal == null }) finishExit(s, requestId, t, outcome)
        }
    }

    private fun exitResult(outcome: TailscaleExitResult) = JSONObject().put("kind", "exit").put("outcome", outcome.outcome)
        .put("savedExit", outcome.savedExit).put("errorCode", outcome.errorCode).put("message", "")

    private fun finishExit(s: Session, requestId: Long, t: Target, outcome: TailscaleExitResult) {
        // Refresh both sides after success, conflict or divergence, without restarting anything.
        runCatching {
            s.savedExit = read(s).tailscaleBean!!.exitNode.orEmpty()
            val actual = Libcore.tailscaleStatus(t.instance.box, t.tag)
            if (outcome.outcome == "diverged") {
                t.intent.set(s.profileId, !JSONObject(actual).isNull("currentExit"))
            }
            val observers = synchronized(lock) {
                sessions.values.filter { it.profileId == s.profileId && it.identity == s.identity && it.target?.instance === t.instance }
            }
            for (observer in observers) {
                observer.savedExit = s.savedExit
                status(observer, "observing", actual)
            }
        }
        val name = if (outcome.outcome == "applied-and-saved" && !valid(s, t)) "saved-for-next-start" else outcome.outcome
        result(s, requestId, exitResult(outcome.copy(outcome = name, savedExit = s.savedExit)))
    }

    fun cancelRequest(cb: ISagerNetServiceCallback, sessionId: Long, requestId: Long) {
        synchronized(lock) { sessions[cb.asBinder() to sessionId]?.requests?.get(requestId)?.job?.cancel() }
    }

    fun closeSession(cb: ISagerNetServiceCallback, sessionId: Long) {
        synchronized(lock) {
            val key = cb.asBinder() to sessionId
            sessions[key]?.let { retire(it) }
            retiringSessions.remove(key)?.let {
                it.statuses.close()
                it.delivery.cancel()
            }
        }
    }

    fun releaseOwner(owner: IBinder) {
        synchronized(lock) {
            sessions.values.filter { it.key.first == owner }.forEach { retire(it) }
            retiringSessions.values.filter { it.key.first == owner }.toList().forEach {
                retiringSessions.remove(it.key)
                it.statuses.close()
                it.delivery.cancel()
            }
        }
    }

    private fun retire(s: Session, lifecycle: Boolean = false) {
        s.accepting = false
        sessions.remove(s.key)
        if (lifecycle) {
            retiringSessions[s.key] = s
        } else {
            s.statuses.close()
            s.delivery.cancel()
        }
        closing.add(s.job)
        s.job.invokeOnCompletion { synchronized(lock) { closing.remove(s.job) } }
        s.job.cancel()
    }

    // Called under lock only by completion handlers, never by cancellation or authority removal.
    private fun releaseDeliveryBudget(s: Session) {
        if (sessions[s.key] !== s && retiringSessions[s.key] !== s &&
            s.job.isCompleted && s.delivery.isCompleted && s.requests.isEmpty()
        ) {
            deliverySessions.remove(s)
        }
    }

    private suspend fun drainRequests(s: Session) {
        val jobs = synchronized(lock) { s.requests.values.map { it.job }.also { jobs -> jobs.forEach { it.cancel() } } }
        jobs.joinAll()
    }

    fun stopAdmission() {
        synchronized(lock) { draining = true }
    }

    fun resumeAdmission() {
        synchronized(lock) { if (!destroyed) draining = false }
    }

    private fun beginDrain(): Drain = synchronized(lock) {
        draining = true
        val old = sessions.values.toList()
        old.forEach { retire(it, lifecycle = !destroyed) }
        Drain(old, closing.toList())
    }

    private suspend fun finishDrain(batch: Drain) {
        batch.jobs.joinAll()
        for (s in batch.sessions) {
            // A single sender orders the terminal after old status delivery, without joining Binder.
            status(s, "closed", code = "tailscale:runtime-changed", terminal = true)
            s.statuses.close()
        }
    }

    suspend fun drain() = withContext(NonCancellable + Dispatchers.IO) {
        finishDrain(beginDrain())
    }

    fun destroy() {
        synchronized(lock) {
            destroyed = true
            draining = true
        }
        scope.launch {
            try {
                drain()
            } finally {
                synchronized(registryLock) { controllers.remove(this@TailscaleSessionController) }
                scope.coroutineContext[Job]?.cancel()
            }
        }
    }
}
