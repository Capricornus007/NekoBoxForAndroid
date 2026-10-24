package io.nekohasekai.sagernet.fmt

import com.google.gson.JsonParser
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TunAccessGuardTest {
    private fun branches(rule: Rule) = ((rule as Rule_LogicalOptions).rules[1] as Rule_LogicalOptions).rules

    @Test fun generatedRulesMatchFixturesExercisedThroughNativeRouter() {
        for (bypass in listOf(false, true)) {
            val rule = perAppTunGuard(
                if (bypass) setOf(23456) else setOf(12345),
                setOf(12345, 23456, 1000, 1212345),
                20001,
                bypass,
                listOf(1000, 1051),
            )
            val name = if (bypass) "bypass" else "allow"
            val fixture = File("../libcore/testdata/tun_${name}_guard.json")
            assertEquals(JsonParser.parseString(fixture.readText()), JsonParser.parseString(gson.toJson(rule.asMap())))
        }
    }

    @Test fun vpnInsideWorkProfileOnlyAllowsItsOwnFullUids() {
        val rule = perAppTunGuard(setOf(1223456), setOf(12345, 1212345, 1223456), 1220001, true, listOf(1051))
        assertEquals(listOf(1212345, 1220001), (branches(rule)[0] as Rule_DefaultOptions).user_id)
        assertEquals(listOf(1051), (branches(rule)[1] as Rule_DefaultOptions).user_id)
    }

    @Test fun emptyBypassListStillCreatesAnExplicitAllowSet() {
        val rule = perAppTunGuard(emptySet(), setOf(12345, 1000, 1212345), 20001, true, emptyList())
        assertEquals(listOf(12345, 20001), (branches(rule).single() as Rule_DefaultOptions).user_id)
    }

    @Test fun explicitlyBypassedResolverHasNoDnsException() {
        val rule = perAppTunGuard(setOf(1051), setOf(12345), 20001, true, listOf(1000, 1051))
        assertEquals(1, branches(rule).size)
    }

    @Test fun noPermittedOwnersNeverBecomesAnUnrestrictedRule() {
        val rule = perAppTunGuard(setOf(20001), emptySet(), 20001, true, emptyList()) as Rule_DefaultOptions
        assertEquals("reject", rule.action)
        assertEquals(listOf("tun-in"), rule.inbound)
        assertNull(rule.user_id)
    }
}
