package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.fmt.ConfigBuildResult.WireGuardInstance
import io.nekohasekai.sagernet.fmt.wireguard.wireGuardServiceStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class WireGuardStatusTextTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val now = 1_800_000_000_000L
    private val owners = mapOf(11L to "chain-a", 12L to "chain-b")

    private fun peers(lastHandshake: Long) = """{"peers":[{"publicKey":"key","endpoint":"192.0.2.20:51820","lastHandshake":$lastHandshake,"rxBytes":2048,"txBytes":1024}]}"""

    private fun text(json: String?) = describeWireGuardStatus(context, json, now) { owners.getValue(it) }

    @Test
    fun everyStateReadsDifferently() {
        assertEquals("Not connected.", text(null))
        assertEquals("Not connected.", text(wireGuardServiceStatus(false, emptyList()) { "" }))
        assertEquals("This profile is not part of the running configuration.", text(wireGuardServiceStatus(true, emptyList()) { "" }))

        val single = text(wireGuardServiceStatus(true, listOf(WireGuardInstance("wg", 11L))) { peers(0) })
        assertTrue(single, single.startsWith("192.0.2.20:51820: no handshake yet, received "))
        assertFalse(single.contains("Via"))

        val recent = text(wireGuardServiceStatus(true, listOf(WireGuardInstance("wg", 11L))) { peers(now - 42_000) })
        assertTrue(recent, recent.startsWith("192.0.2.20:51820: last handshake "))
    }

    @Test
    fun profileInSeveralChainsListsEveryInstance() {
        val instances = listOf(WireGuardInstance("wg", 11L), WireGuardInstance("wg-1", 12L))
        val text = text(wireGuardServiceStatus(true, instances) { tag -> if (tag == "wg") peers(now - 5_000) else error("wireguard:unavailable: device closed") })

        val (first, second) = text.split("\n\n")
        assertTrue(first, first.startsWith("Via chain-a\n192.0.2.20:51820: last handshake "))
        assertEquals("Via chain-b\nStatus unavailable.", second)
    }
}
