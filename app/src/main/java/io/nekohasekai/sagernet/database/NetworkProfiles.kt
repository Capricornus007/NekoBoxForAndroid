package io.nekohasekai.sagernet.database

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Network
import android.os.Build
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import androidx.core.content.ContextCompat
import com.google.gson.reflect.TypeToken
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.dnsServerConfig
import moe.matsuri.nb4a.utils.JavaUtil.gson

object NetworkProfiles {
    data class Selection(val identity: String, val policy: NetworkProfilePolicy?)
    fun policies(): List<NetworkProfilePolicy> = runCatching {
        require(DataStore.networkProfiles.length <= 65536)
        val policies = gson.fromJson<List<NetworkProfilePolicy>>(DataStore.networkProfiles, object : TypeToken<List<NetworkProfilePolicy>>() {}.type).orEmpty()
        require(policies.size <= 32)
        policies.forEach(::validate)
        require(policies.map { it.transport to it.ssid }.distinct().size == policies.size)
        policies
    }.getOrDefault(emptyList())

    fun validate(policy: NetworkProfilePolicy) {
        require(policy.id.isNotBlank() && policy.id.length <= 128)
        require(policy.transport in setOf("wifi", "mobile", "ethernet") && policy.profileId > 0)
        require(policy.ssid.length <= 128 && (policy.transport == "wifi" || policy.ssid.isBlank()))
        for (value in listOf(policy.remoteDns, policy.directDns)) {
            require(value.length <= 8192)
            value.lineSequence().map(String::trim).filter(String::isNotBlank).forEach {
                dnsServerConfig(it, "dns-test", "dns-local", "direct")
            }
        }
    }
    fun save(policies: List<NetworkProfilePolicy>) {
        require(policies.size <= 32)
        policies.forEach(::validate)
        require(policies.map { it.transport to it.ssid }.distinct().size == policies.size)
        DataStore.networkProfiles = gson.toJson(policies)
    }
    @Suppress("DEPRECATION")
    fun select(network: Network? = SagerNet.underlyingNetwork ?: SagerNet.connectivity.activeNetwork): Selection {
        if (!DataStore.networkProfilesEnabled || network == null) return Selection("disabled", null)
        val caps = SagerNet.connectivity.getNetworkCapabilities(network) ?: return Selection("unavailable", null)
        val transport = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> return Selection("unavailable", null)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val ssidAccess = ContextCompat.checkSelfPermission(SagerNet.application, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
            (Build.VERSION.SDK_INT < 29 || ContextCompat.checkSelfPermission(SagerNet.application, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED)
        val ssid = if (transport == "wifi" && ssidAccess) {
            runCatching { (SagerNet.application.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo?.ssid }
                .getOrNull()?.removeSurrounding("\"")?.takeUnless { it == WifiManager.UNKNOWN_SSID || it.isBlank() }
        } else null
        val policy = matchNetworkProfile(policies(), transport, ssid)?.takeIf { SagerDatabase.proxyDao.getById(it.profileId) != null }
        // Private identifier; never write it or the SSID/DNS overrides to the log.
        return Selection("$transport|${ssid.orEmpty()}|${policy?.hashCode()}", policy)
    }
}
