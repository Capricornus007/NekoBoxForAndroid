package io.nekohasekai.sagernet.fmt.wireguard

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma
import java.util.Base64

private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

fun genReserved(anyStr: String): String {
    val values = anyStr
        .trim()
        .removeSurrounding("[", "]")
        .split(Regex("[,\\s]+"))
        .filter(String::isNotEmpty)
        .map { value -> value.toIntOrNull()?.takeIf { it in 0..255 } ?: return anyStr }
    if (values.size != 3) return anyStr
    val bits = (values[0] shl 16) or (values[1] shl 8) or values[2]
    return buildString(4) {
        append(BASE64_ALPHABET[(bits ushr 18) and 0x3F])
        append(BASE64_ALPHABET[(bits ushr 12) and 0x3F])
        append(BASE64_ALPHABET[(bits ushr 6) and 0x3F])
        append(BASE64_ALPHABET[bits and 0x3F])
    }
}

fun genReservedBytes(anyStr: String): List<Int>? {
    val values = anyStr
        .trim()
        .removeSurrounding("[", "]")
        .split(Regex("[,\\s]+"))
        .filter(String::isNotEmpty)
    val numeric = values.map { value -> value.toIntOrNull()?.takeIf { it in 0..255 } }
    if (numeric.size == 3 && numeric.all { it != null }) return numeric.filterNotNull()
    return runCatching { Base64.getDecoder().decode(anyStr.trim()) }
        .getOrNull()
        ?.takeIf { it.size == 3 }
        ?.map { it.toInt() and 0xff }
}

fun parseWireGuardEndpoint(json: JsonObject): WireGuardBean? {
    if (json.stringValue("type") != "wireguard") return null
    val peerList = json.getAsJsonArray("peers")
        ?.mapNotNull { element -> element.takeIf(JsonElement::isJsonObject)?.asJsonObject }
        .orEmpty()
    val peer = peerList.firstOrNull() ?: return null
    val localAddresses = json.listableStrings("address") ?: return null
    val privateKey = json.stringValue("private_key") ?: return null
    val serverAddress = peer.stringValue("address") ?: return null
    val serverPort = peer.intValue("port")?.takeIf { it in 1..65535 } ?: return null
    val publicKey = peer.stringValue("public_key") ?: return null

    return WireGuardBean().applyDefaultValues().apply {
        name = json.stringValue("tag").orEmpty()
        localAddress = localAddresses.joinToString("\n")
        this.privateKey = privateKey
        json.intValue("mtu")?.takeIf { it > 0 }?.let { mtu = it }
        listenPort = json.intValue("listen_port")?.takeIf { it in 1..65535 } ?: 0
        this.serverAddress = serverAddress
        this.serverPort = serverPort
        peerPublicKey = publicKey
        peerPreSharedKey = peer.stringValue("pre_shared_key").orEmpty()
        persistentKeepaliveInterval =
            peer.intValue("persistent_keepalive_interval")?.takeIf { it in 1..65535 } ?: 0
        reserved = peer.reservedValue().orEmpty()
        peerAllowedIps = peer.listableStrings("allowed_ips").orEmpty().joinToString(", ")
        // 第二筆以後的 peer 不能丟：原實作只取 firstOrNull，多 peer 配置匯入後會靜默少掉
        // 後面那些目的段。
        extraPeers = formatWireGuardPeerBlocks(
            peerList.drop(1).mapNotNull { element -> wireGuardPeerSpecFromJson(element) },
        )
    }
}

internal fun wireGuardPeerSpecFromJson(
    peer: JsonObject,
    preSharedKeyField: String = "pre_shared_key",
): WireGuardPeerSpec? {
    // 匯入來源可能把 IPv6 寫成 "[2001:db8::1]"，留著方括號會在寫回 [Peer] 時變成雙層、
    // 再解析就整筆被丟掉，所以一律先剝掉。
    val host = (peer.stringValue("address") ?: return null).removeSurrounding("[", "]")
    val port = peer.intValue("port")?.takeIf { it in 1..65535 } ?: return null
    val publicKey = peer.stringValue("public_key") ?: return null
    return WireGuardPeerSpec(
        host = host,
        port = port,
        publicKey = publicKey,
        preSharedKey = peer.stringValue(preSharedKeyField).orEmpty(),
        allowedIPs = peer.listableStrings("allowed_ips").orEmpty().joinToString(", "),
        keepalive = peer.intValue("persistent_keepalive_interval")?.takeIf { it in 1..65535 } ?: 0,
        reserved = peer.reservedValue().orEmpty(),
    )
}

private fun JsonObject.stringValue(name: String): String? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) return null
    return value.asString.trim().takeIf(String::isNotEmpty)
}

private fun JsonObject.intValue(name: String): Int? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    if (!value.isJsonPrimitive) return null
    return value.asJsonPrimitive.asString.trim().toIntOrNull()
}

private fun JsonObject.listableStrings(name: String): List<String>? {
    val value = get(name)?.takeUnless(JsonElement::isJsonNull) ?: return null
    val values = when {
        value.isJsonArray -> value.asJsonArray.toList()
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> listOf(value)
        else -> return null
    }
    return values.mapNotNull { element ->
        element.takeIf(JsonElement::isJsonPrimitive)
            ?.asJsonPrimitive
            ?.takeIf { it.isString }
            ?.asString
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }.takeIf(List<String>::isNotEmpty)
}

private fun JsonObject.reservedValue(): String? {
    val value = get("reserved")?.takeUnless(JsonElement::isJsonNull) ?: return null
    return when {
        value.isJsonPrimitive && value.asJsonPrimitive.isString -> value.asString.trim()
        value.isJsonArray -> value.asJsonArray.mapNotNull { element ->
            element.takeIf(JsonElement::isJsonPrimitive)?.asJsonPrimitive?.asString?.trim()
        }.joinToString(", ")
        else -> null
    }?.takeIf(String::isNotEmpty)
}

fun buildSingBoxEndpointWireGuardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions {
    return SingBoxOptions.Endpoint_WireGuardOptions().apply {
        type = "wireguard"
        address = bean.localAddress.listByLineOrComma().map(::normalizeWireGuardLocalAddress)
        private_key = normalizeBase64Key(bean.privateKey)
        mtu = bean.mtu?.takeIf { it > 0 }
        listen_port = bean.listenPort?.takeIf { it > 0 }
        peers = buildWireGuardPeers(bean)
    }
}

/**
 * 主 peer 取自 profile 本身的伺服器／公鑰欄位，`extraPeers` 裡每個 `[Peer]` 區塊再各補一筆。
 * sing-box 的 peers 是依 allowed_ips 查表選目的地，所以多 peer 的用途是按目的位址分流；
 * 要做漫遊／備援切換請改用代理分組（自動優選），不是把同一個目的段寫兩份。
 */
fun buildWireGuardPeers(bean: WireGuardBean): List<SingBoxOptions.Endpoint_WireGuardPeer> {
    val allowedIPs = parseWireGuardAllowedIPs(bean.peerAllowedIps)
    val peers = mutableListOf(
        SingBoxOptions.Endpoint_WireGuardPeer().apply {
            address = bean.serverAddress?.takeIf { it.isNotBlank() }
            port = bean.serverPort?.takeIf { it in 1..65535 }
            public_key = normalizeBase64Key(bean.peerPublicKey)
            pre_shared_key = normalizeBase64Key(bean.peerPreSharedKey).takeIf { it.isNotBlank() }
            allowed_ips = allowedIPs
            persistent_keepalive_interval = bean.persistentKeepaliveInterval?.takeIf { it > 0 }
            reserved = bean.reserved.takeIf { it.isNotBlank() }?.let(::genReservedBytes)
        },
    )
    parseWireGuardPeerBlocks(bean.extraPeers).forEach { spec ->
        peers.add(
            SingBoxOptions.Endpoint_WireGuardPeer().apply {
                address = spec.host
                port = spec.port
                public_key = normalizeBase64Key(spec.publicKey)
                pre_shared_key = normalizeBase64Key(spec.preSharedKey).takeIf { it.isNotBlank() }
                allowed_ips = parseWireGuardAllowedIPs(spec.allowedIPs)
                persistent_keepalive_interval = spec.keepalive.takeIf { it > 0 }
                reserved = spec.reserved.takeIf { it.isNotBlank() }?.let(::genReservedBytes)
            },
        )
    }
    return peers
}

fun parseWireGuardAllowedIPs(value: String?): List<String> {
    val parsed = value.orEmpty().listByLineOrComma().map { it.trim() }
        .filter { it.isNotEmpty() }
        .map {
            if (it.contains("/")) {
                it
            } else if (it.contains(":")) {
                "$it/64"
            } else {
                "$it/32"
            }
        }
    return parsed.ifEmpty { listOf("0.0.0.0/0", "::/0") }
}

/** 解析 `[Peer]` 區塊文字（WireGuard 官方 conf 寫法），`[Interface]` 區塊一律忽略。 */
fun parseWireGuardPeerBlocks(text: String?): List<WireGuardPeerSpec> {
    if (text.isNullOrBlank()) return emptyList()
    val result = mutableListOf<WireGuardPeerSpec>()
    var current: MutableMap<String, String>? = null
    var inPeer = false

    fun flush() {
        val fields = current ?: return
        current = null
        val endpoint = fields["endpoint"]?.let(::parseWireGuardEndpointAddress) ?: return
        val publicKey = fields["publickey"] ?: return
        result.add(
            WireGuardPeerSpec(
                host = endpoint.first,
                port = endpoint.second,
                publicKey = publicKey,
                preSharedKey = fields["presharedkey"].orEmpty(),
                allowedIPs = fields["allowedips"].orEmpty(),
                keepalive = fields["persistentkeepalive"]?.toIntOrNull() ?: 0,
                reserved = fields["reserved"].orEmpty(),
            ),
        )
    }

    text.lineSequence().forEach { rawLine ->
        val line = stripWireGuardComment(rawLine).trim()
        if (line.isEmpty()) return@forEach
        if (line.startsWith("[") && line.endsWith("]")) {
            flush()
            inPeer = line.substring(1, line.length - 1).trim().equals("Peer", ignoreCase = true)
            return@forEach
        }
        if (!inPeer) return@forEach
        val separator = line.indexOf('=')
        if (separator <= 0) return@forEach
        val key = line.substring(0, separator).trim().lowercase()
        val value = line.substring(separator + 1).trim()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (current == null) current = linkedMapOf()
        current?.put(key, value)
    }
    flush()
    return result
}

private fun stripWireGuardComment(line: String): String {
    for (index in line.indices) {
        if ((line[index] == '#' || line[index] == ';') &&
            (index == 0 || line[index - 1].isWhitespace())
        ) {
            return line.substring(0, index)
        }
    }
    return line
}

private fun parseWireGuardEndpointAddress(value: String): Pair<String, Int>? {
    val endpoint = value.trim()
    val host: String
    val portText: String
    if (endpoint.startsWith('[')) {
        val closing = endpoint.indexOf(']')
        if (closing <= 1 || closing + 1 >= endpoint.length || endpoint[closing + 1] != ':') return null
        host = endpoint.substring(1, closing)
        portText = endpoint.substring(closing + 2)
    } else {
        val separator = endpoint.lastIndexOf(':')
        if (separator <= 0 || separator == endpoint.lastIndex) return null
        host = endpoint.substring(0, separator).trim()
        portText = endpoint.substring(separator + 1)
    }
    val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
    return host.takeIf(String::isNotBlank)?.let { it to port }
}

data class WireGuardPeerSpec(
    val host: String,
    val port: Int,
    val publicKey: String,
    val preSharedKey: String = "",
    val allowedIPs: String = "",
    val keepalive: Int = 0,
    val reserved: String = "",
)

/** 把多餘的 peer 寫回 `[Peer]` 區塊文字，供匯入／匯出往返。 */
fun formatWireGuardPeerBlocks(specs: List<WireGuardPeerSpec>): String = buildString {
    specs.forEach { spec ->
        append("[Peer]\n")
        append("Endpoint = ").append(formatWireGuardEndpointAddress(spec.host, spec.port)).append('\n')
        append("PublicKey = ").append(spec.publicKey).append('\n')
        if (spec.preSharedKey.isNotBlank()) append("PresharedKey = ").append(spec.preSharedKey).append('\n')
        if (spec.allowedIPs.isNotBlank()) append("AllowedIPs = ").append(spec.allowedIPs).append('\n')
        if (spec.keepalive > 0) append("PersistentKeepalive = ").append(spec.keepalive).append('\n')
        if (spec.reserved.isNotBlank()) append("Reserved = ").append(spec.reserved).append('\n')
        append('\n')
    }
}.trim()

private fun formatWireGuardEndpointAddress(host: String, port: Int): String =
    if (host.contains(':')) "[$host]:$port" else "$host:$port"

@Deprecated("WireGuard is an endpoint in sing-box 1.14+")
fun buildSingBoxOutboundWireguardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions =
    buildSingBoxEndpointWireGuardBean(bean)

private fun normalizeBase64Key(value: String): String {
    if (value.isBlank()) return value
    val trimmed = value.trim()
    val remainder = trimmed.length % 4
    return if (remainder == 0) trimmed else trimmed + "=".repeat(4 - remainder)
}
