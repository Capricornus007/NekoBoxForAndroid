package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.NetworkAutomation.Action
import io.nekohasekai.sagernet.bg.NetworkAutomation.Kind
import io.nekohasekai.sagernet.bg.NetworkAutomation.Rule
import io.nekohasekai.sagernet.bg.NetworkAutomation.Snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NetworkAutomationTest {

    private val home = Rule(Kind.SSID, "Home", Action.DISCONNECT)
    private val anyWifi = Rule(Kind.WIFI, action = Action.CONNECT, profileId = 7L)
    private val mobile = Rule(Kind.MOBILE, action = Action.CONNECT)
    private val rules = listOf(mobile, anyWifi, home)

    @Test
    fun mostSpecificRuleWins() {
        assertEquals(home, NetworkAutomation.match(rules, Snapshot(Kind.WIFI, "Home")))
        assertEquals(anyWifi, NetworkAutomation.match(rules, Snapshot(Kind.WIFI, "Cafe")))
        assertEquals(anyWifi, NetworkAutomation.match(rules, Snapshot(Kind.WIFI, null)))
        assertEquals(mobile, NetworkAutomation.match(rules, Snapshot(Kind.MOBILE, null)))
        assertNull(NetworkAutomation.match(listOf(home), Snapshot(Kind.WIFI, null)))
        assertNull(NetworkAutomation.match(listOf(home, anyWifi), Snapshot(Kind.MOBILE, null)))
    }

    @Test
    fun rulesRoundTripThroughJson() {
        for (rule in rules) {
            assertEquals(rule, Rule.fromJson(rule.toJson()))
        }
    }
}
