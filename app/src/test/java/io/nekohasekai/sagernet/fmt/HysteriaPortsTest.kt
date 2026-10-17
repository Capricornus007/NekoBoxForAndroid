package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.getFirstPort
import io.nekohasekai.sagernet.fmt.hysteria.hopPortsToSingboxList
import org.junit.Assert.assertEquals
import org.junit.Test

class HysteriaPortsTest {
    @Test fun preservesIndividualPortsAndRangesAcrossLines() {
        assertEquals(listOf("443:443", "8443:8443", "9000:9010", "9443:9450"),
            hopPortsToSingboxList("443,8443\n9000-9010\r\n9443:9450"))
        assertEquals(9000, getFirstPort("9000-9010,443"))
        assertEquals(8443, getFirstPort("8443\n443"))
    }
}
