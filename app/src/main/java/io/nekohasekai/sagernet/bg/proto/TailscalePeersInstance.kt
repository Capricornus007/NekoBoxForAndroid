package io.nekohasekai.sagernet.bg.proto

import android.os.SystemClock
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.ConfigBuildResult
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.isIpAddress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import org.json.JSONArray

data class TailscalePeer(val name: String, val dnsName: String, val ips: List<String>, val online: Boolean, val exitNode: Boolean) {
    fun exitNodeAddress(): String = dnsName.takeIf { it.isNotBlank() }
        ?: ips.firstOrNull { it.isIpAddress() }
        ?: error("Tailscale peer has no selectable DNS name or IP address")
}

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

// Time a user gets to finish an interactive login in the browser while the probe node waits.
const val TAILSCALE_LOGIN_TIMEOUT_MS = 180_000L

// The user dismissed the login prompt; the caller shows nothing further.
class TailscaleLoginDeclined : Exception("login declined")

// The running service reports a node waiting for its interactive login at [url].
class TailscaleLoginPending(val url: String) : Exception("Tailscale needs login")

// A short-lived node for a Tailscale profile the service is not running, used to list its
// peers. It reuses the profile's saved identity, which is free while the service does not.
class TailscalePeersInstance(
    profile: ProxyEntity,
    private val preparedConfig: ConfigBuildResult? = null,
) : BoxInstance(profile) {

    override fun buildConfig() {
        config = preparedConfig ?: buildConfig(profile, true)
    }

    override suspend fun loadConfig() {
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

    // [onLoginRequired] gets the interactive login URL of a node without an auth key; returning
    // true keeps the node up while the user signs in, until the login completes or
    // TAILSCALE_LOGIN_TIMEOUT_MS passes.
    suspend fun listPeers(onLoginRequired: suspend (url: String) -> Boolean = { false }): List<TailscalePeer> =
        runProbe {
            // The config may also carry a group's Tailscale front or landing node; pick this profile's.
            val endpoint = config.tailscaleEndpoints.getValue(profile.id)
            try {
                // Only the login matters here; the configured exit node may be the one being replaced.
                awaitTailscaleReady(box, endpoint.tag, false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val url = Libcore.tailscaleAuthURL(box, endpoint.tag)
                if (url.isEmpty()) throw e
                if (!onLoginRequired(url)) throw TailscaleLoginDeclined()
                awaitLogin(endpoint.tag)
            }
            parseTailscalePeers(Libcore.tailscalePeers(box, endpoint.tag))
        }

    // tailscaleWaitReady returns at once while a login is pending, so poll in short rounds; the
    // delay between rounds is where cancellation (leaving the editor) takes effect.
    private suspend fun awaitLogin(tag: String) {
        val deadline = SystemClock.elapsedRealtime() + TAILSCALE_LOGIN_TIMEOUT_MS
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                Libcore.tailscaleWaitReady(box, tag, false, 2_000)
                return
            } catch (e: Exception) {
                if (SystemClock.elapsedRealtime() > deadline) throw e
            }
            delay(1_000)
        }
    }
}
