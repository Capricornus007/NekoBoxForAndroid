package com.boristul.zybcvpn.bg

import com.boristul.zybcvpn.database.TrafficMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TrafficModePolicyResolverTest {

    @Test
    fun `package rule wins over uid and default`() {
        val decision = TrafficModePolicyResolver.resolveWithInputs(
            defaultMode = TrafficMode.REMOTE_VPN,
            rules = mapOf(
                "com.example.app" to TrafficMode.DPI_BYPASS,
                "com.shared.uid" to TrafficMode.REMOTE_VPN,
            ),
            packageName = "com.example.app",
            uid = 12000,
            packagesForUid = { setOf("com.shared.uid") }
        )

        assertEquals(TrafficMode.DPI_BYPASS, decision.mode)
        assertEquals(TrafficModeDecisionSource.PACKAGE_RULE, decision.source)
        assertEquals("com.example.app", decision.matchedPackageName)
    }

    @Test
    fun `uid rule applies when package name is unavailable`() {
        val decision = TrafficModePolicyResolver.resolveWithInputs(
            defaultMode = TrafficMode.REMOTE_VPN,
            rules = mapOf("com.uid.only" to TrafficMode.DPI_BYPASS),
            uid = 12001,
            packagesForUid = { setOf("com.uid.only") }
        )

        assertEquals(TrafficMode.DPI_BYPASS, decision.mode)
        assertEquals(TrafficModeDecisionSource.UID_RULE, decision.source)
        assertEquals("com.uid.only", decision.matchedPackageName)
    }

    @Test
    fun `conflicting uid rules fall back to default`() {
        val decision = TrafficModePolicyResolver.resolveWithInputs(
            defaultMode = TrafficMode.REMOTE_VPN,
            rules = mapOf(
                "com.one" to TrafficMode.REMOTE_VPN,
                "com.two" to TrafficMode.DPI_BYPASS,
            ),
            uid = 12002,
            packagesForUid = { setOf("com.one", "com.two") }
        )

        assertEquals(TrafficMode.REMOTE_VPN, decision.mode)
        assertEquals(TrafficModeDecisionSource.DEFAULT, decision.source)
    }

    @Test
    fun `default mode applies when nothing matches`() {
        val decision = TrafficModePolicyResolver.resolveWithInputs(
            defaultMode = TrafficMode.DPI_BYPASS,
            rules = emptyMap(),
            packageName = "com.unknown.app",
            uid = 12003,
            packagesForUid = { emptySet() }
        )

        assertEquals(TrafficMode.DPI_BYPASS, decision.mode)
        assertEquals(TrafficModeDecisionSource.DEFAULT, decision.source)
        assertEquals("com.unknown.app", decision.packageName)
    }
}
