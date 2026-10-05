package io.nekohasekai.sagernet.ui

import org.json.JSONObject
import java.net.URI

internal data class TailscaleStatusPeer(
    val id: String,
    val name: String,
    val dnsName: String,
    val ips: List<String>,
    val online: Boolean,
    val expired: Boolean,
    val keyExpiry: Long,
    val exitNodeOption: Boolean,
    val exitNodeSelected: Boolean,
)

internal data class TailscaleCurrentExit(val id: String, val ip: String, val live: Boolean)

internal data class TailscaleNodeStatus(
    val backendState: String,
    val needsLogin: Boolean,
    val needsApproval: Boolean,
    val authUrl: String,
    val keyAuth: Boolean,
    val self: TailscaleStatusPeer?,
    val currentExit: TailscaleCurrentExit?,
    val peers: List<TailscaleStatusPeer>,
    val totalPeers: Int,
    val peersTruncated: Boolean,
) {
    fun savedExitDiffers(saved: String): Boolean {
        val current = currentExit ?: return saved.isNotEmpty()
        if (saved == current.id || saved == current.ip) return false
        val peer = peers.firstOrNull { it.id == current.id }
        return peer == null || (saved != peer.name && saved != peer.dnsName && saved !in peer.ips)
    }
}

internal data class TailscaleStatusEnvelope(
    val profileId: Long,
    val identity: String,
    val generation: Long,
    val source: String,
    val stage: String,
    val savedExit: String,
    val node: TailscaleNodeStatus?,
    val errorCode: String,
)

internal data class TailscalePingSample(
    val peerId: String,
    val peerIp: String,
    val sequence: Int,
    val latencyMs: Double?,
    val path: String,
    val derpRegionId: Int,
    val derpRegionCode: String,
    val error: Boolean,
)

internal sealed interface TailscaleStatusResult {
    data class Ping(val done: Boolean, val sample: TailscalePingSample?, val error: Boolean) : TailscaleStatusResult
    data class Exit(val outcome: String, val savedExit: String) : TailscaleStatusResult
}

internal object TailscaleStatusParser {
    private fun json(value: String): JSONObject {
        require(value.toByteArray(Charsets.UTF_8).size <= 132 * 1024)
        return JSONObject(value)
    }

    fun status(value: String): TailscaleStatusEnvelope = json(value).let { obj ->
        require(obj.getInt("version") == 1)
        TailscaleStatusEnvelope(
            obj.getLong("profileId"),
            obj.getString("identity"),
            obj.getLong("generation"),
            obj.getString("source"),
            obj.getString("stage"),
            obj.getString("savedExit"),
            obj.optJSONObject("node")?.let(::node),
            obj.optString("errorCode"),
        )
    }

    private fun peer(obj: JSONObject) = TailscaleStatusPeer(
        obj.getString("id"), obj.getString("name"), obj.getString("dnsName"),
        obj.getJSONArray("ips").let { ips -> List(ips.length()) { ips.getString(it) } },
        obj.getBoolean("online"), obj.getBoolean("expired"), obj.getLong("keyExpiry"),
        obj.getBoolean("exitNodeOption"), obj.getBoolean("exitNodeSelected"),
    )

    private fun node(obj: JSONObject): TailscaleNodeStatus {
        val peers = obj.getJSONArray("peers")
        require(peers.length() <= 256)
        return TailscaleNodeStatus(
            obj.getString("backendState"), obj.getBoolean("needsLogin"), obj.getBoolean("needsApproval"),
            obj.getString("authUrl"), obj.getBoolean("keyAuth"), obj.optJSONObject("self")?.let(::peer),
            obj.optJSONObject("currentExit")?.let {
                TailscaleCurrentExit(it.getString("id"), it.getString("ip"), it.getBoolean("live"))
            },
            List(peers.length()) { peer(peers.getJSONObject(it)) },
            obj.getInt("totalPeers"), obj.getBoolean("peersTruncated"),
        )
    }

    fun result(value: String): TailscaleStatusResult = json(value).let { obj ->
        when (obj.getString("kind")) {
            "exit" -> TailscaleStatusResult.Exit(obj.getString("outcome"), obj.getString("savedExit"))

            "ping" -> TailscaleStatusResult.Ping(
                obj.getBoolean("done"),
                obj.optJSONObject("sample")?.let {
                    val error = it.optString("error").isNotEmpty()
                    val latency = if (it.isNull("latencyMs") || error) null else it.getDouble("latencyMs")
                    require(latency == null || (latency.isFinite() && latency >= 0))
                    TailscalePingSample(
                        it.getString("peerId"),
                        it.getString("peerIp"),
                        it.getInt("sequence"),
                        latency,
                        if (error) {
                            "unknown"
                        } else {
                            it.getString("path").takeIf { path ->
                                path in setOf("direct", "derp", "peer-relay", "unknown")
                            } ?: "unknown"
                        },
                        it.getInt("derpRegionId"),
                        it.getString("derpRegionCode"),
                        error,
                    )
                },
                obj.optString("errorCode").isNotEmpty(),
            )

            else -> error("Unsupported Tailscale result")
        }
    }
}

// Deliberately not a data class: its string representation must not expose the login token.
internal class TailscaleLoginLink private constructor(val url: String, val origin: String) {
    val isHttp get() = origin.startsWith("http://")

    companion object {
        fun parse(url: String): TailscaleLoginLink? = runCatching {
            require(url.none { it.isWhitespace() || it.isISOControl() || it == '\\' })
            require(!Regex("%(?:0[0-9a-f]|1[0-9a-f]|7f)", RegexOption.IGNORE_CASE).containsMatchIn(url))
            val uri = URI(url)
            val scheme = uri.scheme?.lowercase()
            require(scheme == "http" || scheme == "https")
            require(!uri.host.isNullOrBlank() && uri.rawUserInfo == null)
            require(uri.port == -1 || uri.port in 1..65535)
            TailscaleLoginLink(url, "$scheme://${uri.host}" + if (uri.port == -1) "" else ":${uri.port}")
        }.getOrNull()
    }
}
