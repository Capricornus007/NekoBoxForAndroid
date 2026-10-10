package io.nekohasekai.sagernet.bg

import android.net.NetworkCapabilities
import io.nekohasekai.sagernet.bg.BaseService.State
import io.nekohasekai.sagernet.bg.NetworkAutomation.Action
import io.nekohasekai.sagernet.bg.NetworkAutomation.Command
import io.nekohasekai.sagernet.bg.NetworkAutomation.Kind
import io.nekohasekai.sagernet.bg.NetworkAutomation.Rule
import io.nekohasekai.sagernet.bg.NetworkAutomation.Snapshot
import io.nekohasekai.sagernet.bg.NetworkAutomation.Tracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NetworkAutomationTest {

    private val home = Rule(Kind.SSID, "Home", Action.DISCONNECT)
    private val anyWifi = Rule(Kind.WIFI, action = Action.CONNECT, profileId = 7L)
    private val mobile = Rule(Kind.MOBILE, action = Action.CONNECT)
    private val wired = Rule(Kind.ETHERNET, action = Action.DISCONNECT)
    private val rules = listOf(mobile, anyWifi, home, wired)

    private val atHome = Snapshot(Kind.WIFI, "Home")
    private val hiddenWifi = Snapshot(Kind.WIFI, null)
    private val cellular = Snapshot(Kind.MOBILE, null)

    @Test
    fun mostSpecificRuleWins() {
        assertEquals(home, NetworkAutomation.match(rules, atHome))
        assertEquals(anyWifi, NetworkAutomation.match(rules, Snapshot(Kind.WIFI, "Cafe")))
        assertEquals(anyWifi, NetworkAutomation.match(rules, hiddenWifi))
        assertEquals(mobile, NetworkAutomation.match(rules, cellular))
        assertEquals(wired, NetworkAutomation.match(rules, Snapshot(Kind.ETHERNET, null)))
        assertNull(NetworkAutomation.match(listOf(home), hiddenWifi))
        assertNull(NetworkAutomation.match(listOf(home, anyWifi), cellular))
        // Ethernet and mobile rules never stand in for each other or for Wi-Fi.
        assertNull(NetworkAutomation.match(listOf(mobile, anyWifi), Snapshot(Kind.ETHERNET, null)))
        assertNull(NetworkAutomation.match(listOf(wired), cellular))
    }

    @Test
    fun ethernetWinsOverOtherTransports() {
        assertEquals(Kind.ETHERNET, kindOf(NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_WIFI))
        assertEquals(Kind.ETHERNET, kindOf(NetworkCapabilities.TRANSPORT_CELLULAR, NetworkCapabilities.TRANSPORT_ETHERNET))
        assertEquals(Kind.WIFI, kindOf(NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_CELLULAR))
        assertEquals(Kind.MOBILE, kindOf(NetworkCapabilities.TRANSPORT_CELLULAR))
        assertNull(kindOf(NetworkCapabilities.TRANSPORT_BLUETOOTH))
        assertNull(kindOf())
    }

    private fun kindOf(vararg transports: Int): Kind? {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        transports.forEach { shadowOf(capabilities).addTransportType(it) }
        return NetworkAutomation.kindOf(capabilities)
    }

    @Test
    fun rulesRoundTripThroughJson() {
        for (rule in rules) {
            assertEquals(rule, Rule.fromJson(rule.toJson()))
        }
    }

    @Test
    fun storedRulesLoadPastEntriesThisVersionCannotRead() {
        val raw = """[{"kind":"WIFI","action":"CONNECT"},{"kind":"SATELLITE","action":"CONNECT"},""" +
            """{"kind":"MOBILE","ssid":"","action":"DISCONNECT","profileId":3},"text"]"""
        assertEquals(
            listOf(Rule(Kind.WIFI, action = Action.CONNECT), Rule(Kind.MOBILE, action = Action.DISCONNECT, profileId = 3)),
            NetworkAutomation.parseRules(raw),
        )
        assertEquals(emptyList<Rule>(), NetworkAutomation.parseRules(null))
        assertEquals(emptyList<Rule>(), NetworkAutomation.parseRules("{"))
    }

    @Test
    fun commandsFollowTheServiceState() {
        val cafe = Snapshot(Kind.WIFI, "Cafe")
        assertEquals(Command.Start(7), decide(cafe, anyWifi, running = false))
        assertEquals(Command.Switch(7), decide(cafe, anyWifi, running = true, runningProfile = 3))
        assertNull(decide(cafe, anyWifi, running = true, runningProfile = 7))
        // A rule for the selected profile never switches a running service.
        assertNull(decide(cellular, mobile, running = true, runningProfile = 3))
        assertEquals(Command.Stop, decide(atHome, home, running = true))
        assertNull(decide(atHome, home, running = false))
    }

    @Test
    fun alwaysOnVpnIsNeverStopped() {
        assertNull(decide(atHome, home, running = true, alwaysOn = true))
        assertNull(decide(Snapshot(Kind.ETHERNET, null), wired, running = true, alwaysOn = true))
    }

    // Before, a hidden name fell back to the any-Wi-Fi rule for every action. That rule may now only
    // start a stopped service, and not even that when a name rule disconnects: the network may be Home.
    @Test
    fun hiddenWifiNameNeverStopsOrSwitchesAndMayLeaveTheVpnOff() {
        assertNull(decide(hiddenWifi, anyWifi, running = true, runningProfile = 3))
        assertEquals(Command.StayOff, decide(hiddenWifi, anyWifi, running = false))

        val cafe = Rule(Kind.SSID, "Cafe", Action.CONNECT, profileId = 9)
        assertEquals(Command.Start(7), decide(hiddenWifi, anyWifi, running = false, rules = listOf(anyWifi, cafe)))

        val anyWifiOff = Rule(Kind.WIFI, action = Action.DISCONNECT)
        assertNull(decide(hiddenWifi, anyWifiOff, running = true, rules = listOf(anyWifiOff, cafe)))

        // Without name rules the any-Wi-Fi rule is exact, so it acts as before.
        assertEquals(Command.Switch(7), decide(hiddenWifi, anyWifi, running = true, runningProfile = 3, rules = listOf(anyWifi)))
        assertEquals(Command.Stop, decide(hiddenWifi, anyWifiOff, running = true, rules = listOf(anyWifiOff)))
    }

    private fun decide(
        snapshot: Snapshot,
        rule: Rule,
        running: Boolean,
        runningProfile: Long = 0,
        alwaysOn: Boolean = false,
        rules: List<Rule> = this.rules,
    ) = NetworkAutomation.decide(rules, snapshot, rule, running, runningProfile, alwaysOn)

    @Test
    fun watcherRunsOnlyWhileAConnectRuleCanAct() {
        assertTrue(NetworkAutomation.monitorWanted(enabled = true, paused = false, rules = rules))
        assertFalse(NetworkAutomation.monitorWanted(enabled = false, paused = false, rules = rules))
        // A user stop pauses connect rules, so there is nothing to watch for.
        assertFalse(NetworkAutomation.monitorWanted(enabled = true, paused = true, rules = rules))
        assertFalse(NetworkAutomation.monitorWanted(enabled = true, paused = false, rules = listOf(home, wired)))
        assertFalse(NetworkAutomation.monitorWanted(enabled = true, paused = false, rules = emptyList()))
    }

    // A teardown is never settled; the gap before a restart's new start passes settled = false itself.
    private fun Tracker.report(
        network: Long?,
        snapshot: Snapshot?,
        state: State = State.Stopped,
        settled: Boolean = state != State.Stopping,
        runningProfile: Long = 0,
        rules: List<Rule> = this@NetworkAutomationTest.rules,
    ) = next(network, snapshot, rules, state, settled, runningProfile) { false }

    @Test
    fun repeatedReportsActOnce() {
        val tracker = Tracker()
        assertEquals(Command.Start(-1), tracker.report(1, cellular))
        // A capability update, then the starting service's own watcher reporting the same network.
        assertNull(tracker.report(1, cellular))
        assertNull(tracker.report(1, cellular, State.Connecting))
    }

    @Test
    fun automatedStopsAndFailedStartsDoNotLoop() {
        val tracker = Tracker()
        assertEquals(Command.Stop, tracker.report(2, atHome, State.Connected, runningProfile = 3))
        assertNull(tracker.report(2, atHome, State.Stopping))
        assertNull(tracker.report(2, atHome, State.Stopped))

        assertEquals(Command.Start(-1), tracker.report(1, cellular))
        // The start failed and the service is back to Stopped on the same network.
        assertNull(tracker.report(1, cellular, State.Stopped))
    }

    @Test
    fun aChangeWhileStoppingIsEvaluatedOnceStopped() {
        val tracker = Tracker()
        assertEquals(Command.Stop, tracker.report(2, atHome, State.Connected, runningProfile = 3))
        // Mobile data takes over before the stop finishes.
        assertNull(tracker.report(1, cellular, State.Stopping))
        assertTrue(tracker.deferred)
        assertEquals(Command.Start(-1), tracker.report(1, cellular, State.Stopped))
        assertFalse(tracker.deferred)
    }

    // A restart passes through Stopped. Deciding there would see a stopped service, find nothing to
    // stop, and mark the trusted network handled before the restarted service runs on it.
    @Test
    fun aRestartDoesNotSwallowADisconnect() {
        val tracker = Tracker()
        assertNull(tracker.report(1, cellular, State.Connected))
        assertNull(tracker.report(2, atHome, State.Stopping))
        assertNull(tracker.report(2, atHome, State.Stopped, settled = false))
        assertNull(tracker.report(2, atHome, State.Stopped, settled = false))
        assertTrue(tracker.deferred)
        assertEquals(Command.Stop, tracker.report(2, atHome, State.Connecting))
        // Its own teardown then decides nothing again.
        assertNull(tracker.report(2, atHome, State.Stopping))
        assertNull(tracker.report(2, atHome, State.Stopped))
    }

    @Test
    fun aRestartDoesNotSwallowAProfileSwitch() {
        val tracker = Tracker()
        val cafe = Snapshot(Kind.WIFI, "Cafe")
        assertNull(tracker.report(1, cellular, State.Connected, runningProfile = 3))
        assertNull(tracker.report(5, cafe, State.Stopping, runningProfile = 3))
        assertNull(tracker.report(5, cafe, State.Stopped, settled = false, runningProfile = 3))
        assertEquals(Command.Switch(7), tracker.report(5, cafe, State.Connecting, runningProfile = 3))
    }

    // The kill switch ends a failed start's teardown in Connecting, holding the tunnel.
    @Test
    fun aKillSwitchBlockDecidesTheNetworkReportedDuringItsTeardown() {
        val tracker = Tracker()
        assertNull(tracker.report(1, cellular, State.Connected))
        assertNull(tracker.report(2, atHome, State.Stopping))
        assertEquals(Command.Stop, tracker.report(2, atHome, State.Connecting))
    }

    @Test
    fun losingTheNetworkDropsAStartDecidedBefore() {
        val tracker = Tracker()
        assertEquals(Command.Start(-1), tracker.report(1, cellular))
        val generation = tracker.generation
        assertNull(tracker.report(null, null))
        assertNotEquals(generation, tracker.generation)
    }

    @Test
    fun invalidatingDropsAStartDecidedBefore() {
        val tracker = Tracker()
        assertEquals(Command.Start(-1), tracker.report(1, cellular))
        val generation = tracker.generation
        tracker.invalidate()
        assertNotEquals(generation, tracker.generation)
    }

    // The pause of a manual stop replaces the hold of the manual session. Switching automation off and
    // on lifts the pause and decides the network the stopped service is still on.
    @Test
    fun aManualStopEndsTheHoldOfItsSession() {
        val tracker = Tracker()
        tracker.userStarted()
        assertNull(tracker.report(1, cellular, State.Connecting))
        assertNull(tracker.report(1, cellular, State.Connected))
        tracker.userStopped()
        assertNull(tracker.report(1, cellular, State.Stopping))
        assertNull(tracker.report(1, cellular, State.Stopped))
        tracker.reevaluate()
        assertEquals(Command.Start(-1), tracker.report(1, cellular, State.Stopped))
        assertNull(tracker.report(1, cellular, State.Stopped))
    }

    @Test
    fun aManualStopBeforeTheFirstReportLeavesNothingHeld() {
        val tracker = Tracker()
        tracker.userStarted()
        tracker.userStopped()
        assertEquals(Command.Start(-1), tracker.report(1, cellular, State.Stopped))
    }

    @Test
    fun reevaluatingDecidesTheCurrentNetworkOnceMoreButKeepsAUserHold() {
        val tracker = Tracker()
        assertEquals(Command.Start(-1), tracker.report(1, cellular))
        assertNull(tracker.report(1, cellular))
        tracker.reevaluate()
        assertEquals(Command.Start(-1), tracker.report(1, cellular))
        assertNull(tracker.report(1, cellular))

        tracker.userStarted()
        assertNull(tracker.report(2, atHome, State.Connecting))
        tracker.reevaluate()
        assertNull(tracker.report(2, atHome, State.Connected))
    }

    @Test
    fun aLostNetworkAppliesRulesAgain() {
        val tracker = Tracker()
        assertEquals(Command.Start(-1), tracker.report(1, cellular))
        assertNull(tracker.report(null, null))
        assertEquals(Command.Start(-1), tracker.report(3, cellular))
    }

    @Test
    fun aUserStartHoldsUntilTheUnderlyingNetworkChanges() {
        val tracker = Tracker()
        assertNull(tracker.report(2, atHome))
        tracker.userStarted()
        // Duplicate reports and the Wi-Fi name appearing or vanishing are the same network.
        assertNull(tracker.report(2, hiddenWifi, State.Connecting, runningProfile = 3))
        assertNull(tracker.report(2, atHome, State.Connected, runningProfile = 3))
        assertNull(tracker.report(2, atHome, State.Connected, runningProfile = 3))
        assertNull(tracker.report(2, hiddenWifi, State.Connected, runningProfile = 3))
        // Another network: rules apply again, including on the next visit home.
        assertNull(tracker.report(1, cellular, State.Connected, runningProfile = 3))
        assertEquals(Command.Stop, tracker.report(4, atHome, State.Connected, runningProfile = 3))
    }

    @Test
    fun aKnownNetworkHoldDoesNotConsumeTheFirstChangedNetwork() {
        val tracker = Tracker()
        tracker.userStarted(1)
        assertEquals(Command.Stop, tracker.report(2, atHome, State.Connecting))
        assertNull(tracker.report(2, atHome, State.Connected))

        val switching = Tracker()
        switching.userStarted(1)
        assertEquals(Command.Switch(7), switching.report(3, Snapshot(Kind.WIFI, "Cafe"), State.Connecting, runningProfile = 5))
    }

    @Test
    fun aUserStartKeepsTheChosenProfile() {
        val tracker = Tracker()
        tracker.userStarted()
        val cafe = Snapshot(Kind.WIFI, "Cafe")
        assertNull(tracker.report(5, cafe, State.Connecting, runningProfile = 3))
        assertNull(tracker.report(5, cafe, State.Connected, runningProfile = 3))
        assertEquals(Command.Switch(7), tracker.report(6, cafe, State.Connected, runningProfile = 3))
    }

    @Test
    fun aRestartedProcessEvaluatesTheCurrentNetworkAgain() {
        assertEquals(Command.Start(-1), Tracker().report(1, cellular))
        // A watcher restarted after its process died remembers nothing and reconnects.
        assertEquals(Command.Start(-1), Tracker().report(1, cellular))
    }

    @Test
    fun aHiddenNameThatKeepsTheVpnOffIsReported() {
        val tracker = Tracker()
        assertEquals(Command.StayOff, tracker.report(2, hiddenWifi))
        assertTrue(tracker.unprotected)
        assertNull(tracker.report(2, hiddenWifi))
        assertTrue(tracker.unprotected)
        // With the app open the name shows: this is Home, where staying off is what the rules want.
        assertNull(tracker.report(2, atHome))
        assertFalse(tracker.unprotected)
        assertEquals(Command.StayOff, tracker.report(3, hiddenWifi))
        assertNull(tracker.report(null, null))
        assertFalse(tracker.unprotected)
    }

    @Test
    fun editedRulesApplyToTheCurrentNetwork() {
        val tracker = Tracker()
        assertNull(tracker.report(1, cellular, rules = listOf(home)))
        assertEquals(Command.Start(-1), tracker.report(1, cellular, rules = listOf(home, mobile)))
    }

    @Test
    fun aStartDecidedBeforeANewerReportIsOutdated() {
        val tracker = Tracker()
        tracker.report(1, cellular)
        val generation = tracker.generation
        tracker.report(1, cellular)
        assertEquals(generation, tracker.generation)
        tracker.report(2, atHome)
        assertNotEquals(generation, tracker.generation)
    }
}
