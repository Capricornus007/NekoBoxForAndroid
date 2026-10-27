package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.bg.proto.TrafficUpdater
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Test

class TrafficUpdaterTest {
    @Test fun countersRetainTotalsAcrossLongInterval() {
        var now = 0L
        val item = TrafficUpdater.TrafficLooperData("proxy")
        val updater = TrafficUpdater(listOf(item), { _, _ -> 3000L }, { now })
        now = 30_000
        updater.updateAll()
        assertEquals(3000L, item.tx)
        assertEquals(100L, item.txRate)
    }

    @Test fun lockedScreenWaitsForUnlockWithoutReadingCounters(): Unit = runBlocking {
        var restricted = true
        val changes = Channel<Unit>(Channel.CONFLATED)
        var reads = 0
        val item = TrafficUpdater.TrafficLooperData("proxy")
        val updater = TrafficUpdater(listOf(item), { _, _ ->
            reads++
            3000L
        })
        val update = async(start = CoroutineStart.UNDISPATCHED) {
            val resumed = TrafficUpdater.awaitUpdate(1, { restricted }, changes)
            assertTrue(resumed)
            updater.updateAll(calculateRates = !resumed)
        }
        // A queued screen-off/Doze event must not cause a counter read.
        changes.send(Unit)
        yield()
        assertFalse(update.isCompleted)
        assertEquals(0, reads)
        restricted = false
        changes.send(Unit)
        withTimeout(1000) { update.await() }
        assertEquals(2, reads)
        assertEquals(3000L, item.tx)
        assertEquals(3000L, item.rx)
        assertEquals(0L, item.txRate)
        assertEquals(0L, item.rxRate)
        changes.close()
    }

    @Test fun unlockedScreenStillUpdatesOnTimer(): Unit = runBlocking {
        val changes = Channel<Unit>(Channel.CONFLATED)
        assertFalse(withTimeout(1000) { TrafficUpdater.awaitUpdate(1, { false }, changes) })
        changes.close()
    }

    @Test fun stoppingWhileLockedCancelsWaitingImmediately(): Unit = runBlocking {
        val changes = Channel<Unit>(Channel.CONFLATED)
        val update = launch(start = CoroutineStart.UNDISPATCHED) {
            TrafficUpdater.awaitUpdate(1, { true }, changes)
            fail("A locked screen must not request an update")
        }
        withTimeout(1000) { update.cancelAndJoin() }
        assertTrue(update.isCancelled)
        changes.close()
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
        val updater = TrafficUpdater(listOf(first, second, other), { _, _ ->
            calls++
            15
        }, { now })
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
