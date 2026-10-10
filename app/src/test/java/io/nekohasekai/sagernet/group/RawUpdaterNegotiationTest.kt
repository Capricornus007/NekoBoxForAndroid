package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.group.SubscriptionFormat.AUTO
import io.nekohasekai.sagernet.group.SubscriptionFormat.CLASH
import io.nekohasekai.sagernet.group.SubscriptionFormat.DEFAULT
import io.nekohasekai.sagernet.group.SubscriptionFormat.LINKS
import io.nekohasekai.sagernet.group.SubscriptionFormat.SING_BOX
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.USER_AGENT
import io.nekohasekai.sagernet.ktx.app
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RawUpdaterNegotiationTest {
    private lateinit var originalLogSink: (String) -> Unit
    private val main = "https://main.example/sub"
    private val backup = "https://backup.example/sub"
    private val one = "socks://192.0.2.1:1080#one"
    private val two = "$one\nsocks://192.0.2.2:1080#two"
    private val singBox = JSONObject().put(
        "outbounds",
        JSONArray()
            .put(JSONObject().put("type", "socks").put("tag", "one").put("server", "192.0.2.1").put("server_port", 1080))
            .put(JSONObject().put("type", "socks").put("tag", "two").put("server", "192.0.2.2").put("server_port", 1080))
            .put(JSONObject().put("type", "socks").put("tag", "three").put("server", "192.0.2.3").put("server_port", 1080)),
    ).toString()

    // Stored profiles that are all native: raw sing-box outbounds would replace them.
    private val nativeOnly: (List<io.nekohasekai.sagernet.fmt.AbstractBean>) -> Boolean = { proxies -> proxies.none { it is ConfigBean } }

    @Before
    fun setUp() {
        originalLogSink = Logs.sink
        Logs.sink = {}
    }

    @After
    fun tearDown() {
        Logs.sink = originalLogSink
    }

    private fun response(text: String, title: String = "") = RawUpdater.FetchedResponse(text) { if (it == "Profile-Title") title else "" }

    private fun agent(format: Int) = SubscriptionFormat.plan(AUTO, 0, false, "").first.single { it.format == format }.userAgent

    @Test
    fun aLoneRequestKeepsTheClientsOwnLimitsEvenWhenSlow() = runTest {
        var now = 0L
        val timeouts = mutableListOf<Long?>()
        val download = RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(DEFAULT, 0, false, ""), clock = { now }) { url, userAgent, timeoutMillis ->
            assertEquals(main, url)
            assertEquals(USER_AGENT, userAgent)
            timeouts += timeoutMillis
            // A response slower than any per-attempt cap still counts.
            now += 95_000
            response(two)
        }
        assertEquals(listOf<Long?>(null), timeouts)
        assertEquals(2, download.preview.acceptedCount)
        assertEquals(DEFAULT, download.format)
    }

    @Test
    fun firstAutoUpdateComparesFormatsAndKeepsTheEarlierOneOnTies() = runTest {
        val agents = mutableListOf<String>()
        val download = RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(AUTO, 0, false, "")) { _, userAgent, timeoutMillis ->
            agents += userAgent
            assertTrue(timeoutMillis!! in 1..20_000)
            response(if (userAgent == agent(LINKS)) one else two, userAgent)
        }
        assertEquals(listOf(agent(LINKS), agent(SING_BOX), agent(CLASH)), agents)
        assertEquals(SING_BOX, download.format)
        assertEquals(2, download.preview.acceptedCount)
    }

    @Test
    fun aRememberedFormatIsKeptWhileItWorksEvenIfAnotherReturnsMore() = runTest {
        val agents = mutableListOf<String>()
        val download = RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(AUTO, LINKS, true, "")) { _, userAgent, _ ->
            agents += userAgent
            response(if (userAgent == agent(LINKS)) one else two)
        }
        assertEquals(listOf(agent(LINKS)), agents)
        assertEquals(LINKS, download.format)
        assertEquals(1, download.preview.acceptedCount)
    }

    @Test
    fun aFailingOrEmptyRememberedFormatIsNegotiatedAgain() = runTest {
        for (failure in listOf<() -> RawUpdater.FetchedResponse>({ throw Exception("HTTP 403 Forbidden: unknown client") }, { response("<html>panel page</html>") })) {
            val agents = mutableListOf<String>()
            val download = RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(AUTO, SING_BOX, true, "")) { _, userAgent, _ ->
                agents += userAgent
                if (userAgent == agent(SING_BOX)) failure() else response(if (userAgent == agent(LINKS)) one else two)
            }
            assertEquals(listOf(agent(SING_BOX), agent(LINKS), agent(CLASH)), agents)
            assertEquals(CLASH, download.format)
        }
    }

    @Test
    fun aProviderChangingShapeForTheSameUserAgentIsNegotiatedAway() = runTest {
        // The remembered links request now returns sing-box outbounds; Clash still returns nodes.
        val download = RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(AUTO, LINKS, true, ""), nativeOnly) { _, userAgent, _ ->
            response(if (userAgent == agent(CLASH)) one else singBox)
        }
        assertEquals(CLASH, download.format)
        assertTrue(download.compatible)
        assertEquals(1, download.preview.acceptedCount)

        // When every format changed, the most complete response goes on to ask the user.
        val changed = RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(AUTO, LINKS, true, ""), nativeOnly) { _, _, _ -> response(singBox) }
        assertFalse(changed.compatible)
        assertEquals(LINKS, changed.format)
        assertEquals(3, changed.preview.acceptedCount)
    }

    @Test
    fun unreachableMainLinkMovesToApprovedBackupsWithinOneBudget() = runTest {
        val requests = mutableListOf<Pair<String, String>>()
        val download = RawUpdater.negotiate(listOf(main, backup), SubscriptionFormat.plan(AUTO, 0, false, "")) { url, userAgent, timeoutMillis ->
            requests += url to userAgent
            assertTrue(timeoutMillis!! in 1..20_000)
            if (url == main) throw Exception("dial tcp: connect: connection refused") else response(one)
        }
        // A server that cannot be reached would fail the main link's other formats the same way.
        assertEquals(listOf(main to agent(LINKS), backup to agent(LINKS), backup to agent(SING_BOX), backup to agent(CLASH)), requests)
        assertEquals(backup, download.url)
        assertEquals(LINKS, download.format)
    }

    @Test
    fun aRememberedFormatThatTimesOutStillLetsSmallerFormatsTry() = runTest {
        var now = 0L
        val agents = mutableListOf<String>()
        // The Clash request runs out of time on a large configuration; share links answer.
        val download = RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(AUTO, CLASH, true, ""), clock = { now }) { _, userAgent, timeoutMillis ->
            agents += userAgent
            if (userAgent == agent(CLASH)) {
                now += timeoutMillis!!
                throw Exception("read: connection reset")
            }
            response(one)
        }
        // The fallback formats are compared as on the first update; a tie keeps the earlier one.
        assertEquals(listOf(agent(CLASH), agent(LINKS), agent(SING_BOX)), agents)
        assertEquals(LINKS, download.format)

        // A timeout the HTTP client reports before the attempt's own limit counts too.
        agents.clear()
        RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(AUTO, CLASH, true, ""), clock = { now }) { _, userAgent, _ ->
            agents += userAgent
            if (userAgent == agent(CLASH)) throw Exception("context deadline exceeded (Client.Timeout exceeded while awaiting headers)")
            response(one)
        }
        assertEquals(listOf(agent(CLASH), agent(LINKS), agent(SING_BOX)), agents)

        // All of it stays within one budget, and the backup keeps its share.
        now = 0L
        val requests = mutableListOf<Pair<String, Long>>()
        val viaBackup = RawUpdater.negotiate(listOf(main, backup), SubscriptionFormat.plan(AUTO, CLASH, true, ""), clock = { now }) { url, _, timeoutMillis ->
            requests += url to timeoutMillis!!
            if (url == main) {
                now += timeoutMillis
                throw Exception("i/o timeout")
            }
            response(one)
        }
        assertEquals(listOf(main to 70_000L, backup to 20_000L), requests)
        assertEquals(backup, viaBackup.url)
    }

    @Test
    fun aRememberedRequestSlowerThanTwentySecondsStillAnswers() = runTest {
        var now = 0L
        val timeouts = mutableListOf<Long>()
        val download = RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(AUTO, CLASH, true, ""), clock = { now }) { _, _, timeoutMillis ->
            timeouts += timeoutMillis!!
            now += 30_000
            response(one)
        }
        // The fallback formats keep one 20 s attempt of the 90 s.
        assertEquals(listOf(70_000L), timeouts)
        assertEquals(CLASH, download.format)
    }

    @Test
    fun blockingTimeoutsLeaveEveryApprovedBackupAnAttemptWithinTheBudget() = runTest {
        val backups = listOf(backup, "https://second.example/sub")
        var now = 0L
        val requests = mutableListOf<Pair<String, Long>>()
        // Every request blocks until its time runs out, except the last backup's.
        val download = RawUpdater.negotiate(listOf(main) + backups, SubscriptionFormat.plan(AUTO, CLASH, true, ""), clock = { now }) { url, userAgent, timeoutMillis ->
            requests += url to timeoutMillis!!
            now += timeoutMillis
            if (url == backups.last() && userAgent == agent(CLASH)) response(one) else throw Exception("context deadline exceeded")
        }
        assertEquals(listOf(main to 50_000L, backups[0] to 20_000L, backups[1] to 20_000L), requests)
        assertEquals(backups.last(), download.url)

        // When nothing answers, every link got its attempt and the total stayed within 90 s.
        now = 0L
        requests.clear()
        val failure = runCatching {
            RawUpdater.negotiate(listOf(main) + backups, SubscriptionFormat.plan(AUTO, CLASH, true, ""), clock = { now }) { url, _, timeoutMillis ->
                requests += url to timeoutMillis!!
                now += timeoutMillis
                throw Exception("context deadline exceeded")
            }
        }.exceptionOrNull()
        assertEquals("context deadline exceeded", failure?.message)
        assertEquals(listOf(main, backups[0], backups[1]), requests.map { it.first })
        assertTrue(requests.sumOf { it.second } <= 90_000L)
    }

    @Test
    fun aFetchThatReturnsAfterCancellationEndsNegotiation() = runTest {
        var attempts = 0
        val job = launch {
            RawUpdater.negotiate(listOf(main, backup), SubscriptionFormat.plan(AUTO, CLASH, true, "")) { _, _, _ ->
                attempts++
                // A blocking request returns normally even though its update was cancelled meanwhile.
                currentCoroutineContext().cancel()
                response(one)
            }
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
    }

    @Test
    fun cancellationStopsNegotiationAtOnce() = runTest {
        var attempts = 0
        val failure = runCatching {
            RawUpdater.negotiate(listOf(main, backup), SubscriptionFormat.plan(AUTO, CLASH, true, "")) { _, _, _ ->
                attempts++
                throw kotlinx.coroutines.CancellationException("stopped")
            }
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(1, attempts)
    }

    @Test
    fun failuresKeepTheFirstErrorAndStopWhenTheMonotonicBudgetRunsOut() = runTest {
        var now = 1_000_000L
        var attempts = 0
        val failure = runCatching {
            RawUpdater.negotiate(listOf(main, backup), SubscriptionFormat.plan(AUTO, 0, false, ""), budgetMillis = 50_000, clock = { now }) { url, _, timeoutMillis ->
                attempts++
                now += timeoutMillis!!
                throw Exception(if (url == main) "HTTP 502 Bad Gateway" else "HTTP 404 Not Found")
            }
        }.exceptionOrNull()
        assertEquals("HTTP 502 Bad Gateway", failure?.message)
        // 20 s and 13 s on the main link leave the backup its share of the 50 s.
        assertEquals(3, attempts)

        val empty = runCatching {
            RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(DEFAULT, 0, false, "")) { _, _, timeoutMillis ->
                assertNull(timeoutMillis)
                response("# nothing here\nhttps://provider.example/renew")
            }
        }.exceptionOrNull()
        assertEquals(app.getString(R.string.subscription_no_compatible_format), empty?.message)
    }

    @Test
    fun outboundsTheCoreCannotRunOrThatStartProgramsDoNotWinAuto() = runTest {
        fun outbound(type: String, tag: String) = JSONObject().put("type", type).put("tag", tag).put("server", "192.0.2.9").put("server_port", 443)
        val unusable = JSONObject().put(
            "outbounds",
            JSONArray().put(outbound("socks", "one")).put(outbound("xhttp", "two")).put(outbound("naive", "three")).put(outbound("tor", "four")),
        ).toString()
        val download = RawUpdater.negotiate(listOf(main), SubscriptionFormat.plan(AUTO, 0, false, "")) { _, userAgent, _ ->
            response(if (userAgent == agent(LINKS)) one else unusable, userAgent)
        }
        assertEquals(LINKS, download.format)
        assertEquals(1, download.preview.acceptedCount)
    }
}
