@file:Suppress("SpellCheckingInspection")

package io.nekohasekai.sagernet.ktx

import io.nekohasekai.sagernet.BuildConfig
import moe.matsuri.nb4a.utils.NGUtil
import okhttp3.HttpUrl
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URLDecoder

fun linkBuilder() = HttpUrl.Builder().scheme("https")

/** Decode an encoded query value without treating an unescaped base64 '+' as a space. */
fun HttpUrl.queryParameterPreservingPlus(name: String): String? {
    val pair = encodedQuery
        ?.split('&')
        ?.firstOrNull { it.substringBefore('=') == name }
        ?: return null
    val encodedValue = pair.substringAfter('=', "")
    return URLDecoder.decode(encodedValue.replace("+", "%2B"), Charsets.UTF_8.name())
}

fun HttpUrl.Builder.toLink(scheme: String, appendDefaultPort: Boolean = true): String {
    var url = build()
    val defaultPort = HttpUrl.defaultPort(url.scheme)
    var replace = false
    if (appendDefaultPort && url.port == defaultPort) {
        url = url.newBuilder().port(14514).build()
        replace = true
    }
    return url.toString().replace("${url.scheme}://", "$scheme://").let {
        if (replace) it.replace(":14514", ":$defaultPort") else it
    }
}

fun String.isIpAddress(): Boolean = NGUtil.isIpv4Address(this) || NGUtil.isIpv6Address(this)

fun String.isIpAddressV6(): Boolean = NGUtil.isIpv6Address(this)

// [2001:4860:4860::8888] -> 2001:4860:4860::8888
fun String.unwrapIPV6Host(): String {
    if (startsWith("[") && endsWith("]")) {
        return substring(1, length - 1).unwrapIPV6Host()
    }
    return this
}

// [2001:4860:4860::8888] or 2001:4860:4860::8888 -> [2001:4860:4860::8888]
fun String.wrapIPV6Host(): String {
    val unwrapped = this.unwrapIPV6Host()
    if (unwrapped.isIpAddressV6()) {
        return "[$unwrapped]"
    } else {
        return this
    }
}

fun mkPort(): Int {
    val socket = Socket()
    socket.reuseAddress = true
    socket.bind(InetSocketAddress(0))
    val port = socket.localPort
    socket.close()
    return port
}

const val USER_AGENT = "NekoBox/Android/" + BuildConfig.VERSION_NAME + " (Prefer ClashMeta Format)"
