package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import org.json.JSONArray

data class TailscalePeer(val name: String, val dnsName: String, val ips: List<String>, val online: Boolean, val exitNode: Boolean)

fun parseTailscalePeers(json: String): List<TailscalePeer> {
    val array = JSONArray(json)
    return (0 until array.length()).map { index ->
        val peer = array.getJSONObject(index)
        val ips = peer.getJSONArray("ips")
        TailscalePeer(
            name = peer.getString("name"),
            dnsName = peer.getString("dnsName"),
            ips = (0 until ips.length()).map { ips.getString(it) },
            online = peer.getBoolean("online"),
            exitNode = peer.getBoolean("exitNode"),
        )
    }
}

// A short-lived node for a Tailscale profile the service is not running, used to list its
// peers. It reuses the profile's saved identity, which is free while the service does not.
class TailscalePeersInstance(profile: ProxyEntity) : BoxInstance(profile) {

    override fun buildConfig() {
        config = buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    suspend fun listPeers(): List<TailscalePeer> = use {
        init()
        launch()
        // The config may also carry a group's Tailscale front or landing node; pick this profile's.
        val endpoint = config.tailscaleEndpoints.getValue(profile.id)
        // Only the login matters here; the configured exit node may be the one being replaced.
        Libcore.tailscaleWaitReady(box, endpoint.tag, false, TAILSCALE_READY_TIMEOUT_MS)
        parseTailscalePeers(Libcore.tailscalePeers(box, endpoint.tag))
    }
}
