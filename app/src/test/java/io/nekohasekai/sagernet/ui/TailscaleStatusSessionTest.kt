package io.nekohasekai.sagernet.ui

import android.app.Application
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.BaseService.State
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TailscaleStatusSessionTest {
    private class Transport : TailscaleStatusTransport {
        lateinit var listener: TailscaleStatusTransport.Listener
        val calls = mutableListOf<String>()
        var session = 0L
        var request = 0L
        var expectedExit = ""
        val observedSessions = mutableListOf<Long>()
        val startedSessions = mutableListOf<Long>()
        val closedSessions = mutableListOf<Long>()
        var cancelFails = false
        override fun connect(listener: TailscaleStatusTransport.Listener) {
            this.listener = listener
            calls += "connect"
        }
        override fun disconnect() {
            calls += "disconnect"
        }
        override fun observe(sessionId: Long, profileId: Long, identity: String) {
            session = sessionId
            observedSessions += sessionId
            calls += "observe"
        }
        override fun start(sessionId: Long, profileId: Long, identity: String) {
            session = sessionId
            startedSessions += sessionId
            calls += "start"
        }
        override fun close(sessionId: Long) {
            closedSessions += sessionId
            calls += "close"
        }
        override fun ping(sessionId: Long, requestId: Long, peerId: String) {
            request = requestId
            calls += "ping:$peerId"
        }
        override fun selectExit(sessionId: Long, requestId: Long, peerId: String, expectedExit: String) {
            request = requestId
            this.expectedExit = expectedExit
            calls += "exit:$peerId"
        }
        override fun cancel(sessionId: Long, requestId: Long) {
            calls += "cancel"
            if (cancelFails) error("Cancellation delivery failed")
        }
    }

    private fun setup(): Pair<TailscaleStatusSession, Transport> {
        val transport = Transport()
        val session = TailscaleStatusSession(transport, 42, "identity")
        session.foreground()
        transport.listener.connected(State.Stopped)
        return session to transport
    }

    @Test fun passiveOpenAndRepeatedResumeOnlyObserve() {
        val (session, transport) = setup()
        session.foreground()
        assertEquals(listOf("connect", "observe"), transport.calls)
        assertTrue(session.state.value.canCheck)
    }

    @Test fun checkIsExplicitStoppedOnlyAndNeverRepeatedOnResume() {
        val (session, transport) = setup()
        transport.listener.serviceState(State.Connected)
        session.check()
        assertFalse(transport.calls.contains("start"))
        transport.listener.serviceState(State.Stopped)
        session.check()
        session.check()
        session.foreground()
        assertEquals(1, transport.calls.count { it == "start" })
    }

    @Test fun rotationKeepsSessionButOrdinaryBackgroundClosesIt() {
        val (session, transport) = setup()
        session.check()
        val id = transport.session
        val closes = transport.closedSessions.size
        session.background(true)
        session.foreground()
        assertEquals(id, transport.session)
        assertEquals(closes, transport.closedSessions.size)
        session.background(false)
        assertEquals(listOf("close", "disconnect"), transport.calls.takeLast(2))
    }

    @Test fun explicitBrowserHandoffKeepsSessionUntilServerClosesIt() {
        val (session, transport) = setup()
        session.check()
        transport.listener.status(transport.session, 1, status(source = "temporary"))
        val closes = transport.closedSessions.size
        assertNotNull(session.openLogin())
        session.background(false)
        assertEquals(closes, transport.closedSessions.size)
        transport.listener.status(transport.session, 2, status(stage = "closed", source = "temporary"))
        assertFalse(session.state.value.temporary)
        assertNull(session.openLogin())
        session.foreground()
        assertEquals(1, transport.calls.count { it == "start" })
        session.background(false)
        assertTrue(transport.calls.contains("close"))
    }

    @Test fun previewingHttpWarningWithoutConfirmationDoesNotRetainBrowserHandoff() {
        val (session, transport) = setup()
        val status = JSONObject(status(source = "temporary"))
        status.getJSONObject("node").put("authUrl", "http://login.example.test/login?token=fixture")
        transport.listener.status(transport.session, 1, status.toString())
        assertTrue(session.loginLink()!!.isHttp)
        session.background(false)
        assertEquals(listOf("close", "disconnect"), transport.calls.takeLast(2))
    }

    @Test fun confirmedHttpLoginRetainsHandoffUntilOrdinaryBrowserReturn() {
        val (session, transport) = setup()
        val status = JSONObject(status(source = "temporary"))
        status.getJSONObject("node").put("authUrl", "http://login.example.test/login?token=fixture")
        transport.listener.status(transport.session, 1, status.toString())
        val link = session.loginLink()!!
        val closes = transport.closedSessions.size
        assertNotNull(session.openLogin(link))
        session.background(false)
        assertEquals(closes, transport.closedSessions.size)
        session.foreground()
        session.background(false)
        assertEquals(listOf("close", "disconnect"), transport.calls.takeLast(2))
    }

    @Test fun changedLoginLinkCannotConsumeEarlierHttpConfirmation() {
        val (session, transport) = setup()
        val status = JSONObject(status(source = "temporary"))
        status.getJSONObject("node").put("authUrl", "http://login.example.test/login?token=first")
        transport.listener.status(transport.session, 1, status.toString())
        val link = session.loginLink()!!
        status.getJSONObject("node").put("authUrl", "http://login.example.test/login?token=second")
        transport.listener.status(transport.session, 2, status.toString())
        assertNull(session.openLogin(link))
        session.background(false)
        assertEquals(listOf("close", "disconnect"), transport.calls.takeLast(2))
    }

    @Test fun browserReturnDoesNotGrantUnrelatedBackgroundPermission() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status(source = "temporary"))
        session.openLogin()
        session.background(false)
        session.foreground()
        session.background(false)
        assertTrue(transport.calls.contains("close"))
    }

    @Test fun reconnectOnlyObservesAndRejectsOldSessionResults() {
        val (session, transport) = setup()
        session.check()
        val old = transport.session
        transport.listener.disconnected()
        transport.listener.connected(State.Stopped)
        transport.listener.status(old, 50, status())
        assertNull(session.state.value.status)
        assertEquals(1, transport.calls.count { it == "start" })
        assertEquals(2, transport.calls.count { it == "observe" })
    }

    @Test fun staleSequenceGenerationIdentityAndProfileAreIgnored() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 5, status(generation = 2))
        for ((sequence, json) in listOf(
            4L to status(savedExit = "stale"),
            6L to status(generation = 1, savedExit = "stale"),
            7L to JSONObject(status()).put("identity", "other").toString(),
            8L to JSONObject(status()).put("profileId", 99).toString(),
        )) {
            transport.listener.status(transport.session, sequence, json)
        }
        assertEquals("100.64.0.2", session.state.value.status?.savedExit)
        assertEquals(2L, session.state.value.status?.generation)
    }

    @Test fun exitUsesStableIdAndExpectedSavedSelectorAndWaitsForAcknowledgement() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status())
        session.selectExit("peer-stable", "stale")
        assertEquals(0L, transport.request)
        session.selectExit("peer-stable", "100.64.0.2")
        assertEquals("exit:peer-stable", transport.calls.last())
        assertEquals("100.64.0.2", transport.expectedExit)
        assertEquals("exit", session.state.value.pending)
        assertNull(session.state.value.exitOutcome)
        transport.listener.result(transport.session, transport.request + 1, exitResult("applied-and-saved"))
        assertNull(session.state.value.exitOutcome)
        transport.listener.result(transport.session, transport.request, exitResult("conflict"))
        assertEquals("conflict", session.state.value.exitOutcome)
        assertNull(session.state.value.pending)
    }

    @Test fun newNodeGenerationRejectsOldMutationAcknowledgement() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status(generation = 1))
        session.selectExit("peer-stable", "100.64.0.2")
        val oldRequest = transport.request
        transport.listener.status(transport.session, 2, status(generation = 2))
        transport.listener.result(transport.session, oldRequest, exitResult("applied-and-saved"))
        assertNull(session.state.value.exitOutcome)
        assertNull(session.state.value.pending)
    }

    @Test fun explicitCancelClosesAndReobservesWithoutRestarting() {
        val (session, transport) = setup()
        session.check()
        session.cancelSession()
        transport.listener.connected(State.Stopped)
        assertEquals(1, transport.calls.count { it == "start" })
        assertEquals(2, transport.calls.count { it == "observe" })
        assertFalse(session.state.value.temporary)
    }

    @Test fun cancelExitWaitsForFinalizerAndNoneIsExplicit() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status())
        session.selectExit("", "100.64.0.2")
        assertEquals("exit:", transport.calls.last())
        session.cancelRequest()
        assertEquals("exit", session.state.value.pending)
        transport.listener.result(transport.session, transport.request, exitResult("cancelled-before-apply"))
        assertNull(session.state.value.pending)
    }

    @Test fun pingOnlyRunsOnRequestIsBoundedAndRejectsOtherPeers() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status())
        assertFalse(transport.calls.any { it.startsWith("ping") })
        session.ping("peer-stable")
        transport.listener.result(transport.session, transport.request, pingResult(1, "other"))
        assertTrue(session.state.value.samples.isEmpty())
        for (i in 1..8) transport.listener.result(transport.session, transport.request, pingResult(i))
        assertEquals(5, session.state.value.samples.size)
        assertEquals("peer-relay", session.state.value.samples.last().path)
        transport.listener.result(transport.session, transport.request, pingResult(9, done = true))
        assertNull(session.state.value.pending)
    }

    @Test fun processRecreationDoesNotRestoreConsentOrBrowserHandoff() {
        val (_, transport) = setup()
        val newSession = TailscaleStatusSession(transport, 42, "identity")
        newSession.foreground()
        transport.listener.connected(State.Stopped)
        assertFalse(newSession.state.value.temporary)
        assertNull(newSession.openLogin())
        assertFalse(transport.calls.contains("start"))
    }

    @Test fun expiryAndFailureRetryCloseOldOwnershipAndStartExactlyOnceWithFreshCounters() {
        for (stage in listOf("closed", "error")) {
            val (session, transport) = setup()
            session.check()
            val old = transport.session
            transport.listener.status(old, 40, status(source = "temporary", generation = 9))
            transport.listener.status(old, 41, terminal(stage, "tailscale:expired", generation = 9))
            session.foreground()
            assertEquals(listOf(old), transport.startedSessions)
            assertTrue(session.state.value.canCheck)
            session.check()
            val retry = transport.session
            assertTrue(retry > old)
            assertEquals(old, transport.closedSessions.last())
            assertEquals(listOf("close", "start"), transport.calls.takeLast(2))
            assertEquals(1, transport.observedSessions.size)
            session.check()
            assertEquals(listOf(old, retry), transport.startedSessions)
            transport.listener.status(old, 99, status(source = "temporary", generation = 20))
            assertNull(session.state.value.status)
            transport.listener.status(retry, 1, status(source = "temporary", generation = 1))
            assertEquals(1L, session.state.value.status?.generation)
            assertTrue(session.state.value.temporary)
            assertFalse(session.state.value.failed)
        }
    }

    @Test fun visibleStopAndRestartInvalidateNodeAndObserveEachSettledRuntimeOnlyOnce() {
        val (session, transport) = setup()
        transport.listener.serviceState(State.Connected)
        val running = transport.session
        transport.listener.status(running, 20, status(generation = 9))
        session.ping("peer-stable")
        val oldRequest = transport.request
        transport.listener.serviceState(State.Stopping)
        assertNull(session.state.value.status?.node)
        assertFalse(session.state.value.canOperate)
        assertFalse(session.state.value.canCheck)
        assertNull(session.openLogin())
        transport.listener.status(running, 21, status(generation = 9))
        transport.listener.result(running, oldRequest, pingResult(1, done = true))
        assertNull(session.state.value.status?.node)
        assertTrue(session.state.value.samples.isEmpty())
        transport.listener.serviceState(State.Stopping)
        assertEquals(1, transport.observedSessions.size)
        transport.listener.serviceState(State.Stopped)
        val stopped = transport.session
        assertTrue(stopped > running)
        transport.listener.serviceState(State.Stopped)
        transport.listener.status(running, 22, terminal("closed", "tailscale:runtime-changed", 9))
        assertEquals(2, transport.observedSessions.size)
        transport.listener.status(stopped, 1, terminal("not-running"))
        assertTrue(session.state.value.canCheck)
        transport.listener.serviceState(State.Connecting)
        assertFalse(session.state.value.canCheck)
        assertFalse(session.state.value.canOperate)
        transport.listener.serviceState(State.Connecting)
        assertEquals(2, transport.observedSessions.size)
        transport.listener.serviceState(State.Connected)
        val restarted = transport.session
        assertTrue(restarted > stopped)
        transport.listener.serviceState(State.Connected)
        assertEquals(3, transport.observedSessions.size)
        transport.listener.status(restarted, 1, status())
        assertTrue(session.state.value.canOperate)
        assertFalse(session.state.value.canCheck)
        assertTrue(transport.startedSessions.isEmpty())
        assertEquals(1, transport.calls.count { it.startsWith("ping:") })
    }

    @Test fun foreignRuntimeNoticeReobservesImmediatelyEvenWhenBoundServiceStaysStopped() {
        val (session, transport) = setup()
        val old = transport.session
        transport.listener.status(old, 1, status())
        assertFalse(session.state.value.canCheck)
        session.check()
        assertTrue(transport.startedSessions.isEmpty())
        session.selectExit("peer-stable", "100.64.0.2")
        val oldRequest = transport.request
        transport.listener.status(old, 2, terminal("closed", "tailscale:runtime-changed"))
        val replacement = transport.session
        assertTrue(replacement > old)
        assertEquals(listOf("close", "observe"), transport.calls.takeLast(2))
        assertFalse(session.state.value.failed)
        assertFalse(session.state.value.canCheck)
        assertFalse(session.state.value.canOperate)
        transport.listener.status(old, 3, terminal("closed", "tailscale:runtime-changed"))
        transport.listener.result(old, oldRequest, exitResult("applied-and-saved"))
        transport.listener.serviceState(State.Stopped)
        assertEquals(2, transport.observedSessions.size)
        assertNull(session.state.value.exitOutcome)
        transport.listener.status(replacement, 1, status(generation = 2))
        assertTrue(session.state.value.canOperate)
        assertFalse(session.state.value.canCheck)
        assertTrue(transport.startedSessions.isEmpty())
        assertEquals(1, transport.calls.count { it.startsWith("exit:") })
    }

    @Test fun passiveStartingEnvelopeOnOldStoppedBindingShowsConnectionProgressWithoutStartingCheck() {
        val (session, transport) = setup()
        val context = RuntimeEnvironment.getApplication() as Application
        val format = TailscaleStatusFormatting(context)
        val old = transport.session
        transport.listener.status(old, 1, status())
        transport.listener.status(old, 2, terminal("closed", "tailscale:runtime-changed"))
        val waiter = transport.session
        val starting = """
            {"version":1,"profileId":42,"identity":"identity","generation":0,
             "source":"none","stage":"starting","savedExit":"100.64.0.2",
             "node":null,"errorCode":"","message":""}
        """.trimIndent()
        transport.listener.status(waiter, 1, starting)
        assertEquals(State.Stopped, session.state.value.serviceState)
        assertEquals(context.getString(R.string.connecting), format.state(session.state.value))
        assertFalse(session.state.value.temporary)
        assertFalse(session.state.value.failed)
        assertFalse(session.state.value.canCheck)
        assertFalse(session.state.value.canOperate)
        assertNull(session.openLogin())
        session.check()
        session.ping("peer-stable")
        session.selectExit("peer-stable", "100.64.0.2")
        session.background(true)
        session.foreground()
        transport.listener.serviceState(State.Stopped)
        transport.listener.status(waiter, 2, starting)
        assertEquals(waiter, transport.session)
        assertEquals(context.getString(R.string.connecting), format.state(session.state.value))
        assertEquals(listOf("connect", "observe", "close", "observe"), transport.calls)
        assertEquals(listOf(old, waiter), transport.observedSessions)
        assertEquals(listOf(old), transport.closedSessions)
        assertTrue(transport.startedSessions.isEmpty())
        transport.listener.status(waiter, 3, status())
        assertTrue(session.state.value.canOperate)
        assertFalse(session.state.value.canCheck)
        assertEquals(2, transport.observedSessions.size)
        assertTrue(transport.startedSessions.isEmpty())
    }

    @Test fun explicitCheckRetainsTemporaryStartupWordingBeforeAndAfterAcknowledgement() {
        val (session, transport) = setup()
        val context = RuntimeEnvironment.getApplication() as Application
        val format = TailscaleStatusFormatting(context)
        session.check()
        val expected = context.getString(R.string.tailscale_status_starting)
        assertEquals(expected, format.state(session.state.value))
        for ((index, source) in listOf("none", "temporary").withIndex()) {
            val starting = JSONObject(status(stage = "starting", source = source))
                .put("node", JSONObject.NULL).toString()
            transport.listener.status(transport.session, index + 1L, starting)
            assertEquals(expected, format.state(session.state.value))
            assertTrue(session.state.value.temporary)
            assertFalse(session.state.value.canCheck)
            assertFalse(session.state.value.canOperate)
        }
        assertEquals(1, transport.startedSessions.size)
        assertEquals(1, transport.observedSessions.size)
    }

    @Test fun passiveDrainWaiterSurvivesLocalTransitionCallbacksWithoutDuplicateObserve() {
        val (session, transport) = setup()
        transport.listener.serviceState(State.Connected)
        transport.listener.status(transport.session, 1, status())
        transport.listener.status(transport.session, 2, terminal("closed", "tailscale:runtime-changed"))
        val waiter = transport.session
        for (state in listOf(State.Stopping, State.Stopped, State.Connecting, State.Connected)) {
            transport.listener.serviceState(state)
            assertEquals(waiter, transport.session)
            assertFalse(session.state.value.canOperate)
            assertFalse(session.state.value.canCheck)
        }
        assertEquals(2, transport.observedSessions.size)
        assertEquals(1, transport.closedSessions.size)
        transport.listener.status(waiter, 1, status())
        assertTrue(session.state.value.canOperate)
        assertFalse(session.state.value.failed)
        assertTrue(transport.startedSessions.isEmpty())
    }

    @Test fun observationDeliveredDuringTransitionCannotRestoreOldNodeOrLoseSettledObservation() {
        val transport = Transport()
        val session = TailscaleStatusSession(transport, 42, "identity")
        session.foreground()
        transport.listener.connected(State.Connecting)
        val waiter = transport.session
        transport.listener.status(waiter, 1, status())
        assertNull(session.state.value.status?.node)
        assertFalse(session.state.value.canOperate)
        transport.listener.serviceState(State.Connected)
        assertEquals(2, transport.observedSessions.size)
        assertTrue(transport.session > waiter)
        transport.listener.status(transport.session, 1, status())
        assertTrue(session.state.value.canOperate)
        assertTrue(transport.startedSessions.isEmpty())
    }

    @Test fun ordinaryErrorsAndRepeatedConnectionNotificationsNeverLoopObservations() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, terminal("error", "tailscale:busy"))
        assertTrue(session.state.value.failed)
        repeat(3) {
            transport.listener.connected(State.Stopped)
            transport.listener.serviceState(State.Stopped)
            session.foreground()
        }
        assertEquals(1, transport.observedSessions.size)
        assertTrue(transport.startedSessions.isEmpty())
        session.check()
        assertEquals(1, transport.startedSessions.size)
    }

    @Test fun runtimeChangeDuringTemporarySessionOnlyReobservesAndNeverRenews() {
        val (session, transport) = setup()
        session.check()
        val temporary = transport.session
        transport.listener.status(temporary, 1, status(source = "temporary"))
        assertNotNull(session.openLogin())
        transport.listener.status(temporary, 2, terminal("closed", "tailscale:runtime-changed"))
        assertEquals(2, transport.observedSessions.size)
        assertEquals(listOf(temporary), transport.startedSessions)
        assertNull(session.openLogin())
        session.background(false)
        assertEquals(listOf("close", "disconnect"), transport.calls.takeLast(2))
    }

    @Test fun cancellationDeliveryFailureDoesNotEnableAnotherMutationBeforeFinalizerResult() {
        for (outcome in listOf("cancelled-before-apply", "applied-and-saved", "failed-rolled-back")) {
            val (session, transport) = setup()
            transport.listener.status(transport.session, 1, status())
            session.selectExit("peer-stable", "100.64.0.2")
            transport.cancelFails = true
            session.cancelRequest()
            assertEquals("exit", session.state.value.pending)
            assertFalse(session.state.value.canOperate)
            session.selectExit("", "100.64.0.2")
            assertEquals(1, transport.calls.count { it.startsWith("exit:") })
            transport.listener.result(transport.session, transport.request, exitResult(outcome))
            assertEquals(outcome, session.state.value.exitOutcome)
            assertNull(session.state.value.pending)
        }
    }

    @Test fun cancelledPingWaitsForOwnedTerminalCompletion() {
        val (session, transport) = setup()
        transport.listener.status(transport.session, 1, status())
        session.ping("peer-stable")
        session.cancelRequest()
        assertEquals("ping", session.state.value.pending)
        assertFalse(session.state.value.canOperate)
        transport.listener.result(
            transport.session,
            transport.request,
            """{"kind":"ping","done":true,"sample":null,"errorCode":"tailscale:cancelled","message":""}""",
        )
        assertNull(session.state.value.pending)
        assertTrue(session.state.value.canOperate)
    }

    companion object {
        private fun terminal(stage: String, errorCode: String = "", generation: Long = 1): String = JSONObject(status(stage = stage, source = "none", generation = generation))
            .put("node", JSONObject.NULL).put("errorCode", errorCode).toString()

        internal fun status(
            stage: String = "observing",
            source: String = "running",
            generation: Long = 1,
            savedExit: String = "100.64.0.2",
        ): String = """
            {"version":1,"profileId":42,"identity":"identity","generation":$generation,
             "source":"$source","stage":"$stage","savedExit":"$savedExit","errorCode":"","message":"",
             "node":{"backendState":"Running","needsLogin":true,"needsApproval":true,
               "authUrl":"https://login.example.test/a?token=secret","keyAuth":false,"self":null,
               "currentExit":{"id":"peer-stable","ip":"100.64.0.2","live":true},
               "peers":[{"id":"peer-stable","name":"Example peer","dnsName":"example.test","ips":["100.64.0.2"],
                 "online":false,"expired":false,"keyExpiry":0,"exitNodeOption":true,"exitNodeSelected":true}],
               "totalPeers":300,"peersTruncated":true}}
        """.trimIndent()
        private fun exitResult(outcome: String) = """{"kind":"exit","outcome":"$outcome","savedExit":"100.64.0.2","errorCode":"","message":""}"""
        private fun pingResult(sequence: Int, peerId: String = "peer-stable", done: Boolean = false) = """
            {"kind":"ping","done":$done,"errorCode":"","message":"","sample":{
            "peerId":"$peerId","peerIp":"100.64.0.2","sequence":$sequence,"latencyMs":12.5,
            "path":"peer-relay","derpRegionId":0,"derpRegionCode":"","error":""}}
        """.trimIndent()
    }
}
