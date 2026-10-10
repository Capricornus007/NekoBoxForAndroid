package io.nekohasekai.sagernet.bg

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import io.nekohasekai.sagernet.AUTOMATION_NOTIFICATION_CHANNEL
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.NetworkAutomation.Blocked
import io.nekohasekai.sagernet.bg.NetworkAutomation.Rule
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowVpnService
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.TimeUnit

/**
 * The watcher's start and stop contract, boot and user starts, decisions around service restarts,
 * other apps' VPNs, and network changes end to end.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class NetworkAutomationLifecycleTest {

    private val connectOnMobile = Rule(NetworkAutomation.Kind.MOBILE, action = NetworkAutomation.Action.CONNECT)
    private val disconnectOnWifi = Rule(NetworkAutomation.Kind.WIFI, action = NetworkAutomation.Action.DISCONNECT)
    private val disconnectAtHome = Rule(NetworkAutomation.Kind.SSID, "Home", NetworkAutomation.Action.DISCONNECT)

    private lateinit var app: Application
    private lateinit var watcher: ComponentName
    private val logSink = Logs.sink

    private val manager get() = shadowOf(SagerNet.connectivity)

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        watcher = ComponentName(app, NetworkAutomationService::class.java)
        // One process here; Robolectric cannot connect Room's remote invalidation service.
        shadowOf(app).declareComponentUnbindable(ComponentName(app, "androidx.room.MultiInstanceInvalidationService"))
        ConfigBuilderTestEnv.reset()
        DataStore.serviceState = BaseService.State.Stopped
        Logs.sink = {}
        // Automation state lives for the whole process: start every test from a lost network.
        NetworkAutomation.onServiceState(settled = true)
        NetworkAutomation.onNetwork(null)
        idle()
        shadowOf(app).clearStartedServices()
        while (shadowOf(app).nextStoppedService != null) Unit
    }

    @After
    fun tearDown() {
        Logs.sink = logSink
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun automation(enabled: Boolean, paused: Boolean, vararg rules: Rule) {
        DataStore.networkAutomation = enabled
        DataStore.automationPaused = paused
        NetworkAutomation.saveRules(rules.toList())
    }

    private fun <T : Any> await(timeoutMs: Long = 5_000, poll: () -> T?): T? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            poll()?.let { return it }
            idle()
            Thread.sleep(10)
        }
        return poll()
    }

    private fun broadcasts(action: String, automated: Boolean? = null) = shadowOf(app).broadcastIntents.count {
        it.action == action && (automated == null || it.getBooleanExtra(Action.EXTRA_AUTOMATED, !automated) == automated)
    }

    // Whether the user ever granted the VPN permission.
    private fun vpnPermission(mode: Int) {
        shadowOf(app.getSystemService(AppOpsManager::class.java)).setMode("android:activate_vpn", Process.myUid(), app.packageName, mode)
    }

    private fun capabilities(transport: Int) = ShadowNetworkCapabilities.newInstance().also { shadowOf(it).addTransportType(transport) }

    private val cellular = capabilities(NetworkCapabilities.TRANSPORT_CELLULAR)

    private fun mobileNetwork(netId: Int) = ShadowNetwork.newInstance(netId).also { manager.setNetworkCapabilities(it, cellular) }

    private fun wifiNetwork(netId: Int) = ShadowNetwork.newInstance(netId).also {
        manager.setNetworkCapabilities(it, capabilities(NetworkCapabilities.TRANSPORT_WIFI))
    }

    @Suppress("DEPRECATION")
    private fun vpnInfo() = ShadowNetworkInfo.newInstance(
        NetworkInfo.DetailedState.CONNECTED,
        ConnectivityManager.TYPE_VPN,
        0,
        true,
        NetworkInfo.State.CONNECTED,
    )

    /** VPN capabilities; [owner] is what Android reveals of the owner (Android 10 and later). */
    private fun vpnCapabilities(owner: Int) = capabilities(NetworkCapabilities.TRANSPORT_VPN).also {
        ReflectionHelpers.callInstanceMethod<Any>(it, "setOwnerUid", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, owner))
    }

    /** A VPN network as Android lists it to this app; [owner] is what Android reveals of its owner. */
    private fun vpnNetwork(netId: Int, owner: Int = Process.INVALID_UID): Network {
        val network = ShadowNetwork.newInstance(netId)
        manager.addNetwork(network, vpnInfo())
        manager.setNetworkCapabilities(network, vpnCapabilities(owner))
        return network
    }

    /** Makes a VPN the default network of this app; [owner] is what Android reveals of its owner. */
    private fun activeVpn(owner: Int) {
        manager.setActiveNetworkInfo(vpnInfo())
        manager.setNetworkCapabilities(SagerNet.connectivity.activeNetwork, vpnCapabilities(owner))
    }

    /**
     * Runs [block] with a watcher started as Android starts it, and its network callback, for a
     * connect-on-mobile rule, the selected profile 5 and the given VPN permission.
     */
    private fun withWatcher(
        serviceMode: String,
        vpnPermissionMode: Int = AppOpsManager.MODE_IGNORED,
        block: (NetworkAutomationService, ConnectivityManager.NetworkCallback) -> Unit,
    ) {
        automation(enabled = true, paused = false, connectOnMobile)
        DataStore.selectedProxy = 5L
        DataStore.serviceMode = serviceMode
        vpnPermission(vpnPermissionMode)
        val controller = Robolectric.buildService(NetworkAutomationService::class.java).create()
        controller.get().onStartCommand(Intent(), 0, 1)
        try {
            block(controller.get(), requireNotNull(await { manager.networkCallbacks.singleOrNull() }))
        } finally {
            controller.destroy()
            idle()
        }
        assertTrue(manager.networkCallbacks.isEmpty())
    }

    @Test
    fun theWatcherStartsWhileAConnectRuleCanActAndIsOtherwiseAskedToCheck() {
        automation(enabled = true, paused = false, connectOnMobile)
        NetworkAutomationService.sync(app)
        assertEquals(watcher, shadowOf(app).nextStartedService?.component)

        // A user stop paused automation; disconnect rules act only while the VPN runs, which
        // watches for them itself; or automation is off. None of these stops the watcher from here.
        for (settings in listOf(
            { automation(enabled = true, paused = true, connectOnMobile) },
            { automation(enabled = true, paused = false, disconnectAtHome) },
            { automation(enabled = false, paused = false, connectOnMobile) },
        )) {
            settings()
            val checks = broadcasts(NetworkAutomationService.ACTION_RECHECK)
            NetworkAutomationService.sync(app)
            assertEquals(checks + 1, broadcasts(NetworkAutomationService.ACTION_RECHECK))
        }
        assertNull(shadowOf(app).nextStartedService)
        assertNull(shadowOf(app).nextStoppedService)
    }

    @Test
    fun onlyAUserStartLiftsThePause() {
        automation(enabled = true, paused = true, connectOnMobile)
        DataStore.configurationStore.putBoolean(Key.PERSIST_ACROSS_REBOOT, true)
        DataStore.selectedProxy = 5L

        // Boot brings the service back but keeps the pause, so the watcher stays off.
        BootReceiver.restore(app)
        val boot = shadowOf(app).nextStartedService
        assertEquals(ProxyService::class.java.name, boot?.component?.className)
        assertTrue(boot!!.getBooleanExtra(Action.EXTRA_AUTOMATED, false))
        assertNull(shadowOf(app).nextStartedService)
        assertTrue(DataStore.automationPaused)

        SagerNet.startService(5L, byUser = false)
        assertTrue(shadowOf(app).nextStartedService!!.getBooleanExtra(Action.EXTRA_AUTOMATED, false))
        // Rule edits do not lift it either.
        NetworkAutomation.onRulesChanged(app)
        assertTrue(DataStore.automationPaused)

        SagerNet.startService(5L)
        assertFalse(shadowOf(app).nextStartedService!!.getBooleanExtra(Action.EXTRA_AUTOMATED, true))
        assertFalse(DataStore.automationPaused)
        // The watcher follows once the lifted pause is on disk.
        assertEquals(watcher, await { shadowOf(app).nextStartedService }?.component)
    }

    @Test
    fun switchingAutomationClearsPauseAndBlockAndFollowsTheBootReceiver() {
        automation(enabled = false, paused = true, connectOnMobile)
        DataStore.networkAutomationBlocked = Blocked.BACKGROUND_START.name

        NetworkAutomation.onSwitched(app, true)
        assertFalse(DataStore.automationPaused)
        assertNull(NetworkAutomation.blocked)
        assertTrue(BootReceiver.enabled)
        val start = await { shadowOf(app).nextStartedService }
        assertEquals(watcher, start?.component)
        // Switched on, the current network is decided anew.
        assertTrue(start!!.getBooleanExtra(NetworkAutomationService.EXTRA_REEVALUATE, false))

        val checks = broadcasts(NetworkAutomationService.ACTION_RECHECK)
        NetworkAutomation.onSwitched(app, false)
        assertFalse(BootReceiver.enabled)
        assertNotNull(await { broadcasts(NetworkAutomationService.ACTION_RECHECK).takeIf { it > checks } })
    }

    @Test
    fun aStickyRestartPromotesFirstAndStopsWhenSettingsSaySo() {
        automation(enabled = false, paused = false, connectOnMobile)
        val controller = Robolectric.buildService(NetworkAutomationService::class.java).create()
        val service = controller.get()
        // Android restarts a sticky service after its process died with a null intent.
        assertEquals(Service.START_STICKY, service.onStartCommand(null, 0, 1))
        val shadow = shadowOf(service)
        assertEquals(2, shadow.lastForegroundNotificationId)
        assertEquals(AUTOMATION_NOTIFICATION_CHANNEL, shadow.lastForegroundNotification.channelId)
        assertNotNull(await { shadow.isStoppedBySelf.takeIf { it } })
        controller.destroy()
    }

    // The process that sends the check may still hold a pause another process lifted; only the
    // watcher, reading the settings itself, decides to stop.
    @Test
    fun onlyTheWatcherDecidesToStop() = withWatcher(Key.MODE_PROXY) { service, _ ->
        DataStore.automationPaused = true
        runBlocking { DataStore.configurationStore.awaitWrites() }
        ConfigBuilderTestEnv.io { PublicDatabase.kvPairDao.put(KeyValuePair(Key.NETWORK_AUTOMATION_PAUSED).put(false)) }
        NetworkAutomationService.sync(app)
        await(300) { null }
        assertNull(shadowOf(app).nextStoppedService)
        assertFalse(shadowOf(service).isStoppedBySelf)

        // A pause it reads itself stops it.
        DataStore.automationPaused = true
        NetworkAutomationService.sync(app)
        assertNotNull(await { shadowOf(service).isStoppedBySelf.takeIf { it } })
        assertNull(shadowOf(app).nextStoppedService)
    }

    // A watcher destroyed while it still reads the settings must leave the listener and the
    // notification to the next one.
    @Test
    fun aRetiredWatcherNeverRegistersOrNotifies() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = app.getSystemService(NotificationManager::class.java)
        automation(enabled = true, paused = false, connectOnMobile)
        val first = Robolectric.buildService(NetworkAutomationService::class.java).create()
        first.get().onStartCommand(Intent(), 0, 1)
        first.destroy()
        val second = Robolectric.buildService(NetworkAutomationService::class.java).create()
        second.get().onStartCommand(Intent(), 0, 1)
        assertNotNull(await { manager.networkCallbacks.singleOrNull() })
        // The live watcher shows its status.
        assertNotNull(await { shadowOf(notifications).allNotifications.takeIf { it.isNotEmpty() } })
        second.destroy()
        idle()
        // A listener registered by the first watcher would still be there.
        assertTrue(manager.networkCallbacks.isEmpty())

        notifications.cancelAll()
        NetworkAutomation.blocked = Blocked.BACKGROUND_START
        NetworkAutomationService.refresh()
        idle()
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    // A local proxy needs no VPN permission, so it starts where it was never granted.
    @Test
    fun aNetworkChangeStartsTheServiceOnceAndAPauseStopsFurtherStarts() = withWatcher(Key.MODE_PROXY) { _, callback ->
        val first = mobileNetwork(101)
        val second = mobileNetwork(102)
        try {
            callback.onAvailable(first)
            val start = requireNotNull(await { shadowOf(app).nextStartedService })
            assertEquals(ProxyService::class.java.name, start.component?.className)
            assertTrue(start.getBooleanExtra(Action.EXTRA_AUTOMATED, false))
            assertEquals(5L, start.getLongExtra(Action.EXTRA_PROFILE_ID, -1L))

            // More callbacks about the same network start nothing more.
            callback.onCapabilitiesChanged(first, cellular)
            assertNull(await(300) { shadowOf(app).nextStartedService })

            // A user stop paused automation before the next network came up.
            callback.onLost(first)
            DataStore.automationPaused = true
            callback.onAvailable(second)
            assertNull(await(300) { shadowOf(app).nextStartedService })
        } finally {
            callback.onLost(second)
        }
    }

    // The VPN cannot run without the permission, and Android 14 and later would not even let the
    // service go to the foreground.
    @Test
    fun aVpnStartWithoutPermissionIsReportedNotAttemptedOrRetried() = withWatcher(Key.MODE_VPN) { _, callback ->
        val network = mobileNetwork(103)
        try {
            callback.onAvailable(network)
            assertEquals(Blocked.VPN_PERMISSION, await { NetworkAutomation.blocked })
            callback.onCapabilitiesChanged(network, cellular)
            assertNull(await(300) { shadowOf(app).nextStartedService })

            // Connecting once yourself settles it, and only that start runs.
            SagerNet.startService(5L)
            val start = requireNotNull(shadowOf(app).nextStartedService)
            assertEquals(VpnService::class.java.name, start.component?.className)
            assertFalse(start.getBooleanExtra(Action.EXTRA_AUTOMATED, true))
            assertNull(NetworkAutomation.blocked)
            callback.onCapabilitiesChanged(network, cellular)
            assertEquals(watcher, await { shadowOf(app).nextStartedService }?.component)
            assertNull(await(300) { shadowOf(app).nextStartedService })
        } finally {
            callback.onLost(network)
        }
    }

    // Starting the VPN would take over the other app's VPN even with the permission granted.
    @Test
    fun aRuleNeverStartsTheVpnOverAnotherAppsVpn() {
        val other = vpnNetwork(104)
        try {
            withWatcher(Key.MODE_VPN, AppOpsManager.MODE_ALLOWED) { _, callback ->
                val network = mobileNetwork(105)
                try {
                    callback.onAvailable(network)
                    assertEquals(Blocked.OTHER_VPN, await { NetworkAutomation.blocked })
                    assertNull(await(300) { shadowOf(app).nextStartedService })
                } finally {
                    callback.onLost(network)
                }
            }
        } finally {
            manager.removeNetwork(other)
        }
        // Once it is gone, the next network change starts the VPN and clears the refusal.
        withWatcher(Key.MODE_VPN, AppOpsManager.MODE_ALLOWED) { _, callback ->
            val network = mobileNetwork(106)
            try {
                callback.onAvailable(network)
                assertEquals(VpnService::class.java.name, await { shadowOf(app).nextStartedService }?.component?.className)
                assertNull(NetworkAutomation.blocked)
            } finally {
                callback.onLost(network)
            }
        }
    }

    @Test
    fun thisAppsOwnVpnIsToldApartFromAndroid11() {
        assertFalse(NetworkAutomation.otherVpnActive())
        val own = vpnNetwork(107, owner = Process.myUid())
        try {
            assertFalse(NetworkAutomation.otherVpnActive())
            val other = vpnNetwork(108)
            try {
                assertTrue(NetworkAutomation.otherVpnActive())
            } finally {
                manager.removeNetwork(other)
            }
        } finally {
            manager.removeNetwork(own)
        }
    }

    // Before Android 11 the owner is not revealed, so any VPN counts.
    @Test
    @Config(sdk = [28])
    fun beforeAndroid11AnyVpnCounts() {
        val network = ShadowNetwork.newInstance(109)
        manager.addNetwork(network, vpnInfo())
        manager.setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        try {
            assertTrue(NetworkAutomation.otherVpnActive())
        } finally {
            manager.removeNetwork(network)
        }
    }

    // A user stop, or Android revoking the VPN, drops a start already decided.
    @Test
    fun aStopOfTheUsersDropsAStartAlreadyDecided() = withWatcher(Key.MODE_PROXY) { _, callback ->
        val network = mobileNetwork(110)
        try {
            callback.onAvailable(network)
            NetworkAutomation.onUserStop(app)
            assertNull(await(500) { shadowOf(app).nextStartedService?.takeIf { it.component?.className == ProxyService::class.java.name } })
            assertTrue(DataStore.automationPaused)
        } finally {
            callback.onLost(network)
        }
    }

    @Test
    fun losingTheNetworkDropsAStartAlreadyDecided() = withWatcher(Key.MODE_PROXY) { _, callback ->
        val network = mobileNetwork(111)
        callback.onAvailable(network)
        callback.onLost(network)
        assertNull(await(500) { shadowOf(app).nextStartedService })
    }

    @Test
    fun aWatcherStartRepairsTheBootReceiverAndClearsOnlyItsOwnRefusal() {
        DataStore.configurationStore.putBoolean(Key.PERSIST_ACROSS_REBOOT, false)
        BootReceiver.enabled = false
        NetworkAutomation.blocked = Blocked.WATCHER_START
        withWatcher(Key.MODE_PROXY) { service, _ ->
            assertTrue(BootReceiver.enabled)
            assertNull(NetworkAutomation.blocked)

            NetworkAutomation.blocked = Blocked.BACKGROUND_START
            service.onStartCommand(Intent(), 0, 2)
            await(300) { null }
            assertEquals(Blocked.BACKGROUND_START, NetworkAutomation.blocked)
        }
    }

    @Test
    fun bootLeavesTheVpnOffWithoutPermissionOrBesideAnotherVpn() {
        automation(enabled = false, paused = false)
        DataStore.configurationStore.putBoolean(Key.PERSIST_ACROSS_REBOOT, true)
        DataStore.selectedProxy = 5L
        DataStore.serviceMode = Key.MODE_VPN

        vpnPermission(AppOpsManager.MODE_IGNORED)
        BootReceiver.restore(app)
        assertNull(shadowOf(app).nextStartedService)

        vpnPermission(AppOpsManager.MODE_ALLOWED)
        val other = vpnNetwork(112)
        try {
            BootReceiver.restore(app)
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            manager.removeNetwork(other)
        }

        BootReceiver.restore(app)
        val start = requireNotNull(shadowOf(app).nextStartedService)
        assertEquals(VpnService::class.java.name, start.component?.className)
        assertTrue(start.getBooleanExtra(Action.EXTRA_AUTOMATED, false))
    }

    @Test
    fun switchingAutomationOnDecidesTheCurrentNetworkOnce() = withWatcher(Key.MODE_PROXY) { service, callback ->
        val network = mobileNetwork(113)
        try {
            callback.onAvailable(network)
            assertEquals(ProxyService::class.java.name, await { shadowOf(app).nextStartedService }?.component?.className)
            // The service never came up; the same network does not start it again.
            callback.onCapabilitiesChanged(network, cellular)
            assertNull(await(300) { shadowOf(app).nextStartedService })

            val checks = broadcasts(NetworkAutomationService.ACTION_RECHECK)
            NetworkAutomation.onSwitched(app, false)
            assertNotNull(await { broadcasts(NetworkAutomationService.ACTION_RECHECK).takeIf { it > checks } })
            NetworkAutomation.onSwitched(app, true)
            val start = requireNotNull(await { shadowOf(app).nextStartedService })
            assertEquals(watcher, start.component)
            service.onStartCommand(start, 0, 2)
            assertEquals(ProxyService::class.java.name, await { shadowOf(app).nextStartedService }?.component?.className)
            callback.onCapabilitiesChanged(network, cellular)
            assertNull(await(300) { shadowOf(app).nextStartedService })
        } finally {
            callback.onLost(network)
        }
    }

    // The service process outlives the manual session. Its stop must not leave the session's hold behind,
    // or switching automation off and on would skip the network the stopped service is still on.
    @Test
    fun switchingAutomationBackOnAfterAManualStopDecidesTheSameNetwork() = withWatcher(Key.MODE_PROXY) { service, callback ->
        val network = mobileNetwork(114)
        try {
            // A manual session on mobile data: the connect rule leaves it alone.
            NetworkAutomation.onServiceStart(byUser = true)
            DataStore.serviceState = BaseService.State.Connected
            callback.onAvailable(network)
            assertNull(await(300) { shadowOf(app).nextStartedService })

            // The manual stop pauses automation, and the service stops on the same network.
            NetworkAutomation.onUserStop(app)
            DataStore.serviceState = BaseService.State.Stopped
            callback.onCapabilitiesChanged(network, cellular)
            assertNull(await(300) { shadowOf(app).nextStartedService })

            val checks = broadcasts(NetworkAutomationService.ACTION_RECHECK)
            NetworkAutomation.onSwitched(app, false)
            assertNotNull(await { broadcasts(NetworkAutomationService.ACTION_RECHECK).takeIf { it > checks } })
            NetworkAutomation.onSwitched(app, true)
            val start = requireNotNull(await { shadowOf(app).nextStartedService })
            assertEquals(watcher, start.component)
            service.onStartCommand(start, 0, 2)
            assertEquals(ProxyService::class.java.name, await { shadowOf(app).nextStartedService }?.component?.className)
        } finally {
            callback.onLost(network)
        }
    }

    // ---- the watcher's notice ----

    // Android takes the notice down with a stopped watcher. A status update queued before the destruction
    // posts it again, so the destruction removes it once more.
    @Test
    fun aStoppedWatcherTakesARepostedNoticeAlong() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = app.getSystemService(NotificationManager::class.java)
        automation(enabled = true, paused = false, connectOnMobile)
        val controller = Robolectric.buildService(NetworkAutomationService::class.java).create()
        val watcherService = controller.get()
        var destroyed = false
        try {
            watcherService.onStartCommand(Intent(), 0, 1)
            assertNotNull(await { manager.networkCallbacks.singleOrNull() })

            watcherService.stopSelf(1)
            watcherService.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            assertTrue(shadowOf(notifications).allNotifications.isEmpty())
            NetworkAutomationService.refresh()
            idle()
            assertFalse(shadowOf(notifications).allNotifications.isEmpty())

            controller.destroy()
            destroyed = true
            idle()
            assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        } finally {
            if (!destroyed) controller.destroy()
            idle()
        }
    }

    // The notice belongs to the watcher in the foreground; an older instance destroyed later leaves it.
    @Test
    fun aRetiredWatcherLeavesTheNoticeOfItsSuccessor() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val notifications = app.getSystemService(NotificationManager::class.java)
        automation(enabled = true, paused = false, connectOnMobile)
        val older = Robolectric.buildService(NetworkAutomationService::class.java).create()
        val newer = Robolectric.buildService(NetworkAutomationService::class.java).create()
        var olderDestroyed = false
        try {
            older.get().onStartCommand(Intent(), 0, 1)
            // Robolectric would otherwise remove the shared notice along with the older instance itself.
            older.get().stopForeground(Service.STOP_FOREGROUND_DETACH)
            newer.get().onStartCommand(Intent(), 0, 1)
            assertNotNull(await { manager.networkCallbacks.singleOrNull() })
            assertNotNull(await { shadowOf(notifications).allNotifications.takeIf { it.isNotEmpty() } })

            older.destroy()
            olderDestroyed = true
            idle()
            assertFalse(shadowOf(notifications).allNotifications.isEmpty())
        } finally {
            if (!olderDestroyed) older.destroy()
            newer.destroy()
            idle()
        }
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())
    }

    // ---- this app's VPN service deciding a start that is not manual ----

    /** Runs [block] with this app's VPN service, stopped, in VPN mode and without a selected profile. */
    private fun withVpnService(block: (VpnService) -> Unit) {
        DataStore.serviceMode = Key.MODE_VPN
        DataStore.selectedProxy = 0L
        val controller = Robolectric.buildService(VpnService::class.java).create()
        val service = controller.get()
        try {
            block(service)
        } finally {
            // Before the main thread runs again, so a start that went ahead never reaches the core.
            service.data.connectingJob?.cancel()
            if (service.data.closeReceiverRegistered) service.unregisterReceiver(service.data.receiver)
            controller.destroy()
            DataStore.baseService = null
            DataStore.serviceState = BaseService.State.Stopped
            NetworkAutomation.onServiceState(settled = true)
            idle()
        }
    }

    // Android starts its always-on VPN with the plain service action, and with lockdown nothing reaches
    // the network until that start runs. A VPN whose owner Android hides, such as one of another user or
    // a work profile, must not hold it back, whether automation is on or off.
    @Test
    fun androidsAlwaysOnStartRunsBesideAVpnOfUnknownOwner() {
        ShadowVpnService.setPrepareResult(null)
        val other = vpnNetwork(130)
        try {
            for (enabled in listOf(true, false)) {
                automation(enabled = enabled, paused = false, connectOnMobile)
                withVpnService { service ->
                    val start = Intent(android.net.VpnService.SERVICE_INTERFACE).setPackage(app.packageName)
                    service.onStartCommand(start, 0, 1)
                    assertEquals(BaseService.State.Connecting, service.data.state)
                    assertNull(NetworkAutomation.blocked)
                    assertNull(shadowOf(app).nextStartedActivity)
                }
            }
        } finally {
            manager.removeNetwork(other)
        }
    }

    // Android starting its always-on VPN takes nothing over. Rule and boot starts hold back for every VPN
    // Android reports, including those whose owner it hides.
    @Test
    fun ruleAndBootStartsHoldBackForEveryReportedVpn() = withVpnService { service ->
        val ruleStart = Intent().putExtra(Action.EXTRA_AUTOMATED, true)
        val alwaysOn = Intent(android.net.VpnService.SERVICE_INTERFACE)
        assertFalse(service.takesOverAnotherVpn(ruleStart))
        val other = vpnNetwork(131)
        try {
            assertTrue(service.takesOverAnotherVpn(ruleStart))
            assertTrue(service.takesOverAnotherVpn(Intent()))
            assertFalse(service.takesOverAnotherVpn(alwaysOn))
            activeVpn(owner = Process.INVALID_UID)
            assertFalse(service.takesOverAnotherVpn(alwaysOn))
        } finally {
            manager.removeNetwork(other)
        }
    }

    // A restart of the session, such as a reload, that closed its tunnel holds back only for another VPN
    // carrying this app's traffic. A VPN of another user or a work profile never ends the session, and a
    // kill switch kept the tunnel up, so there is nothing to take over.
    @Test
    fun aRestartHoldsBackOnlyForAnotherVpnCarryingThisApp() = withVpnService { service ->
        service.data.restarting = true
        val restart = Intent()
        val work = vpnNetwork(132)
        try {
            assertFalse(service.takesOverAnotherVpn(restart))
            activeVpn(owner = Process.INVALID_UID)
            assertTrue(service.takesOverAnotherVpn(restart))
            activeVpn(owner = Process.myUid())
            assertFalse(service.takesOverAnotherVpn(restart))
        } finally {
            manager.removeNetwork(work)
        }
    }

    // A start that ends at once, such as one that shows the consent screen, still answers the foreground
    // demand of startForegroundService, and takes the notice down at once: the app or the tile may keep
    // the service bound. Android refusing the type without the VPN permission changes neither.
    @Test
    fun answeringTheForegroundDemandLeavesNoNotice() = assertForegroundDemandLeavesNoNotice()

    @Test
    @Config(sdk = [23, 33])
    fun answeringTheForegroundDemandLeavesNoNoticeBeforeAndroid14() = assertForegroundDemandLeavesNoNotice()

    private fun assertForegroundDemandLeavesNoNotice() = withVpnService { service ->
        val notifications = app.getSystemService(NotificationManager::class.java)
        service.answerForegroundDemand()
        assertEquals(ServiceNotification.notificationId, shadowOf(service).lastForegroundNotificationId)
        assertTrue(shadowOf(service).isForegroundStopped)
        assertTrue(shadowOf(notifications).allNotifications.isEmpty())

        val refused = Robolectric.buildService(VpnService::class.java).create()
        try {
            shadowOf(refused.get()).setThrowInStartForeground(SecurityException("systemExempted needs the VPN permission"))
            refused.get().answerForegroundDemand()
            assertTrue(shadowOf(refused.get()).isForegroundStopped)
            assertTrue(shadowOf(notifications).allNotifications.isEmpty())
        } finally {
            refused.destroy()
        }
    }

    // Before Android 11 nothing tells whose VPN carries this app, so a restart goes ahead.
    @Test
    @Config(sdk = [28])
    fun beforeAndroid11ARestartCannotTellWhoseVpnCarriesThisApp() = withVpnService { service ->
        service.data.restarting = true
        manager.setActiveNetworkInfo(vpnInfo())
        manager.setNetworkCapabilities(SagerNet.connectivity.activeNetwork, capabilities(NetworkCapabilities.TRANSPORT_VPN))
        assertFalse(service.takesOverAnotherVpn(Intent()))
    }

    // ---- decisions around the service's own restarts, with the real state changes ----

    /** Like the VPN or proxy service up to the point where it would load the core. */
    class ServiceUnderTest :
        Service(),
        BaseService.Interface {
        override val data = BaseService.Data(this)
        override val tag = "ServiceUnderTest"
        override var wakeLock: PowerManager.WakeLock? = null
        override var upstreamInterfaceName: String? = null

        override fun onBind(intent: Intent) = super<BaseService.Interface>.onBind(intent)
        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        override fun acquireWakeLock() = Unit
        override fun createNotification(profileName: String): ServiceNotification = error("Stop before native initialization")
        override fun stopRunner(restart: Boolean, msg: String?) = Unit
    }

    private fun withServiceUnderTest(block: (ServiceUnderTest) -> Unit) {
        automation(enabled = true, paused = false, connectOnMobile, disconnectOnWifi)
        val controller = Robolectric.buildService(ServiceUnderTest::class.java).create()
        val service = controller.get()
        try {
            block(service)
        } finally {
            service.data.connectingJob?.cancel()
            if (service.data.closeReceiverRegistered) service.unregisterReceiver(service.data.receiver)
            service.data.binder.close()
            controller.destroy()
            NetworkAutomation.onNetwork(null)
            idle()
        }
    }

    @Test
    fun manualStartupHoldsTheAlreadyReportedNetworkNotTheNextOne() = withServiceUnderTest { service ->
        NetworkAutomation.onNetwork(mobileNetwork(140))
        idle()
        service.onStartCommand(Intent().putExtra(Action.EXTRA_AUTOMATED, false), 0, 1)
        NetworkAutomation.onNetwork(wifiNetwork(141))
        assertEquals(1, await { broadcasts(Action.CLOSE, automated = true).takeIf { it > 0 } })
        assertFalse(DataStore.automationPaused)
    }

    @Test
    fun aConnectedServiceClearsOnlyVpnRefusals() = withServiceUnderTest { service ->
        for (reason in Blocked.entries) {
            NetworkAutomation.blocked = reason
            service.data.changeState(BaseService.State.Connecting)
            assertEquals(reason, NetworkAutomation.blocked)
            service.data.changeState(BaseService.State.Connected)
            val cleared = reason == Blocked.VPN_PERMISSION || reason == Blocked.OTHER_VPN
            assertEquals(if (cleared) null else reason, NetworkAutomation.blocked)
        }
    }

    // A restart's teardown ends in Stopped, and its new start only reaches onStartCommand later. A
    // trusted network reported in that interval is decided once the restarted service runs.
    @Test
    fun aRestartKeepsADisconnectReportedBeforeItsNewStart() = withServiceUnderTest { service ->
        val data = service.data
        data.changeState(BaseService.State.Connecting)
        data.changeState(BaseService.State.Connected)
        NetworkAutomation.onNetwork(mobileNetwork(120))
        idle()

        // The order stopRunner(restart = true) follows.
        data.stopGate.onStopRequested(restart = true, hold = false, alreadyStopping = false)
        data.changeState(BaseService.State.Stopping)
        val wifi = wifiNetwork(121)
        NetworkAutomation.onNetwork(wifi)
        idle()
        data.restarting = data.stopGate.consumeRestart()
        data.changeState(BaseService.State.Stopped)
        idle()
        NetworkAutomation.onNetwork(wifi)
        idle()
        // The gap does not depend on the stop gate still holding the restart.
        ServiceStopGate::class.java.getDeclaredField("pendingRestart").apply { isAccessible = true }.setBoolean(data.stopGate, false)
        NetworkAutomation.onNetwork(wifi)
        await(300) { null }
        assertEquals(0, broadcasts(Action.CLOSE, automated = true))
        assertNull(shadowOf(app).nextStartedService)

        // The restart's own start carries no extra, like the intent startRunner sends.
        service.onStartCommand(Intent(), 0, 2)
        assertEquals(1, await { broadcasts(Action.CLOSE, automated = true).takeIf { it > 0 } })
    }

    @Test
    fun aKillSwitchBlockDecidesTheNetworkReportedDuringItsTeardown() = withServiceUnderTest { service ->
        val data = service.data
        data.changeState(BaseService.State.Connecting)
        data.changeState(BaseService.State.Connected)
        NetworkAutomation.onNetwork(mobileNetwork(122))
        idle()

        // A failure with the kill switch on: the teardown keeps the tunnel and ends in Connecting.
        data.stopGate.onStopRequested(restart = false, hold = true, alreadyStopping = false)
        data.changeState(BaseService.State.Stopping)
        NetworkAutomation.onNetwork(wifiNetwork(123))
        await(300) { null }
        assertEquals(0, broadcasts(Action.CLOSE, automated = true))
        data.changeState(BaseService.State.Connecting, "failed")
        assertEquals(1, await { broadcasts(Action.CLOSE, automated = true).takeIf { it > 0 } })

        // The stop it asked for decides nothing more on the way down.
        data.stopGate.onStopRequested(restart = false, hold = false, alreadyStopping = false)
        data.changeState(BaseService.State.Stopping)
        data.restarting = data.stopGate.consumeRestart()
        data.changeState(BaseService.State.Stopped)
        await(300) { null }
        assertEquals(1, broadcasts(Action.CLOSE, automated = true))
        assertNull(shadowOf(app).nextStartedService)
    }
}
