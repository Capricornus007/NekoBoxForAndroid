package moe.matsuri.nb4a

import android.Manifest.permission.POST_NOTIFICATIONS
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import io.nekohasekai.sagernet.CORE_NOTIFICATION_CHANNEL
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.NetworkInterface

// Identifier prefix of the core's Tailscale login notification, followed by the endpoint tag
// (protocol/tailscale/endpoint.go).
const val TAILSCALE_LOGIN_NOTIFICATION = "tailscale-authentication"

class NativeInterface :
    BoxPlatformInterface,
    NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        DataStore.vpnService?.protect(fd)
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int): Int = SagerNet.connectivity.getConnectionOwnerUid(
        ipProto,
        InetSocketAddress(srcIp, srcPort),
        InetSocketAddress(destIp, destPort),
    )

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) {
            for (packageName in packageNames) {
                return packageName
            }
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    // The core asks for a notification; today that is the Tailscale interactive login URL.
    // The app's own wording replaces the core's, and the tap opens the URL in the browser.
    override fun sendNotification(identifier: String, typeID: Int, title: String, body: String, openURL: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Logs.w("notification permission missing; $identifier")
            return
        }
        val login = identifier.startsWith(TAILSCALE_LOGIN_NOTIFICATION)
        val builder = NotificationCompat.Builder(app, CORE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_service_active)
            .setContentTitle(if (login) app.getString(R.string.tailscale_login_required) else title)
            .setContentText(if (login) app.getString(R.string.tailscale_login_tap) else body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
        if (openURL.isNotEmpty()) {
            val open = Intent(Intent.ACTION_VIEW, openURL.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            builder.setContentIntent(
                PendingIntent.getActivity(app, typeID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE),
            )
        }
        SagerNet.notification.notify(identifier, typeID, builder.build())
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        SagerNet.notification.cancel(identifier, typeID)
    }

    // Tailscale's interface list. Go's net.Interfaces needs a netlink bind that Android 11+
    // denies to apps; this API still works there, only without hardware addresses. An
    // enumeration failure reaches the core as an error.
    override fun networkInterfaces(): String = JSONArray().apply {
        for (nif in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
            val addresses = nif.interfaceAddresses.mapNotNull { address ->
                address.address?.hostAddress?.substringBefore('%')?.let { "$it/${address.networkPrefixLength}" }
            }
            put(
                JSONObject()
                    .put("name", nif.name)
                    .put("index", nif.index)
                    .put("mtu", runCatching { nif.mtu }.getOrDefault(0))
                    .put("up", runCatching { nif.isUp }.getOrDefault(false))
                    .put("loopback", runCatching { nif.isLoopback }.getOrDefault(false))
                    .put("pointToPoint", runCatching { nif.isPointToPoint }.getOrDefault(false))
                    .put("multicast", runCatching { nif.supportsMulticast() }.getOrDefault(false))
                    .put("addresses", JSONArray(addresses)),
            )
        }
    }.toString()

    // nb4a interface

    override fun useOfficialAssets(): Boolean = DataStore.rulesProvider == 0

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val proxy = data.proxy ?: return@runOnDefaultDispatcher
                val id = proxy.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                proxy.looper?.selectMain(id)
                proxy.displayProfileName = ServiceNotification.genTitle(ent)
                data.notification?.postNotificationTitle(proxy.displayProfileName)
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }
}
