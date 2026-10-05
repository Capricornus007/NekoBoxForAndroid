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
class TailscaleStatusModelsTest {
    private val context get() = RuntimeEnvironment.getApplication() as Application
    private val format get() = TailscaleStatusFormatting(context)

    @Test fun loginLinkValidatesHttpHostAndShowsOnlyOrigin() {
        val link = TailscaleLoginLink.parse("https://login.example.test:8443/signin?token=secret#fragment")!!
        assertEquals("https://login.example.test:8443", link.origin)
        assertFalse(link.toString().contains("secret"))
        assertFalse(link.isHttp)
        assertTrue(TailscaleLoginLink.parse("http://localhost:8080/login")!!.isHttp)
        for (url in listOf(
            "javascript:alert(1)", "file:///secret", "https:///no-host", "https://user:pass@example.test/",
            "https://example.test\\@other.test", "https://example.test/\nsecret", "https://example.test/%0d%0a",
            "https://example.test/%7F", "https://example.test:99999/", "https://example.test/ white",
            " https://example.test/", "https://example.test\u0000/", "https://example.test:0/",
        )) {
            assertNull(url, TailscaleLoginLink.parse(url))
        }
    }

    @Test fun parserRetainsIncompleteInventoryAuthApprovalAndExpiryUnknown() {
        val status = TailscaleStatusParser.status(TailscaleStatusSessionTest.status())
        val node = status.node!!
        assertTrue(node.needsLogin)
        assertTrue(node.needsApproval)
        assertTrue(node.peersTruncated)
        assertEquals(300, node.totalPeers)
        assertEquals("peer-stable", node.currentExit?.id)
        assertEquals(context.getString(R.string.tailscale_status_expiry_unknown), format.expiry(node.peers.single()))
        assertFalse(node.savedExitDiffers("100.64.0.2"))
        assertFalse(node.savedExitDiffers("Example peer"))
        assertTrue(node.savedExitDiffers("100.64.0.3"))
    }

    @Test fun stateAndPeerFormattingUseTextForApprovalOfflineAndExpiry() {
        val status = TailscaleStatusParser.status(TailscaleStatusSessionTest.status())
        val rendered = format.state(TailscaleStatusUiState(connected = true, status = status))
        assertTrue(rendered.contains(context.getString(R.string.tailscale_login_required)))
        assertTrue(rendered.contains(context.getString(R.string.tailscale_status_approval)))
        val peer = status.node!!.peers.single()
        assertTrue(format.peer(peer).contains(context.getString(R.string.tailscale_status_offline)))
        assertEquals(context.getString(R.string.tailscale_status_expired), format.expiry(peer.copy(expired = true)))
        assertEquals(context.getString(R.string.tailscale_status_expiry_unknown), format.expiry(peer.copy(keyExpiry = -1)))
        assertEquals(context.getString(R.string.tailscale_status_expiry_unknown), format.expiry(peer.copy(keyExpiry = Long.MAX_VALUE)))
        assertNotEquals(format.expiry(peer), format.expiry(peer.copy(keyExpiry = 1_900_000_000)))
    }

    @Test fun unknownAndPeerRelayPathsRemainHonestAndErrorsDropLatency() {
        fun result(path: String, error: String = "") = TailscaleStatusParser.result(
            """
            {"kind":"ping","done":true,"errorCode":"","sample":{"peerId":"p","peerIp":"100.64.0.2",
            "sequence":1,"latencyMs":4.2,"path":"$path","derpRegionId":4,"derpRegionCode":"test","error":"$error"}}
        """,
        ) as TailscaleStatusResult.Ping
        assertEquals("unknown", result("future-path").sample!!.path)
        assertEquals("peer-relay", result("peer-relay").sample!!.path)
        val failed = result("direct", "timeout").sample!!
        assertEquals("unknown", failed.path)
        assertNull(failed.latencyMs)
        assertTrue(format.sample(result("peer-relay").sample!!).contains(context.getString(R.string.tailscale_status_path_peer_relay)))
        assertTrue(format.sample(result("unknown").sample!!).contains(context.getString(R.string.tailscale_status_path_unknown)))
    }

    @Test fun unsupportedVersionAndOversizedInventoryFailClosed() {
        val json = JSONObject(TailscaleStatusSessionTest.status())
        assertTrue(runCatching { TailscaleStatusParser.status(json.put("version", 2).toString()) }.isFailure)
        json.put("version", 1)
        val peers = json.getJSONObject("node").getJSONArray("peers")
        val peer = peers.getJSONObject(0)
        repeat(256) { peers.put(peer) }
        assertTrue(runCatching { TailscaleStatusParser.status(json.toString()) }.isFailure)
        assertTrue(runCatching { TailscaleStatusParser.status(" ".repeat(133 * 1024)) }.isFailure)
    }

    @Test fun serviceTransitionsAndPassiveRefreshRenderAsProgressNotStaleNodeState() {
        val status = TailscaleStatusParser.status(TailscaleStatusSessionTest.status())
        val state = TailscaleStatusUiState(connected = true, serviceState = State.Connected, status = status)
        assertEquals(context.getString(R.string.stopping), format.state(state.copy(serviceState = State.Stopping)))
        assertEquals(context.getString(R.string.connecting), format.state(state.copy(serviceState = State.Connecting)))
        assertEquals(context.getString(R.string.connecting), format.state(state.copy(serviceState = State.Stopped, refreshing = true)))
        assertEquals(context.getString(R.string.tailscale_status_stopped), format.state(state.copy(serviceState = State.Stopped, status = null)))
    }

    @Test fun onlyDurableExitOutcomesRenderAsSuccess() {
        assertEquals(context.getString(R.string.tailscale_status_exit_saved), format.exitOutcome("applied-and-saved"))
        assertEquals(context.getString(R.string.tailscale_status_exit_diverged), format.exitOutcome("diverged"))
        assertEquals(context.getString(R.string.tailscale_status_error), format.exitOutcome("success"))
    }
}
