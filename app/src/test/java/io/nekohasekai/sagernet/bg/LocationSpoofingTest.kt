package io.nekohasekai.sagernet.bg

import android.Manifest
import android.app.AppOpsManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager.FUSED_PROVIDER
import android.location.LocationManager.GPS_PROVIDER
import android.location.LocationManager.NETWORK_PROVIDER
import android.os.PowerManager
import android.provider.Settings
import android.util.AtomicFile
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.ConfigBuilderTestEnv
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val THIS_APP = "this app"
private const val OTHER_APP = "another simulator"
private const val OWN_PACKAGE = "org.example.this"
private const val OTHER_PACKAGE = "org.example.simulator"

// Test providers the way current Android keeps them: one per name, from whichever app added it
// last. Adding replaces a provider of the same name without an error.
private class FakeAccess(context: Context) : MockLocationAccess(context) {
    var failRemove: String? = null
    var revokeAfterRemoving: String? = null

    // Android 10 and earlier throw when adding an existing name or removing an unknown one.
    var legacy = false
    var pushes = 0
    val installed = mutableMapOf<String, String>()
    val locations = mutableMapOf<String, Location>()
    val watchers = mutableListOf<(String?, String?) -> Unit>()
    val everWatched = mutableListOf<(String?, String?) -> Unit>()

    var allowed = true
        set(value) {
            if (field == value) return
            field = value
            opChanged(AppOpsManager.OPSTR_MOCK_LOCATION, OWN_PACKAGE)
        }

    // What Android reports to the watch for an app op change.
    fun opChanged(op: String?, packageName: String?) = watchers.toList().forEach { it(op, packageName) }

    fun replaceByAnotherSimulator(name: String) {
        installed[name] = OTHER_APP
        locations.remove(name)
    }

    override fun allowed() = allowed

    override fun watch(changed: (op: String?, packageName: String?) -> Unit): () -> Unit {
        watchers += changed
        everWatched += changed
        return { watchers -= changed }
    }

    override fun add(provider: String) {
        // Android ignores these calls from an app that is not the mock location app.
        if (!allowed) return
        require(!legacy || provider !in installed) { "provider already exists" }
        installed[provider] = THIS_APP
        locations.remove(provider)
    }

    override fun enable(provider: String) = Unit

    override fun set(provider: String, location: Location) {
        if (!allowed) return
        require(provider in installed) { "provider doesn't exist" }
        locations[provider] = location
        pushes++
    }

    override fun remove(provider: String) {
        check(provider != failRemove) { "removal failed" }
        if (!allowed) return
        require(!legacy || provider in installed) { "unknown provider" }
        installed.remove(provider)
        locations.remove(provider)
        if (provider == revokeAfterRemoving) allowed = false
    }
}

private fun noticeOf(context: Context): Notification? = shadowOf(context.getSystemService(NotificationManager::class.java)).getNotification("location-spoofing", 0)

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class LocationSpoofingTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()
    private val all = setOf(GPS_PROVIDER, NETWORK_PROVIDER, FUSED_PROVIDER)
    private val access by lazy { FakeAccess(context) }

    private val logSink = Logs.sink

    @Before
    fun setUp() {
        Logs.sink = {}
        ConfigBuilderTestEnv.reset()
        DataStore.baseService = null
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        recordFile().delete()
        context.getSystemService(NotificationManager::class.java).cancelAll()
    }

    @After
    fun restoreLogs() {
        Logs.sink = logSink
    }

    private fun recordFile() = File(context.noBackupFilesDir, "mock-location-providers")

    private fun TestScope.spoofing(record: AtomicFile = AtomicFile(recordFile())) = LocationSpoofing(context, access, backgroundScope, { testScheduler.currentTime }, record)

    private fun TestScope.started(): LocationSpoofing = spoofing().also {
        it.start("1,1", null, { "" }) { error("unused") }
        runCurrent()
        assertEquals(all.associateWith { THIS_APP }, access.installed)
    }

    private fun latitude() = access.locations.getValue(GPS_PROVIDER).latitude

    private fun notice() = noticeOf(context)

    @Suppress("DEPRECATION") // The platform's PendingIntent type checks need API 31.
    private fun assertNotice(title: Int) {
        val notice = checkNotNull(notice())
        assertEquals(context.getString(title), notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val ongoing = title != R.string.gps_spoofing_failed && title != R.string.gps_spoofing_unsupported_title
        assertEquals(ongoing, notice.flags and Notification.FLAG_ONGOING_EVENT != 0)
        if (!ongoing) {
            assertNull(notice.actions)
            assertNull(notice.deleteIntent)
            return
        }
        // A dismissal reaches the explicit turn-off receiver.
        val deleteIntent = checkNotNull(notice.deleteIntent)
        assertTrue(shadowOf(deleteIntent).isBroadcastIntent)
        assertEquals(ComponentName(context, LocationTurnOffReceiver::class.java), shadowOf(deleteIntent).savedIntent.component)
        if (title == R.string.gps_spoofing_stuck) {
            // Removal waits for confirmation in the app, which the notice opens.
            assertNull(notice.actions)
            assertTrue(shadowOf(notice.contentIntent).isActivityIntent)
        } else {
            val action = notice.actions.single()
            assertEquals(context.getString(R.string.gps_spoofing_turn_off), action.title.toString())
            assertEquals(deleteIntent, action.actionIntent)
        }
    }

    // SystemUI removes a dismissed notice, then sends its delete intent.
    private fun dismiss() {
        val deleteIntent = checkNotNull(checkNotNull(notice()).deleteIntent)
        context.getSystemService(NotificationManager::class.java).cancel("location-spoofing", 0)
        LocationTurnOffReceiver().onReceive(context, shadowOf(deleteIntent).savedIntent)
    }

    // What Settings sends once the user confirmed removal.
    private fun confirmRemoval() = LocationTurnOffReceiver().onReceive(context, LocationSpoofing.confirmedRemoval(context))

    // Providers an earlier process of this boot added before it died.
    private fun leftBehind(vararg names: String) {
        for (name in names) access.installed[name] = THIS_APP
        recordFile().writeText((listOf("boot 7") + names).joinToString("\n"))
    }

    private fun hideBootCount() {
        Settings.Global.putString(context.contentResolver, Settings.Global.BOOT_COUNT, null)
        assertFalse(LocationSpoofing.supported(context))
    }

    private fun TestScope.withRunningService(block: (RunningService, LocationSpoofing) -> Unit) {
        val service = Robolectric.buildService(RunningService::class.java).create().get()
        try {
            val spoofing = spoofing()
            service.data.locationSpoofing = spoofing
            DataStore.baseService = service
            block(service, spoofing)
        } finally {
            DataStore.baseService = null
            service.data.binder.close()
        }
    }

    @Test
    fun coordinatesAreDecimalPairsInRangeAndBlankMeansAutomatic() {
        assertNull(parseMockCoordinates(" "))
        assertEquals(MockCoordinates(0.0, 0.0), parseMockCoordinates("0,0"))
        assertEquals(MockCoordinates(-90.0, 180.0), parseMockCoordinates(" -90, 180 "))
        assertEquals(MockCoordinates(0.0000001, 52.52), parseMockCoordinates("0.0000001,52.52"))
        for (value in listOf("0", "0,0,0", "91,0", "0,181", "NaN,0", "0,Infinity", "1e1,0", "0x1p3,0", "1d,0", "+1,0", "text,0")) {
            assertThrows(IllegalArgumentException::class.java) { parseMockCoordinates(value) }
        }
    }

    @Test
    fun coordinatesSkipTheLookupAndStopRemovesEveryProvider() = runTest {
        val spoofing = spoofing()
        var lookups = 0
        spoofing.start("52.52, 13.405", "proxy", { "a" }) {
            lookups++
            "0,0"
        }
        runCurrent()
        assertEquals(all, access.installed.keys)
        assertEquals(52.52, latitude(), 0.0)
        assertNotice(R.string.gps_spoofing_active)
        advanceTimeBy(10_000)
        assertEquals(0, lookups)
        assertTrue(access.pushes > all.size)

        assertTrue(spoofing.stop())
        assertTrue(access.installed.isEmpty())
        assertNull(notice())
        assertFalse(recordFile().exists())
        assertFalse(LocationSpoofing.mayRemain(context))
        assertTrue(access.watchers.isEmpty())
    }

    @Test
    fun aResultForAnEarlierSelectionIsDropped() = runTest {
        val spoofing = spoofing()
        var selection = "a"
        val tags = mutableListOf<String>()
        val results = mutableListOf<CompletableDeferred<String>>()
        spoofing.start("", "proxy", { selection }) { tag ->
            tags += tag
            CompletableDeferred<String>().also { results += it }.await()
        }
        runCurrent()
        selection = "b"
        results[0].complete("10,10")
        runCurrent()
        assertTrue(access.installed.isEmpty())
        assertEquals(listOf("a", "b"), tags)

        results[1].complete("20,20")
        runCurrent()
        assertEquals(20.0, latitude(), 0.0)
        assertNotice(R.string.gps_spoofing_active)
        spoofing.stop()
    }

    @Test
    fun aServerSwitchShowsUpdatingAndPushesNothingUntilTheNewLocationArrives() = runTest {
        val spoofing = spoofing()
        var selection = "a"
        val results = mutableMapOf<String, CompletableDeferred<String>>()
        spoofing.start("", "proxy", { selection }) { tag -> results.getOrPut(tag) { CompletableDeferred() }.await() }
        runCurrent()
        results.getValue("a").complete("10,10")
        runCurrent()
        assertNotice(R.string.gps_spoofing_active)

        selection = "b"
        advanceTimeBy(2_001)
        val pushes = access.pushes
        advanceTimeBy(8_000)
        assertNotice(R.string.gps_spoofing_updating)
        assertEquals(pushes, access.pushes)
        assertEquals(10.0, latitude(), 0.0)

        results.getValue("b").complete("20,20")
        runCurrent()
        assertEquals(20.0, latitude(), 0.0)
        assertNotice(R.string.gps_spoofing_active)
        spoofing.stop()
    }

    @Test
    fun stoppingDuringALookupNeverAppliesItsResult() = runTest {
        val spoofing = spoofing()
        val tags = mutableListOf<String>()
        val result = CompletableDeferred<String>()
        // An empty selection means a plain profile or an undecided group: look up through the base tag.
        spoofing.start("", "proxy", { "" }) { tag ->
            tags += tag
            result.await()
        }
        runCurrent()
        spoofing.stop()
        result.complete("10,10")
        advanceTimeBy(10_000)
        assertEquals(listOf("proxy"), tags)
        assertTrue(access.installed.isEmpty())
        assertNull(notice())
    }

    @Test
    fun failedLookupsRetryTwiceThenStopWithoutClaimingSpoofing() = runTest {
        var attempts = 0
        spoofing().start("", "proxy", { "a" }) {
            attempts++
            error("unreachable")
        }
        runCurrent()
        advanceTimeBy(5_001)
        assertEquals(2, attempts)
        assertNull(notice())

        advanceTimeBy(5_001)
        assertEquals(3, attempts)
        assertTrue(access.installed.isEmpty())
        assertNotice(R.string.gps_spoofing_failed)
        advanceTimeBy(600_000)
        assertEquals(3, attempts)
    }

    @Test
    fun theExitIsLookedUpAgainEveryFiveMinutesAndAFailedRefreshKeepsItsLocation() = runTest {
        val results = ArrayDeque(listOf(Result.success("10,10"), Result.failure(IllegalStateException()), Result.success("30,30")))
        var lookups = 0
        val spoofing = spoofing()
        spoofing.start("", "proxy", { "a" }) {
            lookups++
            results.removeFirst().getOrThrow()
        }
        runCurrent()
        advanceTimeBy(299_000)
        assertEquals(1, lookups)

        advanceTimeBy(2_000)
        assertEquals(2, lookups)
        assertEquals(10.0, latitude(), 0.0)
        assertNotice(R.string.gps_spoofing_cached)

        advanceTimeBy(302_000)
        assertEquals(3, lookups)
        assertEquals(30.0, latitude(), 0.0)
        assertNotice(R.string.gps_spoofing_active)
        spoofing.stop()
    }

    @Test
    fun aFailedLookupAfterAServerSwitchRemovesThePreviousExitLocation() = runTest {
        var selection = "a"
        val spoofing = spoofing()
        spoofing.start("", "proxy", { selection }) { tag -> if (tag == "a") "10,10" else error("unreachable") }
        runCurrent()
        assertEquals(10.0, latitude(), 0.0)

        selection = "b"
        advanceTimeBy(2_001)
        val pushes = access.pushes
        advanceTimeBy(10_001)
        assertEquals(pushes, access.pushes)
        assertTrue(access.installed.isEmpty())
        assertNotice(R.string.gps_spoofing_failed)
    }

    @Test
    fun withoutTheMockLocationAppOpNothingStarts() = runTest {
        access.allowed = false
        var lookups = 0
        spoofing().start("", "proxy", { "a" }) {
            lookups++
            "1,1"
        }
        runCurrent()
        assertEquals(0, lookups)
        assertTrue(access.installed.isEmpty())
        assertNotice(R.string.gps_spoofing_failed)
    }

    @Test
    fun losingNotificationsStopsSpoofingBecauseItsControlIsGone() = runTest {
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        val revocations = listOf(
            { manager.setNotificationsEnabled(false) } to { manager.setNotificationsEnabled(true) },
            { shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS) } to
                { shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS) },
        )
        for ((revoke, restore) in revocations) {
            started()
            revoke()
            advanceTimeBy(2_001)
            assertTrue(access.installed.isEmpty())
            assertFalse(recordFile().exists())
            restore()
        }
    }

    @Test
    fun withoutABootCountNothingStartsEvenWithCoordinates() = runTest {
        hideBootCount()
        spoofing().start("1,1", null, { "" }) { error("unused") }
        advanceTimeBy(10_000)
        assertTrue(access.installed.isEmpty())
        assertEquals(0, access.pushes)
        assertFalse(recordFile().exists())
        assertNotice(R.string.gps_spoofing_unsupported_title)
    }

    @Test
    fun providersFromAnEarlierProcessWaitForConfirmation() = runTest {
        leftBehind(*all.toTypedArray())
        val spoofing = spoofing()
        assertFalse(spoofing.stop())
        assertNotice(R.string.gps_spoofing_stuck)
        assertTrue(LocationSpoofing.mayRemain(context))
        assertEquals(all.associateWith { THIS_APP }, access.installed)

        assertTrue(spoofing.removeConfirmed())
        assertTrue(access.installed.isEmpty())
        assertFalse(recordFile().exists())
        assertFalse(LocationSpoofing.mayRemain(context))
        assertNull(notice())
    }

    @Test
    fun aPendingRemovalBlocksAddingWhereAndroidWouldReplaceSilently() = runTest {
        leftBehind(GPS_PROVIDER)
        access.replaceByAnotherSimulator(GPS_PROVIDER)
        spoofing().start("1,1", null, { "" }) { error("unused") }
        advanceTimeBy(10_000)
        assertEquals(mapOf(GPS_PROVIDER to OTHER_APP), access.installed)
        assertEquals(0, access.pushes)
        assertNotice(R.string.gps_spoofing_stuck)
    }

    @Test
    fun aReplacedProviderIsLeftUntilRemovalIsConfirmed() = runTest {
        val spoofing = started()
        // Another simulator takes over a name while this app is not the mock location app.
        access.allowed = false
        advanceTimeBy(2_001)
        assertNotice(R.string.gps_spoofing_stuck)
        access.replaceByAnotherSimulator(GPS_PROVIDER)
        access.allowed = true

        assertFalse(spoofing.stop())
        assertEquals(OTHER_APP, access.installed[GPS_PROVIDER])
        assertEquals(all, access.installed.keys)
        assertNotice(R.string.gps_spoofing_stuck)

        assertTrue(spoofing.removeConfirmed())
        assertTrue(access.installed.isEmpty())
    }

    @Test
    fun aQuickMockLocationAppChangeIsCaughtByTheWatch() = runTest {
        val spoofing = started()
        val pushes = access.pushes
        // Deselected and selected again between two checks of the app op.
        access.allowed = false
        access.allowed = true
        advanceTimeBy(10_000)
        assertEquals(pushes, access.pushes)
        assertEquals(all, access.installed.keys)
        assertNotice(R.string.gps_spoofing_stuck)
        assertTrue(access.watchers.isEmpty())

        assertFalse(spoofing.stop())
        assertTrue(spoofing.removeConfirmed())
        assertTrue(access.installed.isEmpty())
    }

    @Test
    fun aLateCallbackOfAnEndedWatchCannotAffectALaterRun() = runTest {
        val spoofing = started()
        assertTrue(spoofing.stop())
        spoofing.start("1,1", null, { "" }) { error("unused") }
        runCurrent()
        assertEquals(2, access.everWatched.size)
        // A callback of the first watch arrives late.
        access.everWatched.first()(AppOpsManager.OPSTR_MOCK_LOCATION, OWN_PACKAGE)
        val pushes = access.pushes
        advanceTimeBy(4_001)
        assertTrue(access.pushes > pushes)
        assertNotice(R.string.gps_spoofing_active)
        assertTrue(spoofing.stop())
        assertTrue(access.installed.isEmpty())
    }

    @Test
    fun aProviderThatCouldNotBeRemovedStaysOwnedUntilARetrySucceeds() = runTest {
        val spoofing = started()
        access.failRemove = GPS_PROVIDER
        assertFalse(spoofing.stop())
        assertEquals(setOf(GPS_PROVIDER), access.installed.keys)
        assertEquals("boot 7\n$GPS_PROVIDER", recordFile().readText())
        assertNotice(R.string.gps_spoofing_stuck)

        // The process keeps the name too, so losing the record does not lose it.
        recordFile().delete()
        access.failRemove = null
        assertTrue(spoofing.stop())
        assertTrue(access.installed.isEmpty())
        assertNull(notice())
    }

    @Test
    fun aMockLocationAppChangeDuringCleanupLeavesEveryNameForConfirmation() = runTest {
        val spoofing = started()
        // Another app becomes the mock location app after the first removal; Android ignores the rest.
        access.revokeAfterRemoving = GPS_PROVIDER
        assertFalse(spoofing.stop())
        assertEquals(all - GPS_PROVIDER, access.installed.keys)
        assertEquals((listOf("boot 7") + all).joinToString("\n"), recordFile().readText())
        assertNotice(R.string.gps_spoofing_stuck)

        // Selected again, the process no longer removes them on its own.
        access.revokeAfterRemoving = null
        access.allowed = true
        assertFalse(spoofing.stop())
        assertEquals(all - GPS_PROVIDER, access.installed.keys)
        assertTrue(spoofing.removeConfirmed())
        assertTrue(access.installed.isEmpty())
        assertNull(notice())
    }

    @Test
    fun aMissingBootCountKeepsTheRecordForConfirmation() = runTest {
        leftBehind(*all.toTypedArray())
        hideBootCount()
        // A missing count does not prove a restart, so the record stays.
        val spoofing = spoofing()
        assertFalse(spoofing.stop())
        spoofing.start("1,1", null, { "" }) { error("unused") }
        advanceTimeBy(10_000)
        assertEquals(all, access.installed.keys)
        assertEquals(0, access.pushes)
        assertTrue(recordFile().exists())
        assertTrue(LocationSpoofing.mayRemain(context))
        assertNotice(R.string.gps_spoofing_stuck)

        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 7)
        assertFalse(spoofing.stop())
        assertTrue(spoofing.removeConfirmed())
        assertTrue(access.installed.isEmpty())
        assertFalse(recordFile().exists())
    }

    @Test
    fun anUnreadableRecordIsKeptForConfirmation() = runTest {
        access.installed[GPS_PROVIDER] = THIS_APP
        // Reading a directory fails like an unreadable file.
        assertTrue(recordFile().mkdirs())
        val spoofing = spoofing()
        assertTrue(LocationSpoofing.mayRemain(context))
        assertFalse(spoofing.stop())
        spoofing.start("1,1", null, { "" }) { error("unused") }
        advanceTimeBy(10_000)
        assertEquals(setOf(GPS_PROVIDER), access.installed.keys)
        assertEquals(0, access.pushes)
        assertTrue(recordFile().isDirectory)
        assertNotice(R.string.gps_spoofing_stuck)

        assertTrue(spoofing.removeConfirmed())
        assertTrue(access.installed.isEmpty())
        assertFalse(recordFile().exists())
    }

    @Test
    fun aRecordNotExactlyInTheStoredFormStaysForConfirmation() = runTest {
        val malformed = listOf(
            "", "boot", "garbage", "boot x\n$GPS_PROVIDER", "boot -1\n$GPS_PROVIDER", "boot 07\n$GPS_PROVIDER",
            "boot 7 \n$GPS_PROVIDER", "boot 7\r\n$GPS_PROVIDER",
            // Incomplete: a stored record always names a provider, and only these.
            "boot 7", "boot 7\ngp", "boot 7\n$GPS_PROVIDER\n", "boot 7\n$GPS_PROVIDER\n$GPS_PROVIDER", "boot 7\nsomething-else",
            // A wrong body casts doubt on the boot count too.
            "boot 6", "boot 6\nsomething-else",
        )
        for (content in malformed) {
            recordFile().writeText(content)
            val spoofing = spoofing()
            assertTrue(content, LocationSpoofing.mayRemain(context))
            assertFalse(content, spoofing.stop())
            spoofing.start("1,1", null, { "" }) { error("unused") }
            advanceTimeBy(10_000)
            assertTrue(content, access.installed.isEmpty())
            assertEquals(content, 0, access.pushes)
            assertEquals(content, recordFile().readText())
            assertNotice(R.string.gps_spoofing_stuck)
        }
        assertTrue(spoofing().removeConfirmed())
        assertFalse(recordFile().exists())
        assertFalse(LocationSpoofing.mayRemain(context))
    }

    @Test
    fun theSettingsQueryChangesNoFile() = runTest {
        // A record of another boot names nothing that remains; only the background process cleans it up.
        recordFile().writeText("boot 6\n$GPS_PROVIDER")
        assertFalse(LocationSpoofing.mayRemain(context))
        assertEquals("boot 6\n$GPS_PROVIDER", recordFile().readText())
        assertTrue(spoofing().stop())
        assertFalse(recordFile().exists())

        // Android 10 and earlier keep the previous version as ".bak" until a write finishes, and
        // AtomicFile would move it back or delete an unfinished ".new". The query reads in place.
        val backup = File(recordFile().path + ".bak")
        val unfinished = File(recordFile().path + ".new")
        backup.writeText("boot 7\n$GPS_PROVIDER")
        assertTrue(LocationSpoofing.mayRemain(context))
        recordFile().writeText("boot 7\nnet")
        unfinished.writeText("boot 7\n$NETWORK_PROVIDER")
        assertTrue(LocationSpoofing.mayRemain(context))
        assertEquals("boot 7\n$GPS_PROVIDER", backup.readText())
        assertEquals("boot 7\nnet", recordFile().readText())
        assertEquals("boot 7\n$NETWORK_PROVIDER", unfinished.readText())

        // A backup that cannot be read leaves the state unknown, even without a base file.
        backup.delete()
        recordFile().delete()
        assertTrue(backup.mkdirs())
        assertTrue(LocationSpoofing.mayRemain(context))
        assertTrue(backup.isDirectory)
        assertTrue(unfinished.exists())

        assertTrue(spoofing().removeConfirmed())
        assertFalse(backup.exists())
        assertFalse(recordFile().exists())
        assertFalse(unfinished.exists())
    }

    @Test
    fun aBackupLeftAfterDeletionKeepsCleanupIncomplete() = runTest {
        // A backup of another boot reads as empty, so only its absence shows that deletion worked.
        val backup = File(recordFile().path + ".bak")
        backup.writeText("boot 6\n$GPS_PROVIDER")
        val keepsBackup = object : AtomicFile(recordFile()) {
            override fun delete() {
                baseFile.delete()
            }
        }
        assertFalse(LocationSpoofing.mayRemain(context))
        assertFalse(spoofing(keepsBackup).stop())
        assertTrue(backup.exists())
        assertNotice(R.string.gps_spoofing_stuck)

        assertTrue(spoofing().stop())
        assertFalse(backup.exists())
        assertNull(notice())
    }

    @Test
    fun onlyAChangeThatMayConcernMockLocationsEndsTheContinuity() = runTest {
        // The API 35 probe ignored another op; an explicitly unrelated callback is ignored too.
        var spoofing = started()
        access.opChanged(AppOpsManager.OPSTR_CAMERA, OWN_PACKAGE)
        val pushes = access.pushes
        advanceTimeBy(4_001)
        assertTrue(access.pushes > pushes)
        assertNotice(R.string.gps_spoofing_active)
        assertTrue(spoofing.stop())
        assertTrue(access.installed.isEmpty())

        // Any app allowed to mock locations can replace a provider; a missing argument counts too.
        val relevant = listOf(
            AppOpsManager.OPSTR_MOCK_LOCATION to OWN_PACKAGE,
            AppOpsManager.OPSTR_MOCK_LOCATION to OTHER_PACKAGE,
            AppOpsManager.OPSTR_MOCK_LOCATION to null,
            null to OWN_PACKAGE,
            null to null,
        )
        for ((op, packageName) in relevant) {
            spoofing = started()
            access.opChanged(op, packageName)
            advanceTimeBy(2_001)
            assertNotice(R.string.gps_spoofing_stuck)
            assertFalse("$op $packageName", spoofing.stop())
            assertEquals(all, access.installed.keys)
            assertTrue(spoofing.removeConfirmed())
            assertTrue(access.installed.isEmpty())
        }
    }

    @Test
    fun confirmedRemovalTouchesOnlyTheStandardProviders() = runTest {
        recordFile().writeText("boot 7\n$GPS_PROVIDER\nsomething-else")
        access.installed[GPS_PROVIDER] = THIS_APP
        access.installed["something-else"] = OTHER_APP
        val spoofing = spoofing()
        assertFalse(spoofing.stop())
        assertTrue(spoofing.removeConfirmed())
        assertEquals(mapOf("something-else" to OTHER_APP), access.installed)
        assertFalse(recordFile().exists())
    }

    @Test
    fun confirmedRemovalNeedsTheMockLocationAppOpAndChecksItAgain() = runTest {
        leftBehind(*all.toTypedArray())
        val spoofing = spoofing()
        // The app op changed between the prompt and the removal.
        access.allowed = false
        assertFalse(spoofing.removeConfirmed())
        assertEquals(all, access.installed.keys)
        assertTrue(recordFile().exists())
        assertNotice(R.string.gps_spoofing_stuck)

        access.allowed = true
        access.revokeAfterRemoving = GPS_PROVIDER
        assertFalse(spoofing.removeConfirmed())
        assertTrue(recordFile().exists())

        access.revokeAfterRemoving = null
        access.allowed = true
        assertTrue(spoofing.removeConfirmed())
        assertTrue(access.installed.isEmpty())
        assertFalse(recordFile().exists())
        assertNull(notice())
    }

    @Test
    fun aRecordThatCannotBeStoredAddsNothing() = runTest {
        val lostWrite = object : AtomicFile(recordFile()) {
            // The rename after writing fails without an error.
            override fun finishWrite(str: FileOutputStream?) = failWrite(str)
        }
        val failedWrite = object : AtomicFile(recordFile()) {
            override fun startWrite(): FileOutputStream = throw IOException("no space left on device")
        }
        for (record in listOf(lostWrite, failedWrite)) {
            spoofing(record).start("1,1", null, { "" }) { error("unused") }
            runCurrent()
            assertTrue(access.installed.isEmpty())
            assertEquals(0, access.pushes)
            assertFalse(recordFile().exists())
            assertNotice(R.string.gps_spoofing_failed)
        }
    }

    @Test
    fun aRecordThatCannotBeClearedWaitsForConfirmation() = runTest {
        val undeletable = object : AtomicFile(recordFile()) {
            override fun delete() = Unit
        }
        val spoofing = spoofing(undeletable)
        spoofing.start("1,1", null, { "" }) { error("unused") }
        runCurrent()
        assertFalse(spoofing.stop())
        assertTrue(access.installed.isEmpty())
        assertTrue(recordFile().exists())
        assertNotice(R.string.gps_spoofing_stuck)

        assertFalse(spoofing().stop())
        assertTrue(spoofing().removeConfirmed())
        assertFalse(recordFile().exists())
        assertNull(notice())
    }

    @Test
    fun aFailedAddStaysRecordedForConfirmation() = runTest {
        access.installed[NETWORK_PROVIDER] = OTHER_APP
        access.legacy = true
        spoofing().start("1,1", null, { "" }) { error("unused") }
        runCurrent()
        assertEquals(mapOf(NETWORK_PROVIDER to OTHER_APP), access.installed)
        assertEquals("boot 7\n$NETWORK_PROVIDER", recordFile().readText())
        assertNotice(R.string.gps_spoofing_stuck)

        assertTrue(spoofing().removeConfirmed())
        assertTrue(access.installed.isEmpty())
    }

    @Test
    fun aRecordedProviderAlreadyGoneOnAndroid10CountsAsRemoved() = runTest {
        recordFile().writeText("boot 7\n$GPS_PROVIDER")
        access.legacy = true
        assertTrue(spoofing().removeConfirmed())
        assertFalse(recordFile().exists())
    }

    @Test
    fun cleanupLeavesProvidersThisAppDidNotRecordInThisBoot() = runTest {
        access.installed[GPS_PROVIDER] = OTHER_APP
        assertTrue(spoofing().stop())
        assertEquals(setOf(GPS_PROVIDER), access.installed.keys)

        // Rebooting removed every provider an earlier boot recorded.
        recordFile().writeText("boot 6\n$NETWORK_PROVIDER")
        access.installed[NETWORK_PROVIDER] = OTHER_APP
        assertTrue(spoofing().stop())
        assertEquals(setOf(GPS_PROVIDER, NETWORK_PROVIDER), access.installed.keys)
        assertFalse(recordFile().exists())
    }

    @Test
    fun dismissingTheNoticeTurnsSpoofingOffAndLeavesTheVpnAlone() = runTest {
        withRunningService { service, spoofing ->
            DataStore.gpsSpoofing = true
            spoofing.start("1,1", null, { "" }) { error("unused") }
            runCurrent()
            assertNotice(R.string.gps_spoofing_active)

            dismiss()
            advanceTimeBy(10_000)
            assertFalse(DataStore.gpsSpoofing)
            assertTrue(access.installed.isEmpty())
            assertNull(notice())
            assertEquals(0, service.stops)
        }
    }

    @Test
    fun dismissingTheRecoveryNoticeShowsItAgainUntilRemovalIsConfirmed() = runTest {
        withRunningService { service, spoofing ->
            DataStore.gpsSpoofing = true
            spoofing.start("1,1", null, { "" }) { error("unused") }
            runCurrent()
            access.allowed = false
            advanceTimeBy(2_001)
            assertNotice(R.string.gps_spoofing_stuck)

            dismiss()
            assertFalse(DataStore.gpsSpoofing)
            assertEquals(all, access.installed.keys)
            assertNotice(R.string.gps_spoofing_stuck)

            // Selected again as the mock location app, a dismissal still removes nothing.
            access.allowed = true
            dismiss()
            assertEquals(all, access.installed.keys)
            assertNotice(R.string.gps_spoofing_stuck)

            confirmRemoval()
            val pushes = access.pushes
            advanceTimeBy(10_000)
            assertTrue(access.installed.isEmpty())
            assertFalse(recordFile().exists())
            assertNull(notice())
            assertFalse(DataStore.gpsSpoofing)
            assertEquals(pushes, access.pushes)
            assertEquals(0, service.stops)
        }
    }

    @Test
    fun aColdTurnOffSavesOffAndRemovesNothingUncertain() = runTest {
        leftBehind(*all.toTypedArray())
        DataStore.gpsSpoofing = true
        LocationTurnOffReceiver().onReceive(context, Intent(context, LocationTurnOffReceiver::class.java))
        assertFalse(DataStore.gpsSpoofing)
        assertEquals(all.associateWith { THIS_APP }, access.installed)
        assertTrue(recordFile().exists())
        assertNotice(R.string.gps_spoofing_stuck)
    }

    @Test
    fun turningOffClearsANoticeLeftByADeadProcess() = runTest {
        DataStore.gpsSpoofing = true
        spoofing().start("1,1", null, { "" }) { error("unused") }
        runCurrent()
        assertNotice(R.string.gps_spoofing_active)
        val turnOff = checkNotNull(notice()).actions.single().actionIntent
        access.installed.clear()
        recordFile().delete()

        LocationTurnOffReceiver().onReceive(context, shadowOf(turnOff).savedIntent)
        assertFalse(DataStore.gpsSpoofing)
        assertNull(notice())
    }

    class RunningService :
        Service(),
        BaseService.Interface {
        override val data = BaseService.Data(this)
        override val tag = "RunningService"
        override var wakeLock: PowerManager.WakeLock? = null
        override var upstreamInterfaceName: String? = null
        var stops = 0

        override fun onBind(intent: Intent) = super<BaseService.Interface>.onBind(intent)
        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = super<BaseService.Interface>.onStartCommand(intent, flags, startId)
        override fun acquireWakeLock() = Unit
        override fun createNotification(profileName: String): ServiceNotification = error("unused")
        override fun stopRunner(restart: Boolean, msg: String?) {
            stops++
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23], application = Application::class)
class LocationSpoofingApi23Test {
    @Test
    fun withoutABootCountNothingStartsEvenWithCoordinates() = runTest {
        val context = RuntimeEnvironment.getApplication()
        // Notices use the SagerNet context; attach it without native initialization.
        SagerNet::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
            invoke(SagerNet(), context)
        }
        val access = FakeAccess(context)
        assertFalse(LocationSpoofing.supported(context))
        LocationSpoofing(context, access, backgroundScope, { testScheduler.currentTime }).start("1,1", null, { "" }) { error("unused") }
        advanceTimeBy(10_000)
        assertTrue(access.installed.isEmpty())
        assertEquals(0, access.pushes)
        val notice = checkNotNull(noticeOf(context))
        assertEquals(context.getString(R.string.gps_spoofing_unsupported_title), notice.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }
}
