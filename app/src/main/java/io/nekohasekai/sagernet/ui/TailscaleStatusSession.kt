package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.bg.BaseService.State
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

internal data class TailscaleStatusUiState(
    val connected: Boolean = false,
    val serviceState: State = State.Idle,
    val refreshing: Boolean = false,
    val status: TailscaleStatusEnvelope? = null,
    val temporaryRequested: Boolean = false,
    val pending: String? = null,
    val samples: List<TailscalePingSample> = emptyList(),
    val exitOutcome: String? = null,
    val failed: Boolean = false,
) {
    val settled get() = serviceState == State.Stopped || serviceState == State.Connected
    val canCheck get() = connected && serviceState == State.Stopped && !refreshing && !temporaryRequested &&
        status?.source !in setOf("temporary", "running") && status?.stage != "starting"
    val canOperate get() = connected && settled && !refreshing && status?.stage == "observing" && status.node != null && pending == null
    val temporary get() = temporaryRequested || status?.source == "temporary"
}

/** In-memory ownership only; neither consent nor authentication URLs enter saved state. */
internal class TailscaleStatusSession(
    private val transport: TailscaleStatusTransport,
    private val profileId: Long,
    private val identity: String,
) : TailscaleStatusTransport.Listener {
    companion object {
        private val ids = AtomicLong()
    }
    private val mutableState = MutableStateFlow(TailscaleStatusUiState())
    val state = mutableState.asStateFlow()
    private var sessionId = 0L
    private var sequence = -1L
    private var generation = -1L
    private var requestId = 0L
    private var requestedPeer = ""
    private var browserHandoff = false
    private var attached = false
    private var observationPending = false

    fun foreground() {
        browserHandoff = false
        if (!attached) {
            attached = true
            attempt { transport.connect(this) }
        }
    }

    fun background(changingConfiguration: Boolean) {
        if (!changingConfiguration && !browserHandoff) close()
    }

    fun loginLink(): TailscaleLoginLink? = mutableState.value.status?.node?.authUrl?.let(TailscaleLoginLink::parse)

    fun openLogin(expectedLink: TailscaleLoginLink? = null): TailscaleLoginLink? {
        val link = loginLink() ?: return null
        if (expectedLink != null && expectedLink.url != link.url) return null
        browserHandoff = true
        return link
    }

    fun browserFailed() {
        browserHandoff = false
        mutableState.value = mutableState.value.copy(failed = true)
    }

    override fun connected(state: State) {
        if (mutableState.value.connected) {
            serviceState(state)
            return
        }
        mutableState.value = TailscaleStatusUiState(connected = true, serviceState = state)
        observe(refreshing = !mutableState.value.settled)
    }

    override fun disconnected() {
        sessionId = 0
        requestId = 0
        observationPending = false
        browserHandoff = false
        mutableState.value = TailscaleStatusUiState(failed = true)
    }

    override fun serviceState(state: State) {
        val previous = mutableState.value
        if (previous.serviceState == state) return
        mutableState.value = previous.copy(serviceState = state)
        if (!previous.connected) return
        if (!mutableState.value.settled) {
            // Keep a passive transition waiter: another service may own the runtime.
            if (!observationPending) retireOwnership()
            mutableState.value = TailscaleStatusUiState(connected = true, serviceState = state, refreshing = true)
        } else if (!observationPending) {
            observe(refreshing = true)
        }
    }

    private fun retireOwnership() {
        val previous = sessionId
        sessionId = 0
        sequence = -1
        generation = -1
        requestId = 0
        observationPending = false
        browserHandoff = false
        if (previous != 0L) runCatching { transport.close(previous) }
    }

    private fun observe(refreshing: Boolean) {
        retireOwnership()
        sessionId = ids.incrementAndGet()
        observationPending = true
        mutableState.value = TailscaleStatusUiState(
            connected = true,
            serviceState = mutableState.value.serviceState,
            refreshing = refreshing,
        )
        attempt { transport.observe(sessionId, profileId, identity) }
    }

    fun check() {
        if (!mutableState.value.canCheck) return
        retireOwnership()
        sessionId = ids.incrementAndGet()
        mutableState.value = TailscaleStatusUiState(
            connected = true,
            serviceState = mutableState.value.serviceState,
            temporaryRequested = true,
        )
        attempt { transport.start(sessionId, profileId, identity) }
    }

    fun ping(peerId: String) {
        if (!mutableState.value.canOperate || mutableState.value.status?.node?.peers?.none { it.id == peerId } != false) return
        requestId = ids.incrementAndGet()
        requestedPeer = peerId
        mutableState.value = mutableState.value.copy(pending = "ping", samples = emptyList(), failed = false)
        attempt { transport.ping(sessionId, requestId, peerId) }
    }

    fun selectExit(peerId: String, expectedExit: String) {
        val state = mutableState.value
        val status = state.status ?: return
        if (!state.canOperate) return
        if (status.savedExit != expectedExit) {
            mutableState.value = state.copy(exitOutcome = "conflict")
            return
        }
        if (peerId.isNotEmpty() && status.node?.peers?.none { it.id == peerId && it.exitNodeOption } != false) return
        requestId = ids.incrementAndGet()
        mutableState.value = state.copy(pending = "exit", exitOutcome = null, failed = false)
        attempt { transport.selectExit(sessionId, requestId, peerId, expectedExit) }
    }

    fun cancelRequest() {
        if (requestId != 0L) {
            try {
                transport.cancel(sessionId, requestId)
            } catch (_: Exception) {
                mutableState.value = mutableState.value.copy(failed = true)
            }
        }
        // An exit finalizer may already be saving; await its authoritative result.
    }

    fun cancelSession() {
        close()
        foreground()
    }

    fun close() {
        retireOwnership()
        attached = false
        transport.disconnect()
        mutableState.value = TailscaleStatusUiState()
    }

    override fun status(sessionId: Long, sequence: Long, json: String) {
        if (sessionId != this.sessionId || sessionId == 0L || sequence <= this.sequence) return
        val status = runCatching { TailscaleStatusParser.status(json) }.getOrElse {
            mutableState.value = mutableState.value.copy(failed = true)
            return
        }
        if (status.profileId != profileId || status.identity != identity || status.generation < generation) return
        this.sequence = sequence
        if (status.stage == "closed" && status.source == "none" && status.node == null &&
            status.errorCode == "tailscale:runtime-changed"
        ) {
            // Backend retains this lightweight observer across the global admission barrier.
            observe(refreshing = true)
            return
        }
        observationPending = false
        if (!mutableState.value.settled) return
        val changedGeneration = generation >= 0 && generation != status.generation
        generation = status.generation
        val terminal = status.stage in setOf("closed", "error", "not-running")
        if (terminal || changedGeneration) {
            requestId = 0
            browserHandoff = false
        }
        mutableState.value = mutableState.value.copy(
            status = if (terminal) status.copy(node = null, source = "none") else status,
            refreshing = false,
            temporaryRequested = if (terminal || status.source in setOf(
                    "temporary",
                    "running",
                )
            ) {
                false
            } else {
                mutableState.value.temporaryRequested
            },
            pending = if (terminal || changedGeneration) null else mutableState.value.pending,
            samples = if (terminal || changedGeneration) emptyList() else mutableState.value.samples,
            exitOutcome = if (changedGeneration) null else mutableState.value.exitOutcome,
            failed = status.errorCode.isNotEmpty(),
        )
    }

    override fun result(sessionId: Long, requestId: Long, json: String) {
        if (sessionId != this.sessionId || requestId != this.requestId || requestId == 0L) return
        val result = runCatching { TailscaleStatusParser.result(json) }.getOrElse {
            mutableState.value = mutableState.value.copy(failed = true)
            return
        }
        val state = mutableState.value
        when (result) {
            is TailscaleStatusResult.Ping -> {
                if (state.pending != "ping" || (result.sample != null && result.sample.peerId != requestedPeer)) return
                val sample = result.sample?.takeIf { sample -> state.samples.none { it.sequence >= sample.sequence } }
                mutableState.value = state.copy(
                    samples = (state.samples + listOfNotNull(sample)).takeLast(5),
                    pending = if (result.done) null else state.pending,
                    failed = result.error,
                )
                if (result.done) this.requestId = 0
            }

            is TailscaleStatusResult.Exit -> {
                if (state.pending != "exit") return
                this.requestId = 0
                mutableState.value = state.copy(
                    pending = null,
                    exitOutcome = result.outcome,
                    failed = false,
                    status = state.status?.copy(savedExit = result.savedExit),
                )
            }
        }
    }

    private inline fun attempt(action: () -> Unit) {
        try {
            action()
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(
                failed = true,
                refreshing = false,
                temporaryRequested = false,
                pending = null,
            )
            observationPending = false
            requestId = 0
        }
    }
}
