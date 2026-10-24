package io.nekohasekai.sagernet.database

import org.junit.Assert.*
import org.junit.Test

class RouteOverlapTest {
    private fun rule(id: Long, domain: String, outbound: Long = 0) = RuleEntity(
        id = id,
        enabled = true,
        domains = domain,
        outbound = outbound,
    )

    @Test fun identifiesDuplicatesAndFirstShadowingRule() {
        val broad = rule(1, "example.com", -1)
        val narrow = rule(2, "full:api.example.com")
        val duplicate = rule(3, "domain:EXAMPLE.com", -1)
        val found = RouteOverlap.find(listOf(broad, narrow, duplicate))
        assertEquals(listOf(2L, 3L), found.map { it.later.id })
        assertFalse(found[0].duplicate)
        assertTrue(found[1].duplicate)
        assertTrue(found.all { it.earlier.id == 1L })
    }

    @Test fun respectsAllConditionsAndAlternativeDomains() {
        val broad = rule(1, "example.com").copy(packages = setOf("one"), network = "tcp")
        assertTrue(RouteOverlap.find(listOf(broad, rule(2, "full:api.example.com"))).isEmpty())
        assertEquals(
            1,
            RouteOverlap.find(
                listOf(
                    broad,
                    rule(2, "full:api.example.com").copy(packages = setOf("one"), network = "tcp", port = "443"),
                ),
            ).size,
        )
        assertTrue(RouteOverlap.find(listOf(rule(1, "example.com"), rule(2, "example.com,other.org"))).isEmpty())
        assertTrue(RouteOverlap.find(listOf(rule(1, "example.com"), rule(2, "notexample.com"))).isEmpty())
    }

    @Test fun avoidsGuessingCustomRulesOrMixedAddressSemantics() {
        val broad = rule(1, "example.com")
        for (later in listOf(
            rule(2, "api.example.com").copy(config = "{}"),
            rule(2, "api.example.com").copy(ip = "192.0.2.0/24"),
            rule(2, "api.example.com").copy(ruleset = "https://example.org/list.srs"),
            rule(2, "regexp:.*example.com"),
            rule(2, "keyword:example"),
            rule(2, "api.example.com").copy(enabled = false),
        )) {
            assertTrue(RouteOverlap.find(listOf(broad, later)).isEmpty())
        }
        assertTrue(RouteOverlap.find(listOf(rule(1, "full:example.com"), rule(2, "api.example.com"))).isEmpty())
        assertTrue(RouteOverlap.find(listOf(rule(1, ""), rule(2, "example.com"))).isEmpty())
    }
}
