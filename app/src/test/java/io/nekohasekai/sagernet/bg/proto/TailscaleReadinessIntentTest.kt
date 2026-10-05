package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.*
import org.junit.Test

class TailscaleReadinessIntentTest {
    @Test
    fun setClearAndRollbackAreNodeAndGenerationLocal() {
        val first = TailscaleReadinessIntent()
        val next = TailscaleReadinessIntent()
        assertTrue(first.get(1, true))
        first.set(1, false)
        assertFalse(first.get(1, true))
        assertTrue(first.get(2, true))
        assertTrue(next.get(1, true))
        assertNotEquals(first.generation, next.generation)
        first.set(1, true)
        assertTrue(first.get(1, false))
        first.clear()
        assertFalse(first.get(1, false))
    }
}
