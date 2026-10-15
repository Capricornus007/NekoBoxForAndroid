package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class DiagnosticFilterTest {
    @Test fun joinsOnlyFullCorrelationIds() {
        val events = listOf(
            DiagnosticEvent("42/100", "10010 org.example", "example.org", "fallback"),
            DiagnosticEvent("42/100", "", "", "node-a"),
            DiagnosticEvent("42/200", "10011 other", "example.org", "node-a"),
            DiagnosticEvent("", "", "example.org", "dns"),
        )
        assertEquals(setOf(0, 1), diagnosticMatches(events, "ORG.EXAMPLE", "example", "node-a"))
        assertEquals(emptySet<Int>(), diagnosticMatches(events, "10010", "", "node-b"))
    }
    @Test fun emptyIdsNeverJoin() {
        val events = listOf(DiagnosticEvent("", "app", "", ""), DiagnosticEvent("", "", "domain", "node"))
        assertTrue(diagnosticMatches(events, "app", "domain", "node").isEmpty())
        assertEquals(setOf(0, 1), diagnosticMatches(events, "", "", ""))
    }
}
