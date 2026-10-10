package io.nekohasekai.sagernet.bg

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreference
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.SettingsFragment
import io.nekohasekai.sagernet.ui.SettingsPreferenceFragment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "LocationSpoofingDeviceTest"

/**
 * Runs against the platform location service, so use an emulator or a device whose mock location
 * state may change. It checks what the JVM tests can only assume: apps receive the simulated
 * location, turning it off from the notice or dismissing the notice stops it while the VPN stays
 * up, Android keeps test providers after the process that added them dies, providers the running
 * process cannot vouch for are removed only after confirmation in Settings, and only mock location
 * app op changes count as a break in ownership.
 *
 * After a stop, apps may still get real fixes, and Play services can re-report fused fixes derived
 * from the simulated ones until a new real fix arrives. The checks allow both and fail only on
 * fixes this app injected.
 */
@RunWith(AndroidJUnit4::class)
class LocationSpoofingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(LocationManager::class.java)
    private val providers = LocationSpoofing.standardProviders

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { String(it.readBytes()) }

    // Names that have a test provider installed, as the location service reports them.
    private fun mockProviders() = Regex("""^\s+(\w+) provider \[mock]:""", RegexOption.MULTILINE)
        .findAll(shell("dumpsys location")).map { it.groupValues[1] }.toSet()

    // Every test provider is gone. Play services may re-report a fused fix derived from the
    // simulated ones until a new real fix, so only the platform providers are read back.
    private fun awaitRemoved() {
        awaitTrue({ "test providers left: ${mockProviders()}" }) { mockProviders().isEmpty() }
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) await(provider) { !injected(it) }
    }

    // UiAutomation drops a command silently once its shell connection dies, so confirm the effect.
    private fun mockLocationAppOp(mode: String) {
        shell("appops set ${context.packageName} android:mock_location $mode")
        awaitTrue({ "mock location app op $mode" }) { LocationSpoofing.mockLocationAllowed(context) == (mode == "allow") }
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    // Without background location access, Android answers location reads only while the app is
    // in the foreground.
    private fun inForeground(block: (ActivityScenario<MainActivity>) -> Unit) = ActivityScenario.launch(MainActivity::class.java).use {
        val process = ActivityManager.RunningAppProcessInfo().also(ActivityManager::getMyMemoryState)
        assertTrue("foreground process", process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
        block(it)
    }

    private fun spoof(scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)): LocationSpoofing {
        lateinit var spoofing: LocationSpoofing
        onMain {
            spoofing = LocationSpoofing(context, MockLocationAccess(context), scope, SystemClock::elapsedRealtime)
            spoofing.start("48.8566, 2.3522", null, { "" }) { error("unused") }
        }
        return spoofing
    }

    @Suppress("DEPRECATION") // isMock needs API 31.
    private fun simulated(location: Location?) = location != null &&
        location.latitude == 48.8566 &&
        if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider

    // Typed coordinates are injected with 20 m accuracy; derived fused fixes report their own.
    private fun injected(location: Location?) = simulated(location) && location?.accuracy == 20f

    private fun injected(provider: String) = injected(manager.getLastKnownLocation(provider))

    private fun await(provider: String, timeoutMs: Long = 10_000, condition: (Location?) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition(manager.getLastKnownLocation(provider))) {
            check(SystemClock.elapsedRealtime() < deadline) { "$provider: ${manager.getLastKnownLocation(provider)}" }
            Thread.sleep(100)
        }
    }

    private fun awaitTrue(what: () -> String, timeoutMs: Long = 10_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline, what)
            Thread.sleep(100)
        }
    }

    // Longer than two push intervals: a loop that kept running would deliver again.
    private fun assertNoNewInjectedFixes() {
        val received = LinkedBlockingQueue<Location>()
        val listener = LocationListener { received += it }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
        try {
            Thread.sleep(5_000)
            assertTrue("new injected fix", received.none { injected(it) })
        } finally {
            manager.removeUpdates(listener)
        }
    }

    private fun recordExists() = File(context.noBackupFilesDir, "mock-location-providers").exists()

    private fun notice(): StatusBarNotification? = context.getSystemService(NotificationManager::class.java).activeNotifications.firstOrNull { it.tag == "location-spoofing" }

    private fun noticeTitle() = notice()?.notification?.extras?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()

    // Posting is asynchronous, so the notice can trail the change that causes it.
    private fun awaitNotice(title: Int): StatusBarNotification {
        val expected = context.getString(title)
        awaitTrue({ "notice \"$expected\", showing \"${noticeTitle()}\"" }) { noticeTitle() == expected }
        return checkNotNull(notice())
    }

    @Suppress("DEPRECATION") // allNetworks lists the VPN network on every supported release.
    private fun vpnConnected() = context.getSystemService(ConnectivityManager::class.java).run {
        allNetworks.any { getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    // Every node of every window; the notification shade does not answer text searches.
    private fun nodes() = sequence {
        val pending = ArrayDeque(instrumentation.uiAutomation.windows.mapNotNull { it.root })
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            yield(node)
            for (index in 0 until node.childCount) node.getChild(index)?.let(pending::addLast)
        }
    }

    private fun findNode(text: String, exact: Boolean) = nodes().firstOrNull { node ->
        val value = node.text?.toString() ?: return@firstOrNull false
        if (exact) value == text else text in value
    }

    // Visible texts, for a failure message.
    private fun screen() = nodes().mapNotNull { it.text?.toString()?.take(40) }.take(80).toList().toString()

    private fun awaitNode(text: String, exact: Boolean = false): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        awaitTrue({ "\"$text\" not on screen: ${screen()}" }) { findNode(text, exact).also { node = it } != null }
        return checkNotNull(node)
    }

    private fun clickButton(text: String) = check(awaitNode(text, exact = true).performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "$text not clicked" }

    // Swipes the notice away in the notification shade, as a user dismisses it. A gesture can have
    // no effect for reasons the test cannot see, so up to three are made until [dismissed] reports
    // the effect, and each one is logged with its outcome.
    private fun swipeAway(title: Int, dismissed: () -> Boolean) {
        val text = context.getString(title)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("cmd statusbar expand-notifications")
        try {
            for (gesture in 1..3) {
                awaitNode(text)
                Thread.sleep(1_000)
                val bounds = Rect().also(awaitNode(text)::getBoundsInScreen)
                val width = context.resources.displayMetrics.widthPixels
                shell("input swipe ${width / 5} ${bounds.centerY()} ${width * 19 / 20} ${bounds.centerY()} 200")
                val deadline = SystemClock.elapsedRealtime() + 5_000
                var effect = dismissed()
                while (!effect && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(100)
                    effect = dismissed()
                }
                Log.i(TAG, "swipe \"$text\" gesture $gesture of 3: ${if (effect) "dismissed" else "no effect"}")
                if (effect) return
            }
            error("\"$text\" not dismissed: ${screen()}")
        } finally {
            shell("cmd statusbar collapse")
        }
    }

    // What Settings sends once the user confirmed removal; returns the reported outcome.
    private fun confirmRemoval(): Int {
        val outcome = LinkedBlockingQueue<Int>()
        context.sendOrderedBroadcast(
            LocationSpoofing.confirmedRemoval(context),
            null,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    outcome += resultCode
                }
            },
            null,
            Activity.RESULT_CANCELED,
            null,
            null,
        )
        return checkNotNull(outcome.poll(30, TimeUnit.SECONDS)) { "no removal outcome" }
    }

    private fun vpnStopped() = !DataStore.serviceState.started && !vpnConnected()

    private fun withSpoofingVpn(block: () -> Unit) {
        awaitTrue({ "earlier VPN still running: ${DataStore.serviceState}" }, 30_000, ::vpnStopped)
        val group = runBlocking(Dispatchers.IO) {
            GroupManager.createGroup(ProxyGroup(name = "GPS spoofing check")).also { group ->
                // Nothing listens on the port; the VPN connects without using the server.
                val profile = ProfileManager.createProfile(
                    group.id,
                    SOCKSBean().apply {
                        name = "GPS spoofing check"
                        serverAddress = "127.0.0.1"
                        serverPort = 9
                    },
                )
                DataStore.selectedProxy = profile.id
                DataStore.serviceMode = Key.MODE_VPN
                DataStore.gpsCoordinates = "48.8566, 2.3522"
                DataStore.gpsSpoofing = true
                DataStore.configurationStore.awaitWrites()
            }
        }
        try {
            SagerNet.startService()
            await(LocationManager.GPS_PROVIDER, timeoutMs = 60_000) { injected(it) }
            assertTrue("VPN connected", vpnConnected())
            awaitNotice(R.string.gps_spoofing_active)
            block()
        } finally {
            SagerNet.stopService()
            // Waits without failing, so an earlier failure is reported as it is.
            runCatching { awaitTrue({ "VPN stopped" }, 30_000, ::vpnStopped) }
            runBlocking(Dispatchers.IO) {
                GroupManager.deleteGroup(group.id)
                DataStore.gpsSpoofing = false
                DataStore.gpsCoordinates = ""
                DataStore.configurationStore.awaitWrites()
            }
        }
    }

    @Before
    fun allow() {
        shell("pm grant ${context.packageName} ${Manifest.permission.ACCESS_FINE_LOCATION}")
        if (Build.VERSION.SDK_INT >= 33) shell("pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}")
        shell("appops set ${context.packageName} ACTIVATE_VPN allow")
        mockLocationAppOp("allow")
        val automation = instrumentation.uiAutomation
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        assertTrue("location enabled", Build.VERSION.SDK_INT < 28 || manager.isLocationEnabled)
        assertTrue(
            "fine location granted",
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED,
        )
        assertTrue("notifications", LocationSpoofing.notificationsAllowed(context))
        assertTrue("boot count", LocationSpoofing.supported(context))
    }

    @After
    fun reset() {
        mockLocationAppOp("allow")
        onMain { LocationSpoofing(context).removeConfirmed() }
        mockLocationAppOp("default")
        shell("appops set ${context.packageName} ACTIVATE_VPN default")
    }

    @Test
    fun thePlatformAcceptsTheTestProviderCalls() = inForeground {
        val access = MockLocationAccess(context)
        for (provider in providers) {
            access.add(provider)
            access.enable(provider)
            access.set(
                provider,
                Location(provider).apply {
                    latitude = 48.8566
                    longitude = 2.3522
                    accuracy = 20f
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                },
            )
            await(provider) { injected(it) }
            access.remove(provider)
            awaitTrue({ "$provider still a test provider" }) { provider !in mockProviders() }
        }
        awaitRemoved()
    }

    @Test
    fun aRequestingListenerReceivesTheSimulatedLocationUntilStop() = inForeground {
        val received = LinkedBlockingQueue<Location>()
        val listener = LocationListener { received += it }
        manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper())
        try {
            val spoofing = spoof()
            // The service pushes every two seconds, so a listener keeps getting fresh fixes.
            // Real fixes may arrive in between.
            val deadline = SystemClock.elapsedRealtime() + 15_000
            var delivered = 0
            while (delivered < 2) {
                val fix = received.poll(deadline - SystemClock.elapsedRealtime(), TimeUnit.MILLISECONDS)
                checkNotNull(fix) { "$delivered injected fixes delivered" }
                if (injected(fix)) delivered++
            }
            var stopped = false
            onMain { stopped = spoofing.stop() }
            assertTrue("known providers removed", stopped)
        } finally {
            manager.removeUpdates(listener)
        }
        awaitRemoved()
        assertNoNewInjectedFixes()
    }

    @Test
    fun providersOutliveTheirProcessUntilRemovalIsConfirmed() = inForeground {
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        spoof(process)
        for (provider in providers) await(provider) { injected(it) }

        // The process dies without running any cleanup.
        process.cancel()
        Thread.sleep(3_000)
        for (provider in providers) assertTrue(provider, injected(provider))

        // A later process cannot vouch for them, so turning off removes nothing.
        var released = true
        onMain { released = LocationSpoofing(context).stop() }
        assertFalse(released)
        awaitNotice(R.string.gps_spoofing_stuck)
        for (provider in providers) assertTrue(provider, injected(provider))

        assertEquals(Activity.RESULT_OK, confirmRemoval())
        awaitRemoved()
        awaitTrue({ "notice removed" }) { notice() == null }
        assertNoNewInjectedFixes()
    }

    @Test
    fun aMockLocationAppChangeLeavesTheLocationForConfirmation() = inForeground {
        val spoofing = spoof()
        await(LocationManager.GPS_PROVIDER) { injected(it) }

        mockLocationAppOp("deny")
        awaitNotice(R.string.gps_spoofing_stuck)
        var released = true
        onMain { released = spoofing.stop() }
        assertFalse(released)
        assertTrue(injected(LocationManager.GPS_PROVIDER))

        // Selected again, the app still asks before removing.
        mockLocationAppOp("allow")
        onMain { released = spoofing.stop() }
        assertFalse(released)
        assertTrue(injected(LocationManager.GPS_PROVIDER))
        assertEquals(Activity.RESULT_OK, confirmRemoval())
        awaitRemoved()
    }

    @Test
    fun onlyMockLocationChangesBreakOwnership() = inForeground {
        // What Android reports to a watch like the one spoofing makes.
        val reported = LinkedBlockingQueue<Pair<String?, String?>>()
        val appOps = context.getSystemService(AppOpsManager::class.java)
        val watch = AppOpsManager.OnOpChangedListener { op, packageName -> reported += op to packageName }
        appOps.startWatchingMode(AppOpsManager.OPSTR_MOCK_LOCATION, null, watch)
        // For the record only: a watch on this app's package hears of its other ops as well.
        val packageWatch = AppOpsManager.OnOpChangedListener { op, packageName -> Log.i(TAG, "package watch reported $op for $packageName") }
        appOps.startWatchingMode(AppOpsManager.OPSTR_MOCK_LOCATION, context.packageName, packageWatch)
        val uidOf = { packageName: String -> runCatching { context.packageManager.getPackageUid(packageName, 0) }.getOrDefault(-1) }
        Log.i(
            TAG,
            "watching from uid ${Process.myUid()}; ${context.packageName} uid ${uidOf(context.packageName)}, " +
                "${instrumentation.context.packageName} uid ${uidOf(instrumentation.context.packageName)}, com.android.shell uid ${uidOf("com.android.shell")}",
        )
        try {
            // Another op of this app changes: spoofing goes on, and its providers go without asking.
            var spoofing = spoof()
            await(LocationManager.GPS_PROVIDER) { injected(it) }
            shell("appops set ${context.packageName} PICTURE_IN_PICTURE deny")
            awaitTrue({ "picture-in-picture op unchanged" }) { "deny" in shell("appops get ${context.packageName} PICTURE_IN_PICTURE") }
            Thread.sleep(5_000)
            Log.i(TAG, "watch reported after another op changed: ${reported.toList()}")
            assertTrue("reported ${reported.toList()}", reported.isEmpty())
            assertEquals(context.getString(R.string.gps_spoofing_active), noticeTitle())
            var released = false
            onMain { released = spoofing.stop() }
            assertTrue(released)
            awaitRemoved()

            // Another app may now mock locations and replace a provider, so removal waits.
            spoofing = spoof()
            await(LocationManager.GPS_PROVIDER) { injected(it) }
            shell("appops set com.android.shell android:mock_location allow")
            awaitNotice(R.string.gps_spoofing_stuck)
            Log.i(TAG, "watch reported after another app's op changed: ${reported.toList()}")
            assertTrue("reported ${reported.toList()}", AppOpsManager.OPSTR_MOCK_LOCATION to "com.android.shell" in reported)
            onMain { released = spoofing.stop() }
            assertFalse(released)
            assertTrue(injected(LocationManager.GPS_PROVIDER))
            assertEquals(Activity.RESULT_OK, confirmRemoval())
            awaitRemoved()
        } finally {
            appOps.stopWatchingMode(watch)
            appOps.stopWatchingMode(packageWatch)
            shell("appops set ${context.packageName} PICTURE_IN_PICTURE default")
            shell("appops set com.android.shell android:mock_location default")
        }
    }

    @Test
    fun dismissingTheNoticeTurnsSpoofingOffAndLeavesTheVpnConnected() = inForeground {
        withSpoofingVpn {
            swipeAway(R.string.gps_spoofing_active) { notice() == null }
            awaitRemoved()
            awaitTrue({ "turned off" }) { !DataStore.gpsSpoofing }
            assertNoNewInjectedFixes()
            assertTrue("notice posted again: ${noticeTitle()}", notice() == null)
            assertFalse(recordExists())
            assertTrue("VPN still connected", vpnConnected())
        }
    }

    @Test
    fun dismissingTheRecoveryNoticeShowsItAgainUntilRemovalIsConfirmed() = inForeground {
        withSpoofingVpn {
            mockLocationAppOp("deny")
            awaitNotice(R.string.gps_spoofing_stuck)

            var posted = checkNotNull(notice()).postTime
            swipeAway(R.string.gps_spoofing_stuck) { (notice()?.postTime ?: 0) > posted }
            awaitTrue({ "turned off" }) { !DataStore.gpsSpoofing }
            assertEquals(context.getString(R.string.gps_spoofing_stuck), noticeTitle())
            assertTrue(injected(LocationManager.GPS_PROVIDER))
            assertTrue(recordExists())

            // Selected again as the mock location app, a dismissal still removes nothing.
            mockLocationAppOp("allow")
            posted = checkNotNull(notice()).postTime
            swipeAway(R.string.gps_spoofing_stuck) { (notice()?.postTime ?: 0) > posted }
            assertEquals(context.getString(R.string.gps_spoofing_stuck), noticeTitle())
            assertTrue(injected(LocationManager.GPS_PROVIDER))

            assertEquals(Activity.RESULT_OK, confirmRemoval())
            awaitRemoved()
            awaitTrue({ "notice removed" }) { notice() == null }
            assertNoNewInjectedFixes()
            assertTrue("notice posted again: ${noticeTitle()}", notice() == null)
            assertFalse(DataStore.gpsSpoofing)
            assertFalse(recordExists())
            assertTrue("VPN still connected", vpnConnected())
        }
    }

    @Test
    fun afterTheServiceProcessDiesTurningOffLeavesRemovalForConfirmation() = inForeground {
        withSpoofingVpn {
            // Turning off from the notice removes what the running process added.
            awaitNotice(R.string.gps_spoofing_active).notification.actions.single().actionIntent.send()
            awaitRemoved()
            awaitTrue({ "notice removed" }) { notice() == null }
            awaitTrue({ "turned off" }) { !DataStore.gpsSpoofing }
            assertNoNewInjectedFixes()
            assertTrue("VPN still connected", vpnConnected())

            // Spoofing again, then the service process dies without any cleanup.
            runBlocking(Dispatchers.IO) {
                DataStore.gpsSpoofing = true
                DataStore.configurationStore.awaitWrites()
            }
            SagerNet.reloadService()
            await(LocationManager.GPS_PROVIDER, timeoutMs = 60_000) { injected(it) }
            val leftover = awaitNotice(R.string.gps_spoofing_active)
            val service = context.getSystemService(ActivityManager::class.java).runningAppProcesses
                .single { it.processName == "${context.packageName}:bg" }
            Process.killProcess(service.pid)
            Thread.sleep(3_000)
            assertTrue("providers outlive the process", injected(LocationManager.GPS_PROVIDER))

            // Turning off from the notice the dead process left stops nothing it cannot vouch for.
            leftover.notification.actions.single().actionIntent.send()
            awaitNotice(R.string.gps_spoofing_stuck)
            assertTrue(injected(LocationManager.GPS_PROVIDER))

            assertEquals(Activity.RESULT_OK, confirmRemoval())
            awaitRemoved()
            awaitTrue({ "recovery notice removed" }) { notice() == null }
            assertNoNewInjectedFixes()
        }
    }

    // Opens Settings, Leak protection the way the hub does.
    private fun openProtectionSettings(scenario: ActivityScenario<MainActivity>): SettingsPreferenceFragment {
        lateinit var page: SettingsPreferenceFragment
        scenario.onActivity { activity ->
            activity.displayFragmentWithId(R.id.nav_settings)
            activity.supportFragmentManager.executePendingTransactions()
            val settings = activity.supportFragmentManager.findFragmentById(R.id.fragment_holder) as SettingsFragment
            settings.childFragmentManager.executePendingTransactions()
            val hub = settings.childFragmentManager.findFragmentById(R.id.settings) as SettingsPreferenceFragment
            settings.onPreferenceStartScreen(hub, checkNotNull(hub.findPreference<PreferenceScreen>("screen_protection")))
            settings.childFragmentManager.executePendingTransactions()
            page = settings.childFragmentManager.findFragmentById(R.id.settings) as SettingsPreferenceFragment
        }
        return page
    }

    @Test
    fun removalWaitsForConfirmationInSettings() = inForeground { scenario ->
        // A simulated location left by a process that died.
        val process = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        spoof(process)
        await(LocationManager.GPS_PROVIDER) { injected(it) }
        process.cancel()
        assertTrue(LocationSpoofing.mayRemain(context))

        val page = openProtectionSettings(scenario)
        val remove = checkNotNull(page.findPreference<Preference>(Key.GPS_SPOOFING_REMOVE))
        assertTrue("removal offered", remove.isVisible)
        val tap = { scenario.onActivity { checkNotNull(remove.onPreferenceClickListener).onPreferenceClick(remove) } }
        val switch = checkNotNull(page.findPreference<SwitchPreference>(Key.GPS_SPOOFING))
        val turnOn = {
            var accepted = true
            scenario.onActivity { accepted = checkNotNull(switch.onPreferenceChangeListener).onPreferenceChange(switch, true) }
            assertFalse("switch turned on directly", accepted)
        }

        // Turning spoofing on is refused while removal is pending; the dialog leads to the prompt.
        turnOn()
        awaitNode(context.getString(R.string.gps_spoofing_remove_first))
        clickButton(context.getString(R.string.gps_spoofing_remove_action))
        awaitNode(providers.joinToString(", "))
        clickButton(context.getString(android.R.string.cancel))
        assertFalse(DataStore.gpsSpoofing)

        // The prompt names the providers, and cancelling removes nothing.
        tap()
        awaitNode(providers.joinToString(", "))
        clickButton(context.getString(android.R.string.cancel))
        Thread.sleep(3_000)
        assertTrue(injected(LocationManager.GPS_PROVIDER))
        assertTrue(LocationSpoofing.mayRemain(context))

        // The mock location app changes between the prompt and the removal.
        tap()
        awaitNode(providers.joinToString(", "))
        mockLocationAppOp("deny")
        clickButton(context.getString(R.string.gps_spoofing_remove_action))
        awaitNode(context.getString(R.string.gps_spoofing_remove_failed))
        assertTrue(injected(LocationManager.GPS_PROVIDER))
        assertTrue(LocationSpoofing.mayRemain(context))
        awaitNotice(R.string.gps_spoofing_stuck)

        mockLocationAppOp("allow")
        tap()
        clickButton(context.getString(R.string.gps_spoofing_remove_action))
        awaitNode(context.getString(R.string.gps_spoofing_removed))
        awaitRemoved()
        assertFalse(LocationSpoofing.mayRemain(context))
        awaitTrue({ "notice removed" }) { notice() == null }
        assertFalse(DataStore.gpsSpoofing)
        var shown = true
        onMain { shown = remove.isVisible }
        assertFalse("removal still offered", shown)

        // Once removed, turning on goes to the usual warning, and stays off when cancelled.
        turnOn()
        awaitNode(context.getString(R.string.gps_spoofing_warning, DataStore.gpsLookupUrl).substringBefore('\n'))
        clickButton(context.getString(android.R.string.cancel))
        assertFalse(DataStore.gpsSpoofing)
    }
}
