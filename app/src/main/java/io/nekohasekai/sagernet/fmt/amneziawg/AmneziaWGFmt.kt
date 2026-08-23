package io.nekohasekai.sagernet.fmt.amneziawg

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.genReservedBytes
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma

fun buildSingBoxEndpointAmneziaWGBean(bean: AmneziaWGBean): SingBoxOptions.Endpoint_AwgOptions {
    return SingBoxOptions.Endpoint_AwgOptions().apply {
        type = "awg"
        address = bean.localAddress.listByLineOrComma()
        private_key = bean.privateKey
        mtu = bean.mtu
        listen_port = bean.listenPort?.takeIf { it > 0 }
        peers = listOf(
            SingBoxOptions.Endpoint_AwgPeer().apply {
                address = bean.serverAddress
                port = bean.serverPort
                public_key = bean.peerPublicKey
                preshared_key = bean.peerPreSharedKey
                allowed_ips = listOf("0.0.0.0/0", "::/0")
                persistent_keepalive_interval = bean.persistentKeepaliveInterval?.takeIf { it > 0 }
                reserved = bean.reserved.takeIf { it.isNotBlank() }?.let(::genReservedBytes)
            },
        )

        // AmneziaWG obfuscation parameters; zero/blank values are omitted so the
        // tunnel behaves like plain WireGuard when unset.
        if (bean.jc != 0) jc = bean.jc
        if (bean.jmin != 0) jmin = bean.jmin
        if (bean.jmax != 0) jmax = bean.jmax
        if (bean.s1 != 0) s1 = bean.s1
        if (bean.s2 != 0) s2 = bean.s2
        if (bean.s3 != 0) s3 = bean.s3
        if (bean.s4 != 0) s4 = bean.s4
        if (bean.h1.isNotBlank()) h1 = bean.h1
        if (bean.h2.isNotBlank()) h2 = bean.h2
        if (bean.h3.isNotBlank()) h3 = bean.h3
        if (bean.h4.isNotBlank()) h4 = bean.h4
        if (bean.i1.isNotBlank()) i1 = bean.i1
        if (bean.i2.isNotBlank()) i2 = bean.i2
        if (bean.i3.isNotBlank()) i3 = bean.i3
        if (bean.i4.isNotBlank()) i4 = bean.i4
        if (bean.i5.isNotBlank()) i5 = bean.i5
    }
}

fun parseAmneziaWGEndpoint(json: JsonObject): AmneziaWGBean? {
    if (json.stringValue("type") != "awg") return null
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

    return AmneziaWGBean().applyDefaultValues().apply {
        name = json.stringValue("tag").orEmpty()
        localAddress = localAddresses.joinToString("\n")
        this.privateKey = privateKey
        json.intValue("mtu")?.takeIf { it > 0 }?.let { mtu = it }
        listenPort = json.intValue("listen_port")?.takeIf { it in 1..65535 } ?: 0
        this.serverAddress = serverAddress
        this.serverPort = serverPort
        peerPublicKey = publicKey
        peerPreSharedKey = peer.stringValue("preshared_key").orEmpty()
        persistentKeepaliveInterval = peer.intValue("persistent_keepalive_interval")?.takeIf { it > 0 } ?: 0
        reserved = peer.reservedValue().orEmpty()
        jc = json.intValue("jc") ?: 0
        jmin = json.intValue("jmin") ?: 0
        jmax = json.intValue("jmax") ?: 0
        s1 = json.intValue("s1") ?: 0
        s2 = json.intValue("s2") ?: 0
        s3 = json.intValue("s3") ?: 0
        s4 = json.intValue("s4") ?: 0
        h1 = json.stringValue("h1").orEmpty()
        h2 = json.stringValue("h2").orEmpty()
        h3 = json.stringValue("h3").orEmpty()
        h4 = json.stringValue("h4").orEmpty()
        i1 = json.stringValue("i1").orEmpty()
        i2 = json.stringValue("i2").orEmpty()
        i3 = json.stringValue("i3").orEmpty()
        i4 = json.stringValue("i4").orEmpty()
        i5 = json.stringValue("i5").orEmpty()
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

@Deprecated("AmneziaWG is an endpoint in sing-box 1.14+")
fun buildSingBoxOutboundAmneziaWGBean(bean: AmneziaWGBean): SingBoxOptions.Endpoint_AwgOptions =
    buildSingBoxEndpointAmneziaWGBean(bean)

fun WireGuardBean.toAmneziaWGBean() = AmneziaWGBean().apply {
    serverAddress = this@toAmneziaWGBean.serverAddress
    serverPort = this@toAmneziaWGBean.serverPort
    name = this@toAmneziaWGBean.name
    customOutboundJson = this@toAmneziaWGBean.customOutboundJson
    customConfigJson = this@toAmneziaWGBean.customConfigJson
    localAddress = this@toAmneziaWGBean.localAddress
    privateKey = this@toAmneziaWGBean.privateKey
    peerPublicKey = this@toAmneziaWGBean.peerPublicKey
    peerPreSharedKey = this@toAmneziaWGBean.peerPreSharedKey
    mtu = this@toAmneziaWGBean.mtu
    reserved = this@toAmneziaWGBean.reserved
    listenPort = this@toAmneziaWGBean.listenPort
    persistentKeepaliveInterval = this@toAmneziaWGBean.persistentKeepaliveInterval
    jc = this@toAmneziaWGBean.jc
    jmin = this@toAmneziaWGBean.jmin
    jmax = this@toAmneziaWGBean.jmax
    s1 = this@toAmneziaWGBean.s1
    s2 = this@toAmneziaWGBean.s2
    s3 = this@toAmneziaWGBean.s3
    s4 = this@toAmneziaWGBean.s4
    h1 = this@toAmneziaWGBean.h1
    h2 = this@toAmneziaWGBean.h2
    h3 = this@toAmneziaWGBean.h3
    h4 = this@toAmneziaWGBean.h4
    i1 = this@toAmneziaWGBean.i1
    i2 = this@toAmneziaWGBean.i2
    i3 = this@toAmneziaWGBean.i3
    i4 = this@toAmneziaWGBean.i4
    i5 = this@toAmneziaWGBean.i5
    initializeDefaultValues()
}
