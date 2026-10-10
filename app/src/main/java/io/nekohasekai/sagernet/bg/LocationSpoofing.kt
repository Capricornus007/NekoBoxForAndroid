package io.nekohasekai.sagernet.bg

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AppOpsManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import android.util.AtomicFile
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libcore.Libcore
import java.io.File
import java.io.IOException
import java.util.Date

private const val PUSH_INTERVAL_MS = 2_000L
private const val REFRESH_INTERVAL_MS = 300_000L
private const val LOOKUP_ATTEMPTS = 3
private const val LOOKUP_RETRY_MS = 5_000L

// An exit IP locates a city at best; typed coordinates name a point.
private const val LOOKUP_ACCURACY_M = 10_000f
private const val MANUAL_ACCURACY_M = 20f

private const val RECORD_FILE = "mock-location-providers"
private const val NOTICE_CHANNEL = "location-spoofing"
private const val NOTICE_TAG = "location-spoofing"
private const val EXTRA_REMOVE_CONFIRMED = "removeConfirmed"

internal data class MockCoordinates(val latitude: Double, val longitude: Double)

private val coordinatePattern = Regex("""\s*(-?\d{1,3}(?:\.\d+)?)\s*,\s*(-?\d{1,3}(?:\.\d+)?)\s*""")

/** Blank selects the approximate exit location; anything else must be "latitude, longitude". */
internal fun parseMockCoordinates(value: String): MockCoordinates? {
    if (value.isBlank()) return null
    val match = requireNotNull(coordinatePattern.matchEntire(value))
    val latitude = match.groupValues[1].toDouble()
    val longitude = match.groupValues[2].toDouble()
    require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
    return MockCoordinates(latitude, longitude)
}

/** Test provider calls. Android accepts them only from the selected mock location app. */
internal open class MockLocationAccess(private val context: Context) {
    private val manager by lazy { context.getSystemService(LocationManager::class.java) }
    private val appOps by lazy { context.getSystemService(AppOpsManager::class.java) }

    open fun allowed() = LocationSpoofing.mockLocationAllowed(context)

    /**
     * Calls [changed] with the op and package Android reports for each change of the mock location
     * app op of any app, until the returned function runs. A watch on this app's package would also
     * report its other ops; the API 35 probe received those under the watched op's name.
     */
    open fun watch(changed: (op: String?, packageName: String?) -> Unit): () -> Unit {
        val listener = AppOpsManager.OnOpChangedListener { op, packageName -> changed(op, packageName) }
        appOps.startWatchingMode(AppOpsManager.OPSTR_MOCK_LOCATION, null, listener)
        return { appOps.stopWatchingMode(listener) }
    }

    // The ProviderProperties overload needs API 31. Its int constants compile into this call,
    // so they work on older releases too.
    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    open fun add(provider: String) = manager.addTestProvider(
        provider, false, false, false, false, false, false, false,
        ProviderProperties.POWER_USAGE_LOW, ProviderProperties.ACCURACY_FINE,
    )

    open fun enable(provider: String) = manager.setTestProviderEnabled(provider, true)

    open fun set(provider: String, location: Location) = manager.setTestProviderLocation(provider, location)

    open fun remove(provider: String) = manager.removeTestProvider(provider)
}

/**
 * Feeds every app a simulated location while the VPN is connected: the approximate location of
 * the exit IP, looked up through the selected outbound, or fixed coordinates. Owned by the VPN
 * service; every method runs on the main thread.
 *
 * Android does not tell which app set a test provider, and while this app is not the mock
 * location app another simulator can replace one under the same name. As a best-effort
 * continuity check, not a guarantee, a process removes on its own only the providers it added
 * and kept while it stayed the mock location app. Every other recorded name waits until the user
 * confirms removal in the app.
 */
class LocationSpoofing internal constructor(
    private val context: Context,
    private val access: MockLocationAccess,
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    // Names this app added in the current boot, written before each add. A restart removes every
    // test provider, which makes a record of another boot void. A record never shows that a
    // provider still comes from this app.
    private val record: AtomicFile = AtomicFile(File(context.noBackupFilesDir, RECORD_FILE)),
) {
    constructor(context: Context) : this(
        context,
        MockLocationAccess(context),
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        SystemClock::elapsedRealtime,
    )

    // Providers this process added and kept while it stayed the mock location app.
    private val added = mutableSetOf<String>()

    // App op watches are numbered so that a late callback of an ended watch cannot count.
    private val watchLock = Any()
    private var watch = 0
    private var changedDuring = -1
    private var unwatch: (() -> Unit)? = null
    private val appOpChanged get() = synchronized(watchLock) { changedDuring == watch }
    private var job: Job? = null
    private var shown: Notice? = null

    /** Never throws: spoofing failures must not affect the VPN. */
    fun start(proxy: ProxyInstance) {
        if (!DataStore.gpsSpoofing || DataStore.serviceMode != Key.MODE_VPN) {
            stop()
            return
        }
        val url = DataStore.gpsLookupUrl
        val baseTag = runCatching {
            if (proxy.config.selectorGroupId >= 0) TAG_PROXY else proxy.config.profileTagMap[proxy.profile.id]
        }.getOrNull()
        start(DataStore.gpsCoordinates, baseTag, { proxy.box.selectedOutbound() }) { tag ->
            withContext(Dispatchers.IO) { Libcore.lookupOutboundLocation(proxy.box, tag, url) }
        }
    }

    /**
     * Looks up through the member [selected] reports, or [baseTag] when it reports none, so a
     * result always belongs to one exit. A result is dropped if the selection moved meanwhile.
     */
    internal fun start(coordinates: String, baseTag: String?, selected: () -> String, lookup: suspend (String) -> String) {
        // Nothing is added while an earlier simulated location may remain: current Android
        // replaces a test provider of the same name without an error.
        if (!stop()) return
        if (!supported(context)) {
            showNotice(Notice.UNSUPPORTED)
            return
        }
        job = scope.launch {
            try {
                val manual = parseMockCoordinates(coordinates)
                check(manual != null || baseTag != null)
                var current = manual
                var selection = ""
                var refreshAt = 0L
                var failures = 0
                var status = Notice.ACTIVE
                var checkedAt = ""
                while (true) {
                    check(!appOpChanged && access.allowed() && notificationsAllowed(context))
                    val now = if (manual == null) selected() else selection
                    val changed = current == null || now != selection
                    if (changed || (manual == null && clock() >= refreshAt && !deviceIdle())) {
                        if (current != null && changed) showNotice(Notice.UPDATING)
                        val result = try {
                            lookup(now.ifEmpty { checkNotNull(baseTag) })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // The exit is unchanged: keep its location and tell when it was checked.
                            if (!changed) {
                                refreshAt = clock() + REFRESH_INTERVAL_MS
                                status = Notice.CACHED
                                continue
                            }
                            if (++failures >= LOOKUP_ATTEMPTS) throw e
                            delay(LOOKUP_RETRY_MS)
                            continue
                        }
                        if (selected() != now) continue
                        failures = 0
                        current = parseMockCoordinates(result)
                        selection = now
                        refreshAt = clock() + REFRESH_INTERVAL_MS
                        checkedAt = DateFormat.getTimeFormat(context).format(Date())
                        status = Notice.ACTIVE
                    }
                    push(checkNotNull(current), if (manual != null) MANUAL_ACCURACY_M else LOOKUP_ACCURACY_M)
                    showNotice(status, checkedAt)
                    delay(PUSH_INTERVAL_MS)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // These messages never carry coordinates or the response.
                Logs.w("GPS spoofing stopped", e)
                // Keep the configured preference; the notice reports stopped injection until recovery.
                showNotice(if (release()) Notice.STOPPED else Notice.STUCK)
            }
        }
    }

    /**
     * Stops injecting and removes the providers this process kept. Returns false, with a notice
     * that leads to removal in the app, while a simulated location from this app may remain.
     */
    fun stop(): Boolean {
        job?.cancel()
        job = null
        return report(release())
    }

    /**
     * Removes the standard providers, which may belong to another simulator, once the user
     * confirmed it in the app, then drops the record. Only these names are ever removed, whatever
     * the record holds. Returns false while any may remain.
     */
    fun removeConfirmed(): Boolean {
        job?.cancel()
        job = null
        forget()
        val removed = access.allowed() &&
            standardProviders.map(::remove).all { it } &&
            // Android ignores removals without an error once the app op is revoked.
            access.allowed() &&
            runCatching { writeRecord(emptySet()) }.isSuccess
        return report(removed)
    }

    private fun report(released: Boolean): Boolean {
        shown = null
        if (released) {
            // Android sends no delete intent for a notification the app cancels itself.
            NotificationManagerCompat.from(context).cancel(NOTICE_TAG, 0)
        } else {
            showNotice(Notice.STUCK)
        }
        return released
    }

    private fun deviceIdle() = context.getSystemService(PowerManager::class.java)?.isDeviceIdleMode == true

    private fun push(coordinates: MockCoordinates, accuracy: Float) {
        for (provider in standardProviders) {
            // Another simulator may own the name since the mock location app changed.
            check(!appOpChanged) { "mock location app changed" }
            claim(provider)
            access.set(
                provider,
                Location(provider).apply {
                    latitude = coordinates.latitude
                    longitude = coordinates.longitude
                    this.accuracy = accuracy
                    time = System.currentTimeMillis()
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                },
            )
        }
    }

    private fun claim(provider: String) {
        if (provider in added) return
        val recorded = checkNotNull(recorded()) { "provider record unavailable" }
        // Adding would replace a provider of the same name, so a pending removal blocks it.
        check((recorded - added).isEmpty()) { "removal pending" }
        if (unwatch == null) {
            val current = synchronized(watchLock) { ++watch }
            unwatch = access.watch { op, _ ->
                if (mayConcernTestProviders(op)) synchronized(watchLock) { if (current == watch) changedDuring = current }
            }
        }
        // A failed add can still have replaced a provider, so the name stays recorded then.
        writeRecord(recorded + provider)
        access.add(provider)
        added += provider
        access.enable(provider)
    }

    /**
     * Removes the providers this process kept and returns whether nothing this app recorded may
     * remain. Other recorded names came from an earlier process or predate a mock location app
     * change; they wait for confirmation.
     */
    private fun release(): Boolean {
        // Another simulator could have replaced these providers since the mock location app changed.
        if (appOpChanged || !access.allowed()) forget()
        var removed = added.filter(::remove).toSet()
        // Android ignores removals without an error once the app op is revoked.
        if (appOpChanged || !access.allowed()) {
            forget()
            removed = emptySet()
        }
        added -= removed
        if (added.isEmpty()) forget()
        val remaining = (recorded() ?: return false) - removed
        return runCatching { writeRecord(remaining) }.isSuccess && remaining.isEmpty()
    }

    private fun forget() {
        added.clear()
        unwatch?.invoke()
        unwatch = null
        synchronized(watchLock) { watch++ }
    }

    private fun remove(provider: String) = try {
        access.remove(provider)
        true
    } catch (_: IllegalArgumentException) {
        // Android 10 and earlier report an unknown name: no test provider by that name remains.
        true
    } catch (e: Exception) {
        Logs.w("A simulated location provider could not be removed", e)
        false
    }

    private fun recorded() = recordedIn(record, context)

    /**
     * Stores [names] for this boot as the boot marker, then one name per line, or deletes the
     * record when none remain. AtomicFile does not report every failure, so the result is read back.
     */
    private fun writeRecord(names: Set<String>) {
        if (names.isEmpty()) {
            record.delete()
            // A leftover record of another boot also reads as empty, so the files themselves are checked.
            check(!record.baseFile.exists() && !backupOf(record).exists()) { "provider record not cleared" }
            return
        }
        val marker = checkNotNull(bootMarker(context)) { "boot count unavailable" }
        val stream = record.startWrite()
        try {
            stream.write((listOf(marker) + names).joinToString("\n").toByteArray())
        } catch (e: IOException) {
            record.failWrite(stream)
            throw e
        }
        record.finishWrite(stream)
        check(recorded() == names) { "provider record not stored" }
    }

    private enum class Notice(val title: Int, val text: Int, val ongoing: Boolean, val turnOff: Boolean) {
        ACTIVE(R.string.gps_spoofing_active, R.string.gps_spoofing_notice, true, true),
        CACHED(R.string.gps_spoofing_cached, R.string.gps_spoofing_cached_summary, true, true),
        UPDATING(R.string.gps_spoofing_updating, R.string.gps_spoofing_updating_summary, true, true),
        STOPPED(R.string.gps_spoofing_failed, R.string.gps_spoofing_failed_summary, false, false),

        // Opens the app, where the user confirms removal; dismissing it shows it again.
        STUCK(R.string.gps_spoofing_stuck, R.string.gps_spoofing_stuck_summary, true, false),
        UNSUPPORTED(R.string.gps_spoofing_unsupported_title, R.string.gps_spoofing_unsupported, false, false),
    }

    @SuppressLint("MissingPermission") // notificationsAllowed() checks the runtime permission.
    private fun showNotice(notice: Notice, vararg textArgs: Any) {
        if (shown == notice || !notificationsAllowed(context)) return
        shown = notice
        val manager = NotificationManagerCompat.from(context)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(NOTICE_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(context.getString(R.string.gps_spoofing))
                .build(),
        )
        val text = context.getString(notice.text, *textArgs)
        val builder = NotificationCompat.Builder(context, NOTICE_CHANNEL)
            .setSmallIcon(R.drawable.ic_baseline_push_pin_24)
            .setContentTitle(context.getString(notice.title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(SagerNet.configureIntent(context))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setOnlyAlertOnce(true)
            .setOngoing(notice.ongoing)
        if (notice.ongoing) {
            val turnOff = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, LocationTurnOffReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            if (notice.turnOff) builder.addAction(0, context.getString(R.string.gps_spoofing_turn_off), turnOff)
            // Android 14 and later let the user swipe an ongoing notification away. Dismissing it
            // turns spoofing off like the action, so injection never runs without its control.
            builder.setDeleteIntent(turnOff)
        }
        manager.notify(NOTICE_TAG, 0, builder.build())
    }

    companion object {
        /** The providers this app sets. */
        val standardProviders = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            if (Build.VERSION.SDK_INT >= 31) add(LocationManager.FUSED_PROVIDER)
        }

        @Suppress("DEPRECATION") // unsafeCheckOpNoThrow needs API 29.
        fun mockLocationAllowed(context: Context) = runCatching {
            context.getSystemService(AppOpsManager::class.java)
                .checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        /** The Turn off action lives in the notification, so spoofing runs only while it can show. */
        fun notificationsAllowed(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return manager.getNotificationChannelCompat(NOTICE_CHANNEL)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }

        // Android keeps test providers until the device restarts. The boot count tells a later
        // process whether a record can still name providers.
        private fun bootMarker(context: Context): String? {
            if (Build.VERSION.SDK_INT < 24) return null
            val count = runCatching {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            }.getOrDefault(-1)
            return if (count >= 0) "boot $count" else null
        }

        /**
         * Names the record holds for the current boot, read without changing any file, so that
         * another process can ask while the background process writes. Empty when there is no
         * record, or when it comes from another boot: a restart removes every test provider. Null
         * when that cannot be told: the record is unreadable or not exactly in the stored form, or
         * the boot count is unavailable, which does not prove a restart.
         */
        private fun recordedIn(record: AtomicFile, context: Context): Set<String>? {
            // AtomicFile.readFully restores or deletes the files of an unfinished write. Until a
            // write finishes, Android 10 and earlier keep the previous version as ".bak", which
            // counts first; when it disappears meanwhile, the base file is current.
            val content = listOf(backupOf(record), record.baseFile).firstNotNullOfOrNull { file ->
                try {
                    file.readText()
                } catch (_: IOException) {
                    if (file.exists()) return null
                    null
                }
            } ?: return emptySet()
            val lines = content.split('\n')
            val header = lines.first()
            val names = lines.drop(1)
            val stored = header.removePrefix("boot ").toIntOrNull()?.let { it >= 0 && header == "boot $it" } == true &&
                names.isNotEmpty() && names.all { it in standardProviders } && names.distinct() == names
            if (!stored) return null
            val marker = bootMarker(context) ?: return null
            return if (header == marker) names.toSet() else emptySet()
        }

        private fun backupOf(record: AtomicFile) = File(record.baseFile.path + ".bak")

        /**
         * Whether a change reported to the app op watch may concern the providers. Every app
         * allowed to mock locations can replace or remove a test provider of the same name, so the
         * package does not rule a change out; only another op does, and a missing name counts.
         */
        private fun mayConcernTestProviders(op: String?) = op == null || op == AppOpsManager.OPSTR_MOCK_LOCATION

        /**
         * Spoofing needs the boot count, which Android 6.0 and some systems do not provide.
         * Without it the app could not tell after a restart whether its record still applies.
         */
        fun supported(context: Context) = bootMarker(context) != null

        /** Whether a simulated location this app set may still be in place. */
        fun mayRemain(context: Context) = recordedIn(AtomicFile(File(context.noBackupFilesDir, RECORD_FILE)), context)?.isNotEmpty() ?: true

        /** Removal the user confirmed in the app; the result code reports whether it finished. */
        fun confirmedRemoval(context: Context): Intent = Intent(context, LocationTurnOffReceiver::class.java)
            .putExtra(EXTRA_REMOVE_CONFIRMED, true)
    }
}

/**
 * Turns spoofing off, from the notice by its action or by dismissing it, or after the user
 * confirmed removal in the app. The setting stays off until it is turned on again. Without that
 * confirmation only the providers the running process kept are removed.
 */
class LocationTurnOffReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DataStore.gpsSpoofing = false
        val spoofing = DataStore.baseService?.data?.locationSpoofing ?: LocationSpoofing(context.applicationContext)
        val released = if (intent.getBooleanExtra(EXTRA_REMOVE_CONFIRMED, false)) spoofing.removeConfirmed() else spoofing.stop()
        if (isOrderedBroadcast) resultCode = if (released) Activity.RESULT_OK else Activity.RESULT_CANCELED
        // The setting is written in the background; keep this process until it is stored.
        val pending: PendingResult? = goAsync()
        runOnDefaultDispatcher {
            try {
                DataStore.configurationStore.awaitWrites()
            } catch (e: Exception) {
                Logs.w(e)
            } finally {
                pending?.finish()
            }
        }
    }
}
