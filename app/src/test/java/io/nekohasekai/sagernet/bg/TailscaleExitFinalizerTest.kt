package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TailscaleExitFinalizerTest {
    private class Change(
        private val events: MutableList<String>,
        private val failRollback: Boolean = false,
        private val failCommit: Boolean = false,
    ) : TailscaleExitChange {
        override fun savedValue() = "100.64.0.2"
        override fun commit() {
            events += "commit"
            if (failCommit) error("closed")
        }
        override fun rollback() {
            events += "rollback-exact"
            if (failRollback) error("closed")
        }
    }

    @Test
    fun publishesSuccessOnlyAfterDurableSaveAndCommit() = runTest {
        val events = mutableListOf<String>()
        val result = finalizeTailscaleExit(
            "old",
            {
                events += "begin"
                Change(events)
            },
            { events += "save:$it" },
            { events += "intent:$it" },
            { events += "restore-intent" },
        )
        assertEquals(listOf("begin", "intent:true", "save:100.64.0.2", "commit"), events)
        assertEquals("applied-and-saved", result.outcome)
    }

    @Test
    fun dbFailureRollsBackExactNativeStateAndReadiness() = runTest {
        val events = mutableListOf<String>()
        val result =
            finalizeTailscaleExit("old", { Change(events) }, { error("disk") }, {}, { events += "restore-intent" })
        assertEquals(listOf("rollback-exact", "restore-intent"), events)
        assertEquals("failed-rolled-back", result.outcome)
        assertEquals("old", result.savedExit)
    }

    @Test
    fun conflictDoesNotUndoAnotherSavedChoice() = runTest {
        val events = mutableListOf<String>()
        val database = "newer-choice"
        val result = finalizeTailscaleExit("old", { Change(events) }, { error("tailscale:conflict") }, {}, {})
        assertEquals("conflict", result.outcome)
        assertEquals(listOf("rollback-exact"), events)
        assertEquals("newer-choice", database)
    }

    @Test
    fun failedRollbackOrCommitReportsDivergence() = runTest {
        val events = mutableListOf<String>()
        assertEquals(
            "diverged",
            finalizeTailscaleExit("old", {
                Change(events, failRollback = true)
            }, { error("disk") }, {}, {}).outcome,
        )
        events.clear()
        val result = finalizeTailscaleExit("old", { Change(events, failCommit = true) }, {}, {}, {})
        assertEquals("diverged", result.outcome)
        assertEquals("100.64.0.2", result.savedExit)
        assertEquals(listOf("commit"), events)
    }

    @Test
    fun rejectionDoesNotWriteDatabaseOrReadiness() = runTest {
        val result =
            finalizeTailscaleExit(
                "old",
                { error("peer removed") },
                { fail("save") },
                { fail("intent") },
                { fail("rollback") },
            )
        assertEquals("failed-unchanged", result.outcome)
    }

    @Test
    fun beginDivergencePublishesDivergedWithoutSavingOrClaimingRollback() = runTest {
        val completed = mutableListOf<TailscaleExitResult>()
        val result = finalizeTailscaleExit(
            "old",
            {
                error("tailscale:diverged: exit preferences diverged after restoration failed")
            },
            { fail("save") },
            { fail("intent") },
            { fail("rollback without handle") },
            completed = { completed += it },
        )
        assertEquals("diverged", result.outcome)
        assertEquals("tailscale:apply-diverged", result.errorCode)
        assertEquals("old", result.savedExit)
        assertEquals(listOf(result), completed)
    }

    @Test
    fun divergenceTextWithoutExactPrefixRemainsOrdinaryBeginFailure() = runTest {
        val result = finalizeTailscaleExit("old", {
            error("wrapped tailscale:diverged: other error")
        }, { fail("save") }, { fail("intent") }, { fail("rollback") })
        assertEquals("failed-unchanged", result.outcome)
        assertEquals("tailscale:apply-failed", result.errorCode)
        assertEquals("old", result.savedExit)
    }

    @Test
    fun ownerCancellationAfterBeginStillSavesBeforeStopCanClose() = runTest {
        val events = mutableListOf<String>()
        lateinit var owner: Job
        owner = launch(start = CoroutineStart.LAZY) {
            finalizeTailscaleExit("old", {
                events += "begin"
                owner.cancel()
                Change(events)
            }, { events += "save" }, {}, {})
        }
        owner.start()
        owner.join()
        events += "close-box"
        assertEquals(listOf("begin", "save", "commit", "close-box"), events)
    }

    @Test
    fun ownerCancellationDuringDbFailureStillRollsBackBeforeClose() = runTest {
        val events = mutableListOf<String>()
        lateinit var owner: Job
        owner = launch(start = CoroutineStart.LAZY) {
            finalizeTailscaleExit("old", { Change(events) }, {
                owner.cancel()
                error("disk")
            }, {}, { events += "restore-intent" })
        }
        owner.start()
        owner.join()
        events += "close-box"
        assertEquals(listOf("rollback-exact", "restore-intent", "close-box"), events)
    }

    @Test
    fun clearingExitUpdatesReadinessBeforeSavingEmptySelector() = runTest {
        val events = mutableListOf<String>()
        val result = finalizeTailscaleExit("old", {
            object : TailscaleExitChange {
                override fun savedValue() = ""
                override fun commit() {
                    events += "commit"
                }
                override fun rollback() {
                    fail("rollback")
                }
            }
        }, { events += "save:$it" }, { events += "intent:$it" }, {})
        assertEquals(listOf("intent:false", "save:", "commit"), events)
        assertEquals("", result.savedExit)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun completionRecordsActualOutcomeBeforeExpiredDeadlineDiscardsReturn() = runTest {
        val events = mutableListOf<String>()
        val outcomes = mutableListOf<TailscaleExitResult>()
        runCatching {
            finalizeTailscaleExit("old", { Change(events) }, {
                events += "save"
                testScheduler.advanceTimeBy(30_001)
            }, {}, {}, completed = { outcomes += it })
        }
        assertEquals(listOf("save", "commit"), events)
        assertEquals(listOf("applied-and-saved"), outcomes.map { it.outcome })
        assertEquals("100.64.0.2", outcomes.single().savedExit)
    }

    @Test
    fun cancelledQueuedMutationNeverApplies() = runTest {
        var applied = false
        val owner = launch(start = CoroutineStart.LAZY) {
            ensureActive()
            finalizeTailscaleExit("old", {
                applied = true
                Change(mutableListOf())
            }, {}, {}, {})
        }
        owner.cancelAndJoin()
        assertFalse(applied)
    }
}
