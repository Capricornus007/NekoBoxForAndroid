package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.proto.TrafficUpdater
import org.junit.Assert.*
import org.junit.Test

class TrafficUpdaterTest {
    @Test fun screenOffPollingRetainsTotalsAcrossLongInterval() {
        assertEquals(30_000L, TrafficUpdater.pollingInterval(500, false))
        assertEquals(60_000L, TrafficUpdater.pollingInterval(60_000, false))
        assertEquals(500L, TrafficUpdater.pollingInterval(500, true))
        var now = 0L
        val item = TrafficUpdater.TrafficLooperData("proxy")
        val updater = TrafficUpdater(listOf(item), { _, _ -> 3000L }, { now })
        now = 30_000
        updater.updateAll()
        assertEquals(3000L, item.tx)
        assertEquals(100L, item.txRate)
    }
    @Test fun disabledSpeedStillPollsTotals() {
        assertEquals(1000L, TrafficUpdater.pollingInterval(0))
        assertEquals(500L, TrafficUpdater.pollingInterval(500))
        var now = 0L
        val item = TrafficUpdater.TrafficLooperData("proxy")
        val updater = TrafficUpdater(listOf(item), { _, direction -> if (direction == "uplink") 100 else 200 }, { now })
        now = 1000
        updater.updateAll()
        now = 2000
        updater.updateAll()
        assertEquals(200L, item.tx)
        assertEquals(400L, item.rx)
        assertEquals(100L, item.txRate)
    }

    @Test fun sharedTagIsQueriedOnceAndSharedProfileUsesDistinctTags() {
        var calls = 0
        var now = 0L
        val first = TrafficUpdater.TrafficLooperData("shared")
        val second = TrafficUpdater.TrafficLooperData("shared")
        val other = TrafficUpdater.TrafficLooperData("other")
        val updater = TrafficUpdater(listOf(first, second, other), { _, _ -> calls++; 15 }, { now })
        now = 1000
        updater.updateAll()
        assertEquals(4, calls)
        assertEquals(15L, second.tx)
        assertEquals(30L to 30L, TrafficUpdater.profileTotals(listOf(first, second, other)))
    }

    @Test fun zeroElapsedTimeRetainsDeltasAndSuppressesRate() {
        val item = TrafficUpdater.TrafficLooperData("proxy")
        TrafficUpdater(listOf(item), { _, _ -> 19 }, { 123L }).updateAll()
        assertEquals(19L, item.tx)
        assertEquals(0L, item.txRate)
    }
}
