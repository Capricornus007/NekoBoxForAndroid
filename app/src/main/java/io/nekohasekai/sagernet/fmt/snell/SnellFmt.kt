package io.nekohasekai.sagernet.fmt.snell

import android.net.Uri
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.unUrlSafe
import io.nekohasekai.sagernet.ktx.urlSafe
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

// URI 格式: snell://base64(psk)@server:port?version=6&userkey=base64(userkey)&mode=default&reuse=true&network=tcp#name
// or
// URI format: snell://base64(psk)@server:port?version=4&obfs-mode=http&obfs-host=bing.com&reuse=true&network=tcp#name
fun parseSnell(url: String): SnellBean {
    val link = url.replace("snell://", "https://").toHttpUrlOrNull()
    // 標準解析有兩個靜默陷阱：(1) psk 含 `/` 時（`snell://abc/def@host:1443`）會被切成
    // host=abc、後面全丟；(2) 我們是把 scheme 換成 https 再解析，原文沒寫端口時 okhttp
    // 會默默給 443 而不是報錯。兩種都比「整筆失敗」更壞，所以要連可疑結果一起交給回退解析。
    val explicitPort = url.substringAfter('@', "").substringBefore('#').substringBefore('?').contains(':')
    if (link == null || (link.port == 443 && !explicitPort)) {
        parseSnellLenient(url)?.let { return it }
        if (link == null) error(app.getString(R.string.invalid_snell_url))
    }

    return SnellBean().apply {
        serverAddress = link.host
        serverPort = link.port
        psk = link.username.unUrlSafe()
        name = link.fragment ?: ""

        link.queryParameter("version")?.toIntOrNull()?.let {
            version = it.coerceIn(1, 6)
        }
        link.queryParameter("userkey")?.let { userKey = it.unUrlSafe() }
        link.queryParameter("obfs-mode")?.let { obfsMode = it }
        link.queryParameter("obfs-host")?.let { obfsHost = it }
        link.queryParameter("reuse")?.let { reuse = it == "1" || it.equals("true", ignoreCase = true) }
        link.queryParameter("network")?.let { network = it }
        link.queryParameter("mode")?.let { mode = it }
    }
}

fun SnellBean.toUri(): String {
    val builder = StringBuilder("snell://")
    builder.append(psk.urlSafe()).append("@")
    builder.append(serverAddress).append(":").append(serverPort)

    val params = mutableListOf<String>()
    params.add("version=$version")
    if (userKey.isNotBlank()) params.add("userkey=${userKey.urlSafe()}")
    if (version == 6) {
        if (mode.isNotBlank() && mode != "default") params.add("mode=$mode")
    } else {
        if (obfsMode.isNotBlank()) params.add("obfs-mode=$obfsMode")
        if (obfsHost.isNotBlank()) params.add("obfs-host=$obfsHost")
    }
    if (reuse) params.add("reuse=true")
    if (network.isNotBlank()) params.add("network=$network")

    builder.append("?").append(params.joinToString("&"))

    if (name.isNotBlank()) {
        builder.append("#").append(name.urlSafe())
    }

    return builder.toString()
}

fun parseClashSnell(proxy: Map<String, Any?>): SnellBean {
    return SnellBean().apply {
        name = proxy["name"] as? String ?: ""
        serverAddress = proxy["server"] as? String ?: ""
        serverPort = (proxy["port"] as? Number)?.toInt() ?: 443
        psk = proxy["psk"] as? String ?: ""

        val clashVersion = ((proxy["version"] as? Number)?.toInt() ?: 4).coerceIn(1, 5)
        version = clashVersion

        reuse = proxy["reuse"] as? Boolean ?: false

        val udpEnabled = proxy["udp"] as? Boolean ?: false
        network = if (udpEnabled) {
            ""
        } else {
            "tcp"
        }

        // obfs-opts
        (proxy["obfs-opts"] as? Map<*, *>)?.let { obfsOpts ->
            obfsMode = obfsOpts["mode"] as? String ?: ""
            obfsHost = obfsOpts["host"] as? String ?: ""
        }
    }
}

/**
 * 手工掃描回退：標準解析器對非標準連結無能為力時用。
 * 取最後一個 `@` 切分（psk 可能含 `@` 之外的任意 base64url 字元），支援 IPv6 的
 * `[addr]:port` 寫法，並接受 `obfsmode` / `obfs-mode` 兩套參數名。
 */
private fun parseSnellLenient(url: String): SnellBean? {
    var rest = url.substringAfter("snell://", "")
    if (rest.isBlank() || rest == url) return null

    var name = ""
    val hash = rest.indexOf('#')
    if (hash >= 0) {
        name = Uri.decode(rest.substring(hash + 1))
        rest = rest.substring(0, hash)
    }
    var query = ""
    val mark = rest.indexOf('?')
    if (mark >= 0) {
        query = rest.substring(mark + 1)
        rest = rest.substring(0, mark)
    }
    val at = rest.lastIndexOf('@')
    if (at <= 0) return null
    val psk = rest.substring(0, at)
    val hostPort = rest.substring(at + 1)

    val host: String
    val port: Int
    if (hostPort.startsWith("[")) {
        val close = hostPort.indexOf(']')
        if (close < 0) return null
        host = hostPort.substring(1, close)
        port = hostPort.substring(close + 1).removePrefix(":").toIntOrNull() ?: return null
    } else {
        val colon = hostPort.lastIndexOf(':')
        if (colon < 0) return null
        host = hostPort.substring(0, colon)
        port = hostPort.substring(colon + 1).toIntOrNull() ?: return null
    }
    if (host.isBlank() || port !in 1..65535) return null

    val params = query.split('&').filter { it.isNotBlank() }
        .associate { it.substringBefore('=').lowercase() to it.substringAfter('=', "") }

    return SnellBean().apply {
        serverAddress = host
        serverPort = port
        this.psk = psk.unUrlSafe()
        this.name = name
        params["version"]?.toIntOrNull()?.let { version = it.coerceIn(1, 6) }
        params["userkey"]?.let { userKey = it.unUrlSafe() }
        (params["obfs-mode"] ?: params["obfsmode"])?.let { obfsMode = it }
        (params["obfs-host"] ?: params["obfshost"])?.let { obfsHost = it }
        params["reuse"]?.let { reuse = it == "1" || it.equals("true", ignoreCase = true) }
        params["network"]?.let { network = it }
        params["mode"]?.let { mode = it }
    }
}
