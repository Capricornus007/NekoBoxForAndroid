package io.nekohasekai.sagernet.fmt.openvpn

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma

/**
 * `.ovpn` 文字 → [OpenVPNBean] → sing-box 的 `openvpn` **endpoint**。
 *
 * 為什麼做在這一層、而且先不碰編輯頁：核心那邊 OpenVPN 是 endpoint（跟 WireGuard 1.14 之後一樣），
 * `route/reference.go` 把 endpoint 的 tag 一起塞進 outbound 參照队列，所以
 * `route.rules[].outbound = <tag>` 就能用，不需要新的 inbound 型別。
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
    val bean: OpenVPNBean,
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
    "af-mtu", "cd", "status", "log", "log-append", "config", "remote-entry",
    "connect-retry", "connect-retry-max", "connect-retry-max-stat",
    "server-poll-timeout", "ifconfig", "route-gateway",
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
 * 沒給就在 `needsCredentials` 回 true，讓上層去問使用者（跟 Proton 自動登入那條線同一個政策）。
 *
 * 找不到任何 `remote`／`server` 時直接丟 `IllegalArgumentException`，讓上層顯示「這不是一份
 * 可匯入的 OpenVPN 設定」而不是生出一個連不上的剖面。
 */
fun parseOpenVPNConfig(
    text: String,
    username: String? = null,
    password: String? = null,
): OpenVPNParseResult {
    val warnings = mutableListOf<String>()
    val blocks = mutableMapOf<String, List<String>>()
    // 一定先澋預設值：下面一律用 isBlank()/直接讀欄位，bean 的欄位是 Java 平台型別，
    // 留 null 會在跑的時候才炸 NPE（編譯不會擋）。
    val bean = OpenVPNBean().apply { initializeDefaultValues() }
    val servers = mutableListOf<String>()
    var defaultPort = 0
    var remoteRandom = false
    var dev = "tun"
    var keyDirection: String? = null
    var authUserPass = false
    var tlsMode = true
    var controlWrapType: String? = null
    var pullFilters = mutableListOf<String>()
    var routes = mutableListOf<String>()
    var redirectGatewayFlags = mutableListOf<String>()

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

    // 2) 逐行指令。第一個 token 是指令名，其餘是值（值裡可能有空格，所以只切一刀）。
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
                // 三段一定寫滿（缺的用空段占位），否則 "host:udp" 這種會被讀成 port=udp。
                servers += listOf(
                    host,
                    port.takeIf { it > 0 }?.toString() ?: "",
                    proto ?: "",
                ).joinToString(":")
                if (proto != null) bean.transport = proto
            }

            "server" -> {
                val host = words.getOrNull(0) ?: continue
                val port = words.getOrNull(1)?.toIntOrNull() ?: defaultPort
                servers += listOf(host, port.takeIf { it > 0 }?.toString() ?: "", "").joinToString(":")
            }

            "remote-random" -> remoteRandom = true

            "port" -> defaultPort = words.getOrNull(0)?.toIntOrNull() ?: defaultPort

            "proto" -> words.getOrNull(0)?.let { mapProto(it)?.let { mapped -> bean.transport = mapped } }

            "dev" -> dev = words.getOrNull(0) ?: dev

            "dev-type" -> dev = words.getOrNull(0) ?: dev

            "key-direction" -> keyDirection = when (words.getOrNull(0)) {
                "0" -> "server"
                "1" -> "client"
                else -> null
            }

            "cipher" -> bean.cipher = value

            "data-ciphers", "ncp-ciphers" -> bean.dataCiphers = words.joinToString(",")

            "data-ciphers-fallback" -> bean.dataCiphersFallback = value

            "auth" -> bean.auth = value

            "compress" -> bean.compression = value

            "comp-lzo" -> bean.compressionLzo = words.getOrNull(0) ?: "yes"

            "allow-compression" -> bean.allowCompression = words.getOrNull(0) ?: "asym"

            "mssfix" -> words.getOrNull(0)?.toIntOrNull()?.let { bean.mssFix = it }

            "fragment" -> words.getOrNull(0)?.toIntOrNull()?.let { bean.fragment = it }

            "tun-mtu" -> words.getOrNull(0)?.toIntOrNull()?.let { bean.mtu = it }

            "replay-window" -> words.getOrNull(0)?.toIntOrNull()?.let { bean.replayWindow = it }

            "replay-window-time" -> putSeconds({ bean.replayWindowTime = it }, words.getOrNull(0))

            "topology" -> bean.topology = value

            "ping" -> putSeconds({ bean.pingInterval = it }, words.getOrNull(0))

            "ping-restart" -> putSeconds({ bean.pingRestart = it }, words.getOrNull(0))

            "hand-window" -> putSeconds({ bean.handshakeWindow = it }, words.getOrNull(0))

            "tls-timeout" -> putSeconds({ bean.tlsTimeout = it }, words.getOrNull(0))

            "reneg-sec", "renegotiate-sec" -> when (words.getOrNull(0)?.toIntOrNull()) {
                null -> Unit

                // OpenVPN 的 reneg-sec 0 是「閃掉週期性重握手」，不是「每 0 秒重握手」。
                0 -> bean.renegotiateDisabled = true

                else -> putSeconds({ bean.renegotiateInterval = it }, words.getOrNull(0))
            }

            "explicit-exit-notify" -> words.getOrNull(0)?.toIntOrNull()
                ?.let { bean.explicitExitNotify = it }

            "keepalive" -> {
                words.getOrNull(0)?.let { bean.pingInterval = "${it}s" }
                words.getOrNull(1)?.let { bean.pingRestart = "${it}s" }
            }

            "pull-filter" -> {
                val action = words.getOrNull(0) ?: ""
                val rest = value.substringAfter(' ').trim()
                if (action in listOf("accept", "ignore", "reject") && rest.isNotEmpty()) {
                    pullFilters += "$action ${rest.removeSurrounding("\"")}"
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
                routes += prefix
            }

            "redirect-gateway" -> {
                bean.redirectGateway = true
                redirectGatewayFlags += words
            }

            "redirect-private" -> bean.redirectPrivate = true

            "block-ipv6" -> bean.blockIPv6 = true

            "route-nopull" -> bean.routeNoPull = true

            "auth-user-pass" -> authUserPass = true

            "auth-retry" -> bean.authRetry = words.getOrNull(0) ?: "none"

            "static-challenge" -> {
                words.getOrNull(0)?.let { bean.staticChallenge = it }
                if (words.getOrNull(1)?.lowercase() in listOf("echo", "1")) {
                    bean.staticChallengeEcho = true
                }
            }

            "mode" -> if (words.getOrNull(0)?.lowercase() == "server") tlsMode = false

            "tls-server" -> tlsMode = false

            "verify-x509-name" -> {
                words.getOrNull(0)?.let { bean.tlsServerName = it }
                bean.tlsServerNameType = when (words.getOrNull(1)) {
                    "subject" -> "subject"
                    "name-prefix" -> "name-prefix"
                    else -> "name"
                }
            }

            "remote-cert-tls" -> words.getOrNull(0)?.let { bean.remoteCertificateTls = it }

            "ns-cert-type" -> words.getOrNull(0)?.let { bean.nsCertificateType = it }

            "peer-fingerprint" -> bean.peerFingerprint = words.joinToString(",")

            "remote-cert-ku" -> bean.remoteCertificateKu = words.joinToString(",")

            "remote-cert-eku" -> words.getOrNull(0)?.let { bean.remoteCertificateEku = it }

            "tls-cipher" -> bean.tlsCipher = value

            "ecdh-curve" -> bean.tlsGroups = value

            "tls-version-min" -> words.getOrNull(0)?.let { bean.tlsVersionMin = normalizeTlsVersion(it) }

            "tls-version-max" -> words.getOrNull(0)?.let { bean.tlsVersionMax = normalizeTlsVersion(it) }

            "secret" -> {
                tlsMode = false
                warnings += "忽略 $name：核心要金鑰內容而不是路徑，請把金鑰以 <static-key> 內嵌"
            }

            in IGNORED_DIRECTIVES -> Unit

            in UNSUPPORTED_DIRECTIVES -> warnings += "忽略 $name（核心沒有對應能力）"

            else -> warnings += "不認識的指令：$name"
        }
    }

    // 3) 區塊材料。
    blocks["ca"]?.let { bean.ca = it.joinToString("\n") }
    blocks["cert"]?.let { bean.clientCert = it.joinToString("\n") }
    blocks["key"]?.let { bean.clientKey = it.joinToString("\n") }
    // static-key 是 `mode: static_key` 那條路的金鑰，核心要它在頂層 `static_key`，
    // 放進 tls.control_wrap 會被當成 tls_auth/tls_crypt（client.go:189 那邊 mode 不對就直接紅）。
    blocks["static-key"]?.let { bean.staticKey = it.joinToString("\n") }
    val control = blocks["tls-crypt-v2"]?.let { "tls_crypt_v2" to it }
        ?: blocks["tls-crypt"]?.let { "tls_crypt" to it }
        ?: blocks["tls-auth"]?.let { "tls_auth" to it }
    if (control != null) {
        controlWrapType = control.first
        bean.controlWrapType = control.first
        bean.controlWrapKey = control.second.joinToString("\n")
        when (control.first) {
            // 核心規定 direction 只能給 tls_auth 用（client.go:229/234），tls_crypt 系列塞了會紅。
            "tls_auth" -> keyDirection?.let { bean.controlWrapDirection = it }

            else -> if (keyDirection != null) {
                warnings += "忽略 key-direction（核心只讓 tls_auth 帶 direction）"
            }
        }
    }

    // 4) 收尾：mode／servers／帳密。
    bean.mode = if (tlsMode) "tls" else "static_key"
    if (!tlsMode) keyDirection?.let { bean.keyDirection = it }
    bean.remoteRandom = remoteRandom
    if (servers.isEmpty()) {
        throw IllegalArgumentException("OpenVPN 設定裡找不到 remote／server，無法匯入")
    }
    bean.servers = servers.joinToString("\n")
    bean.servers.lineSequence().firstOrNull { it.isNotBlank() }?.split(":")?.let { parts ->
        bean.serverAddress = parts[0]
        bean.serverPort = parts.getOrNull(1)?.toIntOrNull() ?: 0
    }
    if (redirectGatewayFlags.isNotEmpty()) {
        bean.redirectGatewayFlags = redirectGatewayFlags.joinToString(",")
    }
    if (routes.isNotEmpty()) bean.routes = routes.joinToString("\n")
    if (pullFilters.isNotEmpty()) bean.pullFilters = pullFilters.joinToString("\n")
    if (dev != "tun") warnings += "dev $dev：核心只支援 tun，剖面會照 tun 走"
    if (authUserPass) {
        username?.takeIf { it.isNotBlank() }?.let { bean.username = it }
        password?.takeIf { it.isNotBlank() }?.let { bean.password = it }
    }
    if (controlWrapType == null && !tlsMode && bean.staticKey.isBlank()) {
        warnings += "static_key 模式需要 <static-key> 或 secret 檔，目前沒有金鑰材料"
    }

    return OpenVPNParseResult(
        bean,
        needsCredentials = authUserPass &&
            username.isNullOrBlank() && password.isNullOrBlank(),
        warnings = warnings,
    )
}

/**
 * 剖面 → 核心 endpoint。逐欄位搬，空字串／0／false 一律留 null（Gson 不序列化 null，
 * 而核心對未知欄位是直接報錯，所以這裡寧可少發也不能發錯名字）。
 */
fun buildSingBoxEndpointOpenVPNBean(bean: OpenVPNBean): SingBoxOptions.Endpoint_OpenVPNOptions = SingBoxOptions.Endpoint_OpenVPNOptions().apply {
    type = "openvpn-client"
    mode = bean.mode.ifBlank { null }
    network = bean.transport.ifBlank { null }
    val serverList = bean.servers.lineSequence().map(String::trim)
        .filter(String::isNotEmpty).toList()
    if (serverList.isNotEmpty()) {
        servers = serverList.map { spec ->
            val parts = spec.split(":")
            SingBoxOptions.Endpoint_OpenVPNRemoteOptions().apply {
                server = parts[0]
                parts.getOrNull(1)?.toIntOrNull()?.let { server_port = it }
                parts.getOrNull(2)?.takeIf { it.isNotBlank() }?.let { network = it }
            }
        }
    }
    if (bean.remoteRandom) remote_random = true
    bean.mtu?.takeIf { it > 0 }?.let { mtu = it }
    bean.topology.ifBlank { null }?.let { topology = it }
    bean.username.ifBlank { null }?.let { username = it }
    bean.password.ifBlank { null }?.let { password = it }
    bean.authRetry.ifBlank { null }?.let { auth_retry = it }
    bean.staticChallenge.ifBlank { null }?.let { static_challenge = it }
    if (bean.staticChallengeEcho) static_challenge_echo = true
    bean.staticKey.ifBlank { null }?.let { static_key = listOf(it) }
    bean.keyDirection.ifBlank { null }?.let { key_direction = it }
    // 核心的規矩：`cipher`（單一、OpenVPN 2.4 以前的名字）**只**能用在不走 TLS 的
    // 靜態金鑰模式；TLS 模式下要放 `data_ciphers`。實測（2026-10-27 手機）把 Proton
    // 那份含 tls-crypt 的剖面直接送 `cipher` 進去，box 啟動時直接
    // 「`cipher` is only supported in `static_key` mode; use `data_ciphers`…」。
    val ciphersFromSingle = bean.cipher.listByLineOrComma()
    val ciphersFromList = bean.dataCiphers.listByLineOrComma()
    if (bean.staticKey.isBlank()) {
        // 沒有靜態金鑰＝這條線走 TLS 控制通道，`cipher` 在核心裡是不合法的欄位名，
        // 併進 data_ciphers。
        (ciphersFromList + ciphersFromSingle).distinct()
            .takeIf { it.isNotEmpty() }?.let { data_ciphers = it }
    } else {
        bean.cipher.ifBlank { null }?.let { cipher = it }
        ciphersFromList.takeIf { it.isNotEmpty() }?.let { data_ciphers = it }
    }
    bean.dataCiphersFallback.ifBlank { null }?.let { data_ciphers_fallback = it }
    bean.auth.ifBlank { null }?.let { auth = it }
    bean.mssFix?.takeIf { it > 0 }?.let { mss_fix = it }
    bean.fragment?.takeIf { it > 0 }?.let { fragment = it }
    bean.replayWindow?.takeIf { it > 0 }?.let { replay_window = it }
    bean.replayWindowTime.ifBlank { null }?.let { replay_window_time = it }
    bean.compression.ifBlank { null }?.let { compression = it }
    bean.compressionLzo.ifBlank { null }?.let { compression_lzo = it }
    bean.allowCompression.ifBlank { null }?.let { allow_compression = it }
    if (bean.routeNoPull) route_no_pull = true
    bean.pullFilters.lineSequence().map(String::trim).filter(String::isNotEmpty)
        .map { line ->
            SingBoxOptions.Endpoint_OpenVPNPullFilterOptions().apply {
                action = line.substringBefore(' ')
                text = line.substringAfter(' ', "").trim()
            }
        }.toList().takeIf { it.isNotEmpty() }?.let { pull_filters = it }
    bean.routes.listByLineOrComma().takeIf { it.isNotEmpty() }?.let { routes = it }
    if (bean.redirectGateway) redirect_gateway = true
    bean.redirectGatewayFlags.listByLineOrComma().takeIf { it.isNotEmpty() }
        ?.let { redirect_gateway_flags = it }
    if (bean.redirectPrivate) redirect_private = true
    if (bean.blockIPv6) block_ipv6 = true
    bean.pingInterval.ifBlank { null }?.let { ping_interval = it }
    bean.pingRestart.ifBlank { null }?.let { ping_restart = it }
    bean.handshakeWindow.ifBlank { null }?.let { handshake_window = it }
    bean.tlsTimeout.ifBlank { null }?.let { tls_timeout = it }
    bean.renegotiateInterval.ifBlank { null }?.let { renegotiate_interval = it }
    if (bean.renegotiateDisabled) renegotiate_disabled = true
    bean.explicitExitNotify?.takeIf { it > 0 }?.let { explicit_exit_notify = it }

    val tlsOptions = SingBoxOptions.Endpoint_OpenVPNClientTLSOptions()
    bean.ca.ifBlank { null }?.let { tlsOptions.certificate = listOf(it) }
    bean.clientCert.ifBlank { null }?.let { tlsOptions.client_certificate = listOf(it) }
    bean.clientKey.ifBlank { null }?.let { tlsOptions.client_key = listOf(it) }
    bean.tlsServerName.ifBlank { null }?.let { tlsOptions.server_name = it }
    bean.tlsServerNameType.ifBlank { null }?.let { tlsOptions.server_name_type = it }
    bean.remoteCertificateTls.ifBlank { null }?.let { tlsOptions.remote_certificate_tls = it }
    bean.nsCertificateType.ifBlank { null }?.let { tlsOptions.ns_certificate_type = it }
    bean.peerFingerprint.listByLineOrComma().takeIf { it.isNotEmpty() }
        ?.let { tlsOptions.peer_fingerprint = it }
    bean.remoteCertificateKu.listByLineOrComma().takeIf { it.isNotEmpty() }
        ?.let { tlsOptions.remote_certificate_ku = it }
    bean.remoteCertificateEku.ifBlank { null }?.let { tlsOptions.remote_certificate_eku = it }
    bean.tlsCipher.ifBlank { null }?.let { tlsOptions.cipher = it }
    bean.tlsGroups.ifBlank { null }?.let { tlsOptions.groups = it }
    bean.tlsVersionMin.ifBlank { null }?.let { tlsOptions.version_min = it }
    bean.tlsVersionMax.ifBlank { null }?.let { tlsOptions.version_max = it }
    bean.controlWrapType.ifBlank { null }?.let { type ->
        tlsOptions.control_wrap = SingBoxOptions.Endpoint_OpenVPNControlWrapOptions().apply {
            this.type = type
            bean.controlWrapKey.ifBlank { null }?.let { key = listOf(it) }
            // tls_crypt／tls_crypt_v2 帶 direction 會被核心拒絕，這裡連發都不發。
            if (type == "tls_auth") {
                bean.controlWrapDirection.ifBlank { null }?.let { direction = it }
            }
        }
    }
    if (tlsOptions.asMap().isNotEmpty()) tls = tlsOptions
}

/** 只接受純數字的秒數，避免把空字串或非數字拼成 "s" 這種核心解析不過的 Duration。 */
private fun putSeconds(assigner: (String) -> Unit, raw: String?) {
    val seconds = raw?.toIntOrNull() ?: return
    assigner("${seconds}s")
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
