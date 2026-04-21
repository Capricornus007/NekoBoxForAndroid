package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.utils.PackageCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppTrafficRuleSerializerTest {

    @Test
    fun `serialize and deserialize round trip keeps valid rules`() {
        val raw = AppTrafficRuleSerializer.serialize(
            listOf(
                AppTrafficRule("com.example.remote", TrafficMode.REMOTE_VPN),
                AppTrafficRule("com.example.dpi", TrafficMode.DPI_BYPASS),
            )
        )

        val decoded = AppTrafficRuleSerializer.deserialize(raw)

        assertEquals(
            listOf(
                AppTrafficRule("com.example.remote", TrafficMode.REMOTE_VPN),
                AppTrafficRule("com.example.dpi", TrafficMode.DPI_BYPASS),
            ),
            decoded
        )
    }

    @Test
    fun `serializer keeps last duplicate rule and drops invalid package names`() {
        val raw = AppTrafficRuleSerializer.serialize(
            listOf(
                AppTrafficRule("bad package", TrafficMode.REMOTE_VPN),
                AppTrafficRule("com.example.app", TrafficMode.REMOTE_VPN),
                AppTrafficRule("com.example.app", TrafficMode.DPI_BYPASS),
            )
        )

        assertEquals(
            listOf(AppTrafficRule("com.example.app", TrafficMode.DPI_BYPASS)),
            AppTrafficRuleSerializer.deserialize(raw)
        )
    }

    @Test
    fun `validator reports duplicates invalid packages invalid modes and missing apps`() {
        PackageCache.packageMap = mapOf("com.present.app" to 12001)

        val report = AppTrafficRuleValidator.validateSerialized(
            """
            [
              {"packageName":"com.present.app","trafficMode":"REMOTE_VPN"},
              {"packageName":"com.present.app","trafficMode":"DPI_BYPASS"},
              {"packageName":"bad package","trafficMode":"REMOTE_VPN"},
              {"packageName":"com.missing.app","trafficMode":"DPI_BYPASS"},
              {"packageName":"com.invalid.mode","trafficMode":"BOGUS"}
            ]
            """.trimIndent()
        )

        assertEquals(setOf("com.present.app"), report.duplicatePackages)
        assertEquals(setOf("bad package"), report.invalidPackages)
        assertEquals(setOf("BOGUS"), report.invalidModes)
        assertEquals(setOf("com.missing.app"), report.missingPackages)
        assertEquals(
            listOf(
                AppTrafficRule("com.present.app", TrafficMode.DPI_BYPASS),
                AppTrafficRule("com.missing.app", TrafficMode.DPI_BYPASS),
            ),
            report.normalizedRules
        )
    }

    @Test
    fun `invalid payload returns parse error report`() {
        val report = AppTrafficRuleValidator.validateSerialized("{not-json")

        assertTrue(report.parseError != null)
        assertTrue(report.normalizedRules.isEmpty())
    }
}
