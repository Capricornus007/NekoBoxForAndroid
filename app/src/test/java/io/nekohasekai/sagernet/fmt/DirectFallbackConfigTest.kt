package io.nekohasekai.sagernet.fmt

import org.junit.Assert.*
import org.junit.Test

class DirectFallbackConfigTest {
    @Test fun directIsFirstAndProxyIsNotRankedByLatency() {
        val config = directFallbackConfig("waterfall", "https://www.kernel.org", 30)
        assertEquals(listOf("direct", "waterfall"), config["outbounds"])
        assertEquals("direct-fallback", config["strategy"])
        assertEquals("3s", config["timeout"])
        assertEquals(true, config["wait_for_initial"])
        assertEquals(true, config["interrupt_exist_connections"])
    }

    @Test fun rejectsInvalidProbesAndIntervals() {
        for (url in listOf("", "ftp://example.org", "https://", "https://user:pass@example.org", "https://example.org/#fragment", "https://example.org:0")) {
            assertThrows(IllegalArgumentException::class.java) { directFallbackConfig("proxy", url, 30) }
        }
        for (interval in listOf(0, 4, 3601)) {
            assertThrows(IllegalArgumentException::class.java) { directFallbackConfig("proxy", "https://example.org", interval) }
        }
        assertThrows(IllegalArgumentException::class.java) { directFallbackConfig(TAG_DIRECT_FALLBACK, "https://example.org", 30) }
    }
}
