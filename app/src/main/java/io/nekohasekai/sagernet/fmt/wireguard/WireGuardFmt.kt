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
    val peer = json.getAsJsonArray("peers")
        ?.firstOrNull()
        ?.takeIf(JsonElement::isJsonObject)
        ?.asJsonObject
        ?: return null
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
    }
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
        peers = listOf(
            SingBoxOptions.Endpoint_WireGuardPeer().apply {
                address = bean.serverAddress?.takeIf { it.isNotBlank() }
                port = bean.serverPort?.takeIf { it in 1..65535 }
                public_key = normalizeBase64Key(bean.peerPublicKey)
                pre_shared_key = normalizeBase64Key(bean.peerPreSharedKey).takeIf { it.isNotBlank() }
                allowed_ips = listOf("0.0.0.0/0", "::/0")
                persistent_keepalive_interval = bean.persistentKeepaliveInterval?.takeIf { it > 0 }
                reserved = bean.reserved.takeIf { it.isNotBlank() }?.let(::genReservedBytes)
            },
        )
    }
}

@Deprecated("WireGuard is an endpoint in sing-box 1.14+")
fun buildSingBoxOutboundWireguardBean(bean: WireGuardBean): SingBoxOptions.Endpoint_WireGuardOptions =
    buildSingBoxEndpointWireGuardBean(bean)

private fun normalizeBase64Key(value: String): String {
    if (value.isBlank()) return value
    val trimmed = value.trim()
    val remainder = trimmed.length % 4
    return if (remainder == 0) trimmed else trimmed + "=".repeat(4 - remainder)
}
