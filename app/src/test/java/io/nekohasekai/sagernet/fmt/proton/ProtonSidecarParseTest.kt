package io.nekohasekai.sagernet.fmt.proton

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// org.json is an Android framework class, so it is stubbed on the bare JVM.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ProtonSidecarParseTest {

    @Test
    fun parsesASuccessfulLogin() {
        val state = ProtonJson.parseLogin(
            """{"ok":true,"uid":"uid-1","userId":"user-1","credentialFile":"/data/no-backup/proton-session.json"}""",
        )
        assertTrue(state.ok)
        assertEquals("uid-1", state.uid)
        assertFalse(state.twoFactorRequired)
        assertEquals("", state.error)
    }

    @Test
    fun keepsTheTwoFactorSignalSeparateFromAGenericError() {
        val state = ProtonJson.parseLogin(
            """{"ok":false,"twoFactorRequired":true,"reason":"two-factor-required","error":"this account has two-factor authentication enabled"}""",
        )
        assertFalse(state.ok)
        assertTrue("the UI needs this to re-prompt instead of showing a blob", state.twoFactorRequired)
        assertEquals("two-factor-required", state.reason)
    }

    @Test
    fun mapsAWrongPasswordToItsReason() {
        val state = ProtonJson.parseLogin(
            """{"ok":false,"reason":"wrong-password","code":8002,"error":"Wrong credentials"}""",
        )
        assertFalse(state.ok)
        assertEquals("wrong-password", state.reason)
        assertFalse(state.twoFactorRequired)
    }

    @Test
    fun garbageFromTheSidecarBecomesAnErrorNotACrash() {
        assertFalse(ProtonJson.parseLogin("not json at all").ok)
        assertEquals("sidecar returned no JSON", ProtonJson.parseLogin("").error)
    }

    @Test
    fun readsNodesAndTheirDropCount() {
        val state = ProtonJson.parseNodes(
            """
            {"ok":true,"dropped":3,"servers":[
              {"id":"11","name":"JP#2","penalty":0.2,"tier":2,"ipv6":true,"country":"jp","city":"Tokyo",
               "endpoint":"1.2.3.55","domain":"jp2.protonvpn.net","wgPublicKey":"BBBBAl==","port":51820},
              {"id":"10","name":"JP#1","penalty":0.9,"tier":2,"country":"jp","city":"Osaka",
               "endpoint":"1.2.3.4","wgPublicKey":"AAAAAl==","port":443}
            ]}
            """.trimIndent(),
        )
        assertTrue(state.ok)
        assertEquals(3, state.dropped)
        assertEquals(2, state.nodes.size)
        assertEquals("11", state.nodes[0].id)
        assertEquals("BBBBAl==", state.nodes[0].publicKey)
        assertEquals(0.2, state.nodes[0].penalty, 0.0001)
        assertTrue(state.nodes[0].supportsIPv6)
        assertFalse(state.nodes[1].supportsIPv6)
        assertEquals(443, state.nodes[1].port)
    }

    @Test
    fun dropsNodesItCannotDialEvenIfTheSidecarSentThem() {
        val state = ProtonJson.parseNodes(
            """
            {"ok":true,"dropped":0,"servers":[
              {"id":"1","name":"no key","penalty":0.1,"endpoint":"1.2.3.4","port":51820},
              {"id":"2","name":"no endpoint","penalty":0.1,"wgPublicKey":"AAAAAl==","port":51820},
              {"id":"3","name":"usable","penalty":0.1,"domain":"ok.protonvpn.net","wgPublicKey":"AAAAAl==","port":51820}
            ]}
            """.trimIndent(),
        )
        assertTrue(state.ok)
        assertEquals(listOf("3"), state.nodes.map { it.id })
    }

    @Test
    fun anEmptyListIsAFailureRatherThanAnEmptySuccess() {
        assertFalse(ProtonJson.parseNodes("""{"ok":true,"dropped":9,"servers":[]}""").ok)
        val state = ProtonJson.parseNodes("""{"ok":true,"dropped":9,"servers":[]}""")
        assertEquals(9, state.dropped)
        assertTrue(state.error.isNotEmpty())
    }

    @Test
    fun aRejectedSessionIsPassedThrough() {
        val state = ProtonJson.parseNodes(
            """{"ok":false,"error":"stored session was rejected, please log in again","dropped":0}""",
        )
        assertFalse(state.ok)
        assertTrue(state.error.contains("log in again"))
    }

    @Test
    fun hostWithPortInEndpointIsNotDoubled() {
        val state = ProtonJson.parseNodes(
            """{"ok":true,"servers":[{"id":"1","name":"n","endpoint":"1.2.3.4:51820","wgPublicKey":"AAAAAl==","port":51820}]}""",
        )
        assertEquals("1.2.3.4", state.nodes.single().endpoint)
        assertEquals(51820, state.nodes.single().port)
    }

    @Test
    fun generatesAWireGuardConfigTheImporterCanRead() {
        val node = ProtonNode(
            id = "11", name = "JP#2", penalty = 0.2, tier = 2, supportsIPv6 = true,
            country = "jp", city = "Tokyo", endpoint = "jp2.protonvpn.net",
            publicKey = "hILgVGN0U1k0d0t4cGp6WjB6cQ==", port = 51820,
        )
        val conf = ProtonJson.wireGuardConf(node, "aHdlcnRoaXNpc2FwcHJvdmF0ZWtleQ==")

        assertTrue(conf.contains("[Interface]"))
        assertTrue(conf.contains("PrivateKey = aHdlcnRoaXNpc2FwcHJvdmF0ZWtleQ=="))
        assertTrue(conf.contains("Address = 10.2.0.2/32"))
        assertTrue("Proton's in-tunnel DNS is the server address", conf.contains("DNS = 10.2.0.1"))
        assertTrue(conf.contains("PublicKey = hILgVGN0U1k0d0t4cGp6WjB6cQ=="))
        assertTrue(conf.contains("Endpoint = jp2.protonvpn.net:51820"))
        assertTrue(conf.contains("AllowedIPs = 0.0.0.0/0, ::/0"))
        assertEquals(
            "Proton's own client uses 60s keepalive",
            1,
            conf.lines().count { it == "PersistentKeepalive = 60" },
        )
    }

    @Test
    fun addressAndDnsAreOverridableForIPv6Nodes() {
        val node = ProtonNode("1", "n", 0.0, 0, true, "", "", "1.2.3.4", "AAAAAl==", 51820)
        val conf = ProtonJson.wireGuardConf(
            node,
            "key",
            "10.2.0.2/32, 2a07:b944::2:2/128",
            "10.2.0.1, 2a07:b944::2:1",
        )
        assertTrue(conf.contains("Address = 10.2.0.2/32, 2a07:b944::2:2/128"))
        assertTrue(conf.contains("DNS = 10.2.0.1, 2a07:b944::2:1"))
    }
}
