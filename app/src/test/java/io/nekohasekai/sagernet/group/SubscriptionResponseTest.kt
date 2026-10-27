package io.nekohasekai.sagernet.group

import org.junit.Assert.*
import org.junit.Test

class SubscriptionResponseTest {
    @Test fun distinguishesServerPagesFromSubscriptionFormats() {
        assertEquals(SubscriptionResponseProblem.EMPTY, subscriptionResponseProblem("\uFEFF \n"))
        assertEquals(SubscriptionResponseProblem.HTML, subscriptionResponseProblem(" \n<!DOCTYPE html><html>login</html>"))
        assertEquals(SubscriptionResponseProblem.HTML, subscriptionResponseProblem("<HTML lang=\"en\">error</HTML>"))
        assertEquals(SubscriptionResponseProblem.JSON_ERROR, subscriptionResponseProblem("{\"error\":\"expired\"}"))
        for (valid in listOf("ss://fixture", "c3M6Ly9maXh0dXJl", "[Interface]\nPrivateKey=fixture", "proxies:\n - name: a", "{\"message\":\"note\",\"outbounds\":[]}")) {
            assertNull(valid, subscriptionResponseProblem(valid))
        }
    }
}
