package io.nekohasekai.sagernet.fmt.openvpn

import org.json.JSONArray
import org.json.JSONObject

/**
 * `.ovpn` 文字 → sing-box 的 `openvpn` **endpoint** 物件。
 *
 * 為什麼做在這一層、而且先不碰 Bean／編輯頁：核心那邊 OpenVPN 是 endpoint（跟 WireGuard
 * 1.14 之後一樣），`route/reference.go:172` 把 endpoint 的 tag 一起塞進 outbound 參照队列，
 * 所以 `route.rules[].outbound = <tag>` 就能用，不需要新的 inbound 型別。先把「解析＋產生
 * 正確 JSON」這個最難的純函式站穩並用單元測試釘住，UI 接線是另一筆。
 *
 * 欄位對應全部查過核心原始碼，不是照 OpenVPN 文件猜：
 *  - `option/openvpn.go` 的 `OpenVPNClientEndpointOptions`／`OpenVPNOutboundTLSOptions`
 *    決定欄位名與 enum 可接受值；
 *  - `protocol/openvpn/client.go:189` 明確拒絕在 TLS 模式用 `static_key`＋`key_direction`
 *    （那兩個是 `mode: static_key` 專用的），`tls-auth` 必須走 `tls.control_wrap`
 *    （`type: tls_auth` ＋ `direction: server|client`），`client.go:229/234` 還規定
 *    `direction` 只有 `tls_auth` 能用。所以這裡把 OpenVPN 的 `key-direction 0/1`
 *    翻成核心的 `server`／`client`，而不是照抄數字。
 */
class OpenVPNParseResult(
    val endpoint: JSONObject,
    val needsCredentials: Boolean,
    val warnings: List<String>,
)

/** 會直接吃掉、不需要提醒使用者的指令（OpenVPN 客戶端預設行為或對核心無意義）。 */
private val IGNORED_DIRECTIVES = setOf(
    "client", "nobind", "persist-key", "persist-tun", "float", "tls-client",
    "verb", "mute", "silent-retry", "resolv-retry", "syslog", "pid",
    "dev-node",
)

/** 核心沒有對應能力、必須讓使用者知道我們丟了東西的指令。 */
private val UNSUPPORTED_DIRECTIVES = setOf(
    "up", "down", "route-up", "pre-up", "down-pre", "route-pre-down",
    "script-security", "plugin", "ie-proxy", "win-sdk", "dhcp-option",
    "wins-server", "nbgt", "nttp", "setenv", "setenv-opt", "crl-verify",
    "tls-cert-profile", "fragsize", "memstats", "gc-reload", "rcvbuf", "sndbuf",
    "link-mtu", "txqueuelen", "mlock", "user", "group", "chroot",
    "af-mtu", "sndbuf", "rcvbuf", "cd", "status", "log", "log-append", "config", "remote-entry", "connect-retry",
    "connect-retry-max", "connect-retry-max-stat", "server-poll-timeout",
)

private val MATERIAL_BLOCKS = listOf(
    "ca",
    "cert",
    "key",
    "tls-auth",
    "tls-crypt",
    "tls-crypt-v2",
    "static-key",
)

/**
 * 解析一份 `.ovpn`。[username]／[password] 是 Proton 那類 `auth-user-pass` 剖面需要的帳號密碼，
 * 沒給就在 `needsCredentials` 回 true，讓上層去問使用者（我們不存密碼，跟 Proton 自動登入
 * 那條線同一個政策）。
 *
 * 找不到任何 `remote`／`server` 時直接丟 `IllegalArgumentException`，讓上層顯示「這不是一份
 * 可匯入的 OpenVPN 設定」而不是生出一個連不上的剖面。
 */
fun parseOpenVPNConfig(
    text: String,
    username: String? = null,
    password: String? = null,
    tag: String = "openvpn",
): OpenVPNParseResult {
    val warnings = mutableListOf<String>()
    val blocks = mutableMapOf<String, List<String>>()
    val servers = JSONArray()
    var defaultPort = 0
    var network: String? = null
    var remoteRandom = false
    var dev = "tun"
    var keyDirection: String? = null
    var authUserPass = false
    var controlWrapType: String? = null
    var tlsMode = true
    val tls = JSONObject()
    val endpoint = JSONObject().put("type", "openvpn").put("tag", tag)

    // 1) 先摘掉 <ca>…</ca> 這類的多行區塊，剩下的才是逐行的指令。
    val lines = mutableListOf<String>()
    var openBlock: String? = null
    var blockBody = mutableListOf<String>()
    for (raw in text.lineSequence()) {
        val line = raw.trim()
        val current = openBlock
        if (current == null) {
            val start = MATERIAL_BLOCKS.firstOrNull { line.equals("<$it>", ignoreCase = true) }
            if (start != null) {
                openBlock = start
                blockBody = mutableListOf()
            } else {
                lines += raw
            }
        } else {
            if (line.equals("</$current>", ignoreCase = true)) {
                blocks[current] = blockBody.toList()
                openBlock = null
            } else {
                blockBody += raw
            }
        }
    }
    require(openBlock == null) { "OpenVPN 設定有沒閉合的區塊：<$openBlock>" }

    // 2) 逐行指令。第一个 token 是指令名，其餘是值（值裡可能有空格，所以只切一刀）。
    for (raw in lines) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
        val name: String
        val value: String
        val space = line.indexOfFirst { it == ' ' || it == '\t' }
        if (space < 0) {
            name = line
            value = ""
        } else {
            name = line.substring(0, space)
            value = line.substring(space + 1).trim()
        }
        val words = if (value.isEmpty()) emptyList() else value.split(Regex("[ \\t]+"))

        when (name.lowercase()) {
            "remote" -> {
                val host = words.getOrNull(0) ?: continue
                val port = words.getOrNull(1)?.toIntOrNull() ?: defaultPort
                val proto = words.getOrNull(2)?.let { mapProto(it) }
                servers.put(
                    JSONObject()
                        .put("server", host)
                        .apply { if (port > 0) put("server_port", port) }
                        .apply { if (proto != null) put("network", proto) },
                )
                if (proto != null) network = proto
            }

            "server" -> {
                val host = words.getOrNull(0) ?: continue
                val port = words.getOrNull(1)?.toIntOrNull() ?: defaultPort
                servers.put(
                    JSONObject().put("server", host)
                        .apply { if (port > 0) put("server_port", port) },
                )
            }

            "remote-random" -> remoteRandom = true

            "port" -> defaultPort = words.getOrNull(0)?.toIntOrNull() ?: defaultPort

            "proto" -> words.getOrNull(0)?.let { network = mapProto(it) }

            "dev" -> dev = words.getOrNull(0) ?: dev

            "dev-type" -> dev = words.getOrNull(0) ?: dev

            "key-direction" -> keyDirection = when (words.getOrNull(0)) {
                "0" -> "server"
                "1" -> "client"
                else -> null
            }

            "cipher" -> endpoint.put("cipher", value)

            "data-ciphers", "ncp-ciphers" -> endpoint.put("data_ciphers", JSONArray(words))

            "data-ciphers-fallback" -> endpoint.put("data_ciphers_fallback", value)

            "auth" -> endpoint.put("auth", value)

            "compress" -> endpoint.put("compression", value)

            "comp-lzo" -> endpoint.put("compression_lzo", if (words.isEmpty()) "yes" else words[0])

            "allow-compression" -> endpoint.put("allow_compression", words.getOrNull(0) ?: "asym")

            "mssfix" -> words.getOrNull(0)?.toIntOrNull()?.let { endpoint.put("mss_fix", it) }

            "fragment" -> words.getOrNull(0)?.toIntOrNull()?.let { endpoint.put("fragment", it) }

            "tun-mtu" -> words.getOrNull(0)?.toIntOrNull()?.let { endpoint.put("mtu", it) }

            "topology" -> endpoint.put("topology", value)

            "ping" -> putSeconds(endpoint, "ping_interval", words.getOrNull(0))

            "ping-restart" -> putSeconds(endpoint, "ping_restart", words.getOrNull(0))

            "hand-window" -> putSeconds(endpoint, "handshake_window", words.getOrNull(0))

            "tls-timeout" -> putSeconds(endpoint, "tls_timeout", words.getOrNull(0))

            "reneg-sec", "renegotiate-sec" -> when (words.getOrNull(0)?.toIntOrNull()) {
                null -> Unit

                // OpenVPN 的 reneg-sec 0 是「关掉週期性重握手」，不是「每 0 秒重握手」。
                0 -> endpoint.put("renegotiate_disabled", true)

                else -> putSeconds(endpoint, "renegotiate_interval", words.getOrNull(0))
            }

            "explicit-exit-notify" -> words.getOrNull(0)?.toIntOrNull()
                ?.let { endpoint.put("explicit_exit_notify", it) }

            "keepalive" -> {
                words.getOrNull(0)?.let { endpoint.put("ping_interval", "${it}s") }
                words.getOrNull(1)?.let { endpoint.put("ping_restart", "${it}s") }
            }

            "pull-filter" -> {
                val action = words.getOrNull(0) ?: ""
                val rest = value.substringAfter(' ').trim()
                if (action in listOf("accept", "ignore", "reject") && rest.isNotEmpty()) {
                    appendPullFilter(endpoint, action, rest.removeSurrounding("\""))
                }
            }

            "route" -> {
                val target = words.getOrNull(0) ?: continue
                val mask = words.getOrNull(1)
                val prefix = when {
                    target.contains('/') -> target
                    mask != null -> "$target/${maskToPrefix(mask)}"
                    target.contains(':') -> "$target/128"
                    else -> "$target/32"
                }
                appendListable(endpoint, "routes", prefix)
            }

            "redirect-gateway" -> {
                endpoint.put("redirect_gateway", true)
                if (words.isNotEmpty()) endpoint.put("redirect_gateway_flags", JSONArray(words))
            }

            "redirect-private" -> endpoint.put("redirect_private", true)

            "block-ipv6" -> endpoint.put("block_ipv6", true)

            "route-nopull" -> endpoint.put("route_no_pull", true)

            "auth-user-pass" -> authUserPass = true

            "auth-retry" -> endpoint.put("auth_retry", words.getOrNull(0) ?: "none")

            "static-challenge" -> {
                words.getOrNull(0)?.let { endpoint.put("static_challenge", it) }
                if (words.getOrNull(1)?.lowercase() in listOf("echo", "1")) {
                    endpoint.put("static_challenge_echo", true)
                }
            }

            "mode" -> if (words.getOrNull(0)?.lowercase() == "server") tlsMode = false

            "tls-server" -> tlsMode = false

            "verify-x509-name" -> {
                words.getOrNull(0)?.let { tls.put("server_name", it) }
                when (words.getOrNull(1)) {
                    "subject" -> tls.put("server_name_type", "subject")
                    "name-prefix" -> tls.put("server_name_type", "name-prefix")
                    else -> tls.put("server_name_type", "name")
                }
            }

            "remote-cert-tls" -> words.getOrNull(0)?.let { tls.put("remote_certificate_tls", it) }

            "ns-cert-type" -> words.getOrNull(0)?.let { tls.put("ns_certificate_type", it) }

            "peer-fingerprint" -> tls.put("peer_fingerprint", JSONArray(words))

            "remote-cert-ku" -> tls.put("remote_certificate_ku", JSONArray(words))

            "remote-cert-eku" -> words.getOrNull(0)?.let { tls.put("remote_certificate_eku", it) }

            "tls-cipher" -> tls.put("cipher", value)

            "ecdh-curve" -> tls.put("groups", value)

            "tls-version-min" -> words.getOrNull(0)?.let { tls.put("version_min", normalizeTlsVersion(it)) }

            "tls-version-max" -> words.getOrNull(0)?.let { tls.put("version_max", normalizeTlsVersion(it)) }

            "secret" -> {
                tlsMode = false
                if (value.isNotEmpty()) endpoint.put("static_key_path", value)
            }

            in IGNORED_DIRECTIVES -> Unit

            in UNSUPPORTED_DIRECTIVES -> warnings += "忽略 $name（核心沒有對應能力）"

            else -> warnings += "不認識的指令：$name"
        }
    }

    // 3) 區塊材料 → tls.* 與 control_wrap。
    blocks["ca"]?.let { tls.put("certificate", JSONArray(listOf(it.joinToString("\n")))) }
    blocks["cert"]?.let { tls.put("client_certificate", JSONArray(listOf(it.joinToString("\n")))) }
    blocks["key"]?.let { tls.put("client_key", JSONArray(listOf(it.joinToString("\n")))) }
    // static-key 是 `mode: static_key` 那條路的金鑰，核心要它在頂層 `static_key`，
    // 放進 tls.control_wrap 會被当成 tls_auth/tls_crypt（client.go:189 那邊 mode 不對就直接紅）。
    blocks["static-key"]?.let { endpoint.put("static_key", JSONArray(listOf(it.joinToString("\n")))) }
    val control = blocks["tls-crypt-v2"]?.let { "tls_crypt_v2" to it }
        ?: blocks["tls-crypt"]?.let { "tls_crypt" to it }
        ?: blocks["tls-auth"]?.let { "tls_auth" to it }
    if (control != null) {
        controlWrapType = control.first
        val wrap = JSONObject()
            .put("type", control.first)
            .put("key", JSONArray(listOf(control.second.joinToString("\n"))))
        when (control.first) {
            // 核心規定 direction 只能給 tls_auth 用（client.go:229/234），tls_crypt 系列塞了會紅。
            "tls_auth" -> keyDirection?.let { wrap.put("direction", it) }

            else -> if (keyDirection != null) {
                warnings += "忽略 key-direction（核心只讓 tls_auth 帶 direction）"
            }
        }
        tls.put("control_wrap", wrap)
    }

    // 4) 收尾：mode／network／servers／帳密。
    if (tlsMode) {
        endpoint.put("mode", "tls")
    } else {
        endpoint.put("mode", "static_key")
        keyDirection?.let { endpoint.put("key_direction", it) }
    }
    network?.let { endpoint.put("network", it) }
    if (remoteRandom) endpoint.put("remote_random", true)
    if (servers.length() == 0) {
        throw IllegalArgumentException("OpenVPN 設定裡找不到 remote／server，無法匯入")
    }
    endpoint.put("servers", servers)
    if (tls.length() > 0) endpoint.put("tls", tls)
    if (dev != "tun") warnings += "dev $dev：核心只支援 tun，剖面會照 tun 走"
    if (authUserPass) {
        username?.takeIf { it.isNotBlank() }?.let { endpoint.put("username", it) }
        password?.takeIf { it.isNotBlank() }?.let { endpoint.put("password", it) }
    }
    if (controlWrapType == null && !tlsMode) {
        warnings += "static_key 模式需要 <static-key> 或 secret 檔，目前沒有金鑰材料"
    }

    return OpenVPNParseResult(
        endpoint,
        needsCredentials = authUserPass &&
            username.isNullOrBlank() && password.isNullOrBlank(),
        warnings = warnings,
    )
}

/** 只接受純數字的秒數，避免把空字串或非數字拼成 "s" 這種核心解析不過的Duration。 */
private fun putSeconds(endpoint: JSONObject, key: String, raw: String?) {
    val seconds = raw?.toIntOrNull() ?: return
    endpoint.put(key, "${seconds}s")
}

private fun mapProto(raw: String): String? = when (raw.lowercase()) {
    "udp" -> "udp"
    "udp4" -> "udp4"
    "udp6" -> "udp6"
    "tcp", "tcp-client" -> "tcp"
    "tcp4" -> "tcp4"
    "tcp6" -> "tcp6"
    else -> null
}

private fun normalizeTlsVersion(raw: String): String = when (raw) {
    "1.0.0" -> "1.0"
    "1.1.0" -> "1.1"
    "1.2.0" -> "1.2"
    "1.3.0" -> "1.3"
    else -> raw
}

private fun maskToPrefix(mask: String): Int = mask.split('.').takeIf { it.size == 4 }
    ?.fold(0) { acc, part -> acc + Integer.bitCount(part.toIntOrNull() ?: 0) }
    ?: 32

private fun appendPullFilter(endpoint: JSONObject, action: String, text: String) {
    val array = endpoint.optJSONArray("pull_filters") ?: JSONArray().also {
        endpoint.put("pull_filters", it)
    }
    array.put(JSONObject().put("action", action).put("text", text))
}

/** `badoption.Listable` 允許字串或陣列，這裡一律用陣列累加，避免同一鍵被覆寫。 */
private fun appendListable(endpoint: JSONObject, key: String, value: String) {
    val array = endpoint.optJSONArray(key) ?: JSONArray().also { endpoint.put(key, it) }
    array.put(value)
}
