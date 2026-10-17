package io.nekohasekai.sagernet.database

import org.junit.Assert.*
import org.junit.Test

class NetworkProfilePolicyTest {
    private val wifi = NetworkProfilePolicy(transport = "wifi", profileId = 10)
    private val specific = NetworkProfilePolicy(transport = "wifi", ssid = "Home", profileId = 20)
    private val mobile = NetworkProfilePolicy(transport = "mobile", profileId = 30)
    @Test fun specificSsidPrecedesGenericRegardlessOfListOrder() {
        assertEquals(specific, matchNetworkProfile(listOf(wifi, specific, mobile), "wifi", "Home"))
        assertEquals(specific, matchNetworkProfile(listOf(specific, wifi), "wifi", "Home"))
    }
    @Test fun unknownAndDifferentSsidUseGenericWifi() {
        assertEquals(wifi, matchNetworkProfile(listOf(wifi, specific), "wifi", null))
        assertEquals(wifi, matchNetworkProfile(listOf(wifi, specific), "wifi", "home"))
        assertNull(matchNetworkProfile(listOf(specific), "wifi", null))
    }
    @Test fun noCrossTransportMatches() {
        assertEquals(mobile, matchNetworkProfile(listOf(wifi, specific, mobile), "mobile", "Home"))
        assertNull(matchNetworkProfile(listOf(wifi, specific), "ethernet", "Home"))
    }
}
