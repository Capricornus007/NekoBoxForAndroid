package moe.matsuri.nb4a

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.system.OsConstants
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import io.nekohasekai.sagernet.utils.PackageCache
import kotlinx.coroutines.runBlocking
import libcore.BoxPlatformInterface
import libcore.InterfaceUpdateListener
import libcore.Libcore
import libcore.NB4AInterface
import libcore.NetworkInterfaceIterator
import libcore.StringIterator
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import libcore.NetworkInterface as LibcoreNetworkInterface

class NativeInterface : BoxPlatformInterface, NB4AInterface {

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

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
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

    // 默认接口监视器（sing-box 官方内核强制平台提供）。
    // 复用 DefaultNetworkListener：registerBestMatchingNetworkCallback 避开 VPN 接口，
    // 报告的是物理默认网络（WiFi/蜂窝）。

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (listener == null) return
        // 必须同步注册：官方内核拨号时 DefaultInterface()==nil 会秒报
        // "no available network interface"（见 libcore/interface_monitor.go 批注）。
        // 原先 runOnDefaultDispatcher 异步注册，测试盒 box.Start() 后立刻拨号，
        // 首拨几乎必然抢在首次回调之前 → 批量测速大面积"超时"。
        // DefaultNetworkListener 的 actor 是 Dispatchers.Unconfined，send 内联处理，
        // 缓存命中时首次回调在此调用返回前即完成（调用的 Go 线程短暂阻塞，可接受）。
        runBlocking {
            DefaultNetworkListener.start(listener) { network ->
                checkDefaultInterfaceUpdate(listener, network)
            }
        }
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener?) {
        if (listener == null) return
        // 与 start 对称同步化，避免 box.Close 后监听者残留/时序错乱。
        runBlocking {
            DefaultNetworkListener.stop(listener)
        }
    }

    private fun checkDefaultInterfaceUpdate(listener: InterfaceUpdateListener, network: Network?) {
        if (network == null) {
            Logs.i("checkDefaultInterfaceUpdate network=null -> clear default interface")
            listener.updateDefaultInterface("", -1)
            return
        }
        // LinkProperties / NetworkInterface 可能短暂未就绪，参考 husi 重试
        repeat(10) { attempt ->
            val linkProperties = SagerNet.connectivity.getLinkProperties(network)
            if (linkProperties == null) {
                Logs.i("checkDefaultInterfaceUpdate attempt=${attempt + 1} linkProperties=null network=$network")
                Thread.sleep(100)
                return@repeat
            }
            val interfaceIndex = try {
                NetworkInterface.getByName(linkProperties.interfaceName).index
            } catch (e: Exception) {
                Logs.i("checkDefaultInterfaceUpdate attempt=${attempt + 1} getByName failed name=${linkProperties.interfaceName}: $e")
                Thread.sleep(100)
                return@repeat
            }
            Logs.i("checkDefaultInterfaceUpdate ok name=${linkProperties.interfaceName} index=$interfaceIndex network=$network attempt=${attempt + 1}")
            listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex)
            return
        }
        Logs.w("checkDefaultInterfaceUpdate exhausted retries network=$network -> clear default interface")
        listener.updateDefaultInterface("", -1)
    }

    // 平台网络接口枚举（sing-box 官方内核拨号路径强制要求，否则报 no available network interface）。
    // 参考 husi AndroidPlatformInterface.getInterfaces。

    override fun getInterfaces(): NetworkInterfaceIterator {
        @Suppress("DEPRECATION") val networks = SagerNet.connectivity.allNetworks
        val networkInterfaces = NetworkInterface.getNetworkInterfaces().toList()
        val interfaces = mutableListOf<LibcoreNetworkInterface>()
        for (network in networks) {
            val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: continue
            val networkCapabilities = SagerNet.connectivity.getNetworkCapabilities(network) ?: continue
            val boxInterface = LibcoreNetworkInterface()
            boxInterface.name = linkProperties.interfaceName
            val networkInterface = networkInterfaces.find { it.name == boxInterface.name } ?: continue
            boxInterface.dnsServer = linkProperties.dnsServers.mapNotNull { it.hostAddress }
                .let { it.toStringIterator(it.size) }
            boxInterface.type = when {
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libcore.InterfaceTypeWIFI
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libcore.InterfaceTypeCellular
                networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libcore.InterfaceTypeEthernet
                else -> Libcore.InterfaceTypeOther
            }
            boxInterface.index = networkInterface.index
            runCatching { boxInterface.mtu = networkInterface.mtu }
                .onFailure { Logs.w("failed to get mtu for interface ${boxInterface.name}: $it") }
            boxInterface.addresses = networkInterface.interfaceAddresses.map { it.toPrefix() }
                .let { it.toStringIterator(it.size) }
            var dumpFlags = 0
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                dumpFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (networkInterface.isLoopback) dumpFlags = dumpFlags or OsConstants.IFF_LOOPBACK
            if (networkInterface.isPointToPoint) dumpFlags = dumpFlags or OsConstants.IFF_POINTOPOINT
            if (networkInterface.supportsMulticast()) dumpFlags = dumpFlags or OsConstants.IFF_MULTICAST
            boxInterface.flags = dumpFlags
            boxInterface.metered =
                !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            interfaces.add(boxInterface)
        }
        return InterfaceArray(interfaces.iterator(), interfaces.size)
    }

    private class InterfaceArray(
        private val iterator: Iterator<LibcoreNetworkInterface>,
        private val size: Int,
    ) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibcoreNetworkInterface = iterator.next()
        override fun length(): Int = size
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val id = data.proxy!!.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}

private fun Iterable<String>.toStringIterator(size: Int): StringIterator {
    return object : StringIterator {
        private val it = iterator()
        override fun hasNext(): Boolean = it.hasNext()
        override fun next(): String = it.next()
        override fun length(): Int = size
    }
}

private fun InterfaceAddress.toPrefix(): String {
    return if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/${networkPrefixLength}"
    } else {
        "${address.hostAddress}/${networkPrefixLength}"
    }
}
