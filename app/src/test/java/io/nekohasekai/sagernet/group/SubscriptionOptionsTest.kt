package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean

class SubscriptionOptionsTest {
    @Test fun fingerprintsIgnoreLocalOverridesAndNamesButKeepKeys() {
        val first = WireGuardBean().apply { initializeDefaultValues(); name = "old"; privateKey = "fixture" }
        val second = first.clone().apply { name = "new"; customOutboundJson = "{\"test\":true}" }
        assertEquals(subscriptionFingerprint(first), subscriptionFingerprint(second))
        second.privateKey = "other"
        assertNotEquals(subscriptionFingerprint(first), subscriptionFingerprint(second))
    }
    @Test fun headersAcceptTokensAndValuesContainingColons() {
        assertEquals(mapOf("authorization" to "Bearer fixture", "example-api-key" to "a:b"),
            parseSubscriptionHeaders("Authorization: Bearer fixture\nExample-Api-Key: a:b"))
    }
    @Test fun headersRejectInjectionAmbiguityAndTransportOverrides() {
        for (text in listOf(" : x", "Bad Name: x", "Host: evil", "X-Key: a\rx", "X-Key: a\u0000", "X: a\nx: b", "Transfer-Encoding: chunked", "no separator", "A: " + "x".repeat(8192))) {
            assertThrows(IllegalArgumentException::class.java) { parseSubscriptionHeaders(text) }
        }
    }
    @Test fun namesCanSwapWithoutMovingConnectionIds() {
        val old = mapOf(1L to ("A" to "first"), 2L to ("B" to "second"))
        assertEquals(mapOf("B" to 1L, "A" to 2L), matchSubscriptionProfiles(old, mapOf("B" to "first", "A" to "second")))
    }
    @Test fun changedParametersStillUpdateByName() {
        assertEquals(mapOf("A" to 1L), matchSubscriptionProfiles(mapOf(1L to ("A" to "old")), mapOf("A" to "new")))
    }
    @Test fun duplicateConnectionsDoNotGuessRenameTargets() {
        val old = mapOf(1L to ("A" to "same"), 2L to ("B" to "same"))
        assertEquals(mapOf("A" to 1L), matchSubscriptionProfiles(old, mapOf("A" to "same", "C" to "same")))
    }
    @Test fun oneOldIdCannotBeUsedTwice() {
        val result = matchSubscriptionProfiles(mapOf(1L to ("A" to "same")), mapOf("B" to "same", "A" to "other"))
        assertEquals(mapOf("B" to 1L), result)
    }
}
