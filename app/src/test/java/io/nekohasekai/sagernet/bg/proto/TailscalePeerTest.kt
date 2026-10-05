package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class TailscalePeerTest {
    @Test
    fun duplicateAndRenamedHostLabelsDoNotChooseTheExitIdentifier() {
        val first = TailscalePeer("duplicate", "first.tail.example", listOf("100.64.0.1"), true, true)
        val second = first.copy(dnsName = "second.tail.example", ips = listOf("100.64.0.2"))
        val renamed = first.copy(name = "old-hostname", dnsName = "renamed.tail.example")
        assertEquals("first.tail.example", first.exitNodeAddress())
        assertEquals("second.tail.example", second.exitNodeAddress())
        assertEquals("renamed.tail.example", renamed.exitNodeAddress())
    }

    @Test
    fun blankDnsNameUsesAnActualIpAndNeverFallsBackToDisplayName() {
        val peer = TailscalePeer("display-only", " ", listOf("not-an-address", "100.64.0.3"), true, true)
        assertEquals("100.64.0.3", peer.exitNodeAddress())
        assertEquals("fd7a:115c:a1e0::1", peer.copy(ips = listOf("fd7a:115c:a1e0::1")).exitNodeAddress())
        assertNotNull(runCatching { peer.copy(ips = emptyList()).exitNodeAddress() }.exceptionOrNull())
        assertNotNull(runCatching { peer.copy(ips = listOf("display-only")).exitNodeAddress() }.exceptionOrNull())
    }
}
