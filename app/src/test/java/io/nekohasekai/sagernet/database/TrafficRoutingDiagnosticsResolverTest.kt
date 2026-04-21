package io.nekohasekai.sagernet.database

import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficRoutingDiagnosticsResolverTest {

    @Test
    fun `rules are effective when proxy apps is disabled`() {
        val diagnostics = TrafficRoutingDiagnosticsResolver.snapshot(
            defaultMode = TrafficMode.REMOTE_VPN,
            proxyApps = false,
            bypass = true,
            individual = "com.blocked.one",
            ruleReport = AppTrafficRuleValidationReport(
                normalizedRules = listOf(
                    AppTrafficRule("com.blocked.one", TrafficMode.DPI_BYPASS),
                    AppTrafficRule("com.allowed.two", TrafficMode.REMOTE_VPN),
                )
            )
        )

        assertEquals(emptySet<String>(), diagnostics.ineffectivePackages)
        assertEquals(2, diagnostics.ruleCount)
    }

    @Test
    fun `bypass mode makes selected packages ineffective`() {
        val diagnostics = TrafficRoutingDiagnosticsResolver.snapshot(
            defaultMode = TrafficMode.REMOTE_VPN,
            proxyApps = true,
            bypass = true,
            individual = "com.excluded.one\ncom.excluded.two",
            ruleReport = AppTrafficRuleValidationReport(
                normalizedRules = listOf(
                    AppTrafficRule("com.excluded.one", TrafficMode.DPI_BYPASS),
                    AppTrafficRule("com.kept.three", TrafficMode.REMOTE_VPN),
                )
            )
        )

        assertEquals(setOf("com.excluded.one"), diagnostics.ineffectivePackages)
    }

    @Test
    fun `allow-only mode makes unselected packages ineffective`() {
        val diagnostics = TrafficRoutingDiagnosticsResolver.snapshot(
            defaultMode = TrafficMode.DPI_BYPASS,
            proxyApps = true,
            bypass = false,
            individual = "com.allowed.one",
            ruleReport = AppTrafficRuleValidationReport(
                normalizedRules = listOf(
                    AppTrafficRule("com.allowed.one", TrafficMode.DPI_BYPASS),
                    AppTrafficRule("com.not.allowed.two", TrafficMode.REMOTE_VPN),
                )
            )
        )

        assertEquals(setOf("com.not.allowed.two"), diagnostics.ineffectivePackages)
        assertEquals(TrafficMode.DPI_BYPASS, diagnostics.defaultMode)
    }
}
