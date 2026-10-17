package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import org.junit.Assert.*
import org.junit.Test

class SubscriptionDiffTest {
    private fun bean() = WireGuardBean().apply { initializeDefaultValues() }
    @Test fun secretsAreRepresentedOnlyByCategory() {
        val old = bean()
        val updated = old.clone().apply {
            privateKey = "test-private-value"
            dnsServers = "192.0.2.53"
            allowedIPs = "192.0.2.0/24"
        }
        val changes = subscriptionChanges(old, updated)
        assertEquals(setOf("credentials", "dns", "allowed_ips"), changes)
        assertFalse(changes.joinToString().contains("test-private-value"))
    }
    @Test fun preservedLocalOverridesDoNotCreatePreviewChanges() {
        val old = bean().apply { customConfigJson = "local"; customOutboundJson = "override"; useConfigRoutes = true }
        val incoming = bean()
        assertTrue(subscriptionChanges(old, incoming).isEmpty())
        incoming.serverAddress = "192.0.2.1"
        assertEquals(setOf("address"), subscriptionChanges(old, incoming))
    }
}
