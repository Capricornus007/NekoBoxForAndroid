package io.nekohasekai.sagernet.ktx

import com.google.gson.JsonParser
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.Serializable
import io.nekohasekai.sagernet.fmt.byedpi.parseByeDPI
import io.nekohasekai.sagernet.fmt.http.parseHttp
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.juicity.parseJuicity
import io.nekohasekai.sagernet.fmt.masque.parseMasque
import io.nekohasekai.sagernet.fmt.masterdnsvpn.parseMasterDnsVpn
import io.nekohasekai.sagernet.fmt.naive.parseNaive
import io.nekohasekai.sagernet.fmt.olcrtc.parseOlcrtc
import io.nekohasekai.sagernet.fmt.parseUniversal
import io.nekohasekai.sagernet.fmt.shadowquic.parseShadowQUIC
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.shadowsocksr.parseShadowsocksR
import io.nekohasekai.sagernet.fmt.snell.parseSnell
import io.nekohasekai.sagernet.fmt.socks.parseSOCKS
import io.nekohasekai.sagernet.fmt.trojan.parseTrojan
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.trusttunnel.parseTrustTunnel
import io.nekohasekai.sagernet.fmt.tuic.parseTuic
import io.nekohasekai.sagernet.fmt.v2ray.parseV2Ray
import io.nekohasekai.sagernet.fmt.wireguard.parseWireGuardLink
import io.nekohasekai.sagernet.group.RawUpdater
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// JSON & Base64

fun JSONObject.toStringPretty(): String {
    return gson.toJson(JsonParser.parseString(this.toString()))
}

inline fun <reified T : Any> JSONArray.filterIsInstance(): List<T> {
    val list = mutableListOf<T>()
    for (i in 0 until this.length()) {
        if (this[i] is T) list.add(this[i] as T)
    }
    return list
}

inline fun JSONArray.forEach(action: (Int, Any) -> Unit) {
    for (i in 0 until this.length()) {
        action(i, this[i])
    }
}

inline fun JSONObject.forEach(action: (String, Any) -> Unit) {
    for (k in this.keys()) {
        action(k, this.get(k))
    }
}

fun isJsonObjectValid(j: Any): Boolean {
    if (j is JSONObject) return true
    if (j is JSONArray) return true
    try {
        JSONObject(j as String)
    } catch (ex: JSONException) {
        try {
            JSONArray(j)
        } catch (ex1: JSONException) {
            return false
        }
    }
    return true
}

// wtf hutool
fun JSONObject.getStr(name: String): String? {
    val obj = this.opt(name) ?: return null
    if (obj is String) {
        if (obj.isBlank()) {
            return null
        }
        return obj
    } else {
        return null
    }
}

fun JSONObject.getBool(name: String): Boolean? {
    return try {
        getBoolean(name)
    } catch (ignored: Exception) {
        null
    }
}

/**
 * Read a tri-state boolean out of a link query parameter.
 *
 * A missing (or blank / "auto") parameter returns null so the caller can leave the option
 * unwritten and keep the core's own default, which is not always false: sing-box runs
 * Hysteria2 and TUIC with udp_fragment enabled by default.
 */
fun String?.toTriStateBoolean(): Boolean? = when (this?.trim()?.lowercase()) {
    null, "", "auto", "default" -> null
    "1", "true", "on", "yes" -> true
    "0", "false", "off", "no" -> false
    else -> null
}

/** Same as [toTriStateBoolean] but for a JSON member, which may be a bool, a string or 0 / 1. */
fun JSONObject.getTriStateBool(name: String): Boolean? = when (val value = opt(name)) {
    is Boolean -> value
    is Number -> value.toInt() != 0
    is String -> value.toTriStateBoolean()
    else -> null
}

// name collision, nya
fun JSONObject.getIntNya(name: String): Int? {
    return try {
        getInt(name)
    } catch (ignored: Exception) {
        null
    }
}

fun String.decodeBase64UrlSafe(): String {
    return String(Util.b64Decode(this))
}

// Sub

class SubscriptionFoundException(val link: String) : RuntimeException()

fun String.linesNoComments(): List<String> {
    return removePrefix("\uFEFF")
        .split('\n')
        .map { it.trim() }
        .filterNot { it.startsWith("#") || it.isEmpty() }
}

// 「代理證據」＝有帳號密碼、端口不是協定預設值、或自帶查詢參數。面板習慣在訂閱末尾
// 放自己的官網／客服連結（https://panel.example/），那種網址路徑剛好是 "/"，而
// parseHttp 只檢查路徑，於是 promo 會被收成一顆沒有憑證、永遠測速超時的假節點。
// 這個閘門只用在訂閱解析路徑；使用者手動貼連結時照舊全收。
internal fun hasHttpProxyEvidence(link: String): Boolean {
    val httpUrl = link.toHttpUrlOrNull() ?: return false
    if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return true
    if (httpUrl.port != if (httpUrl.scheme == "https") 443 else 80) return true
    return !httpUrl.encodedQuery.isNullOrEmpty()
}

/**
 * 一行文字是不是「面板塞進訂閱的狀態／宣傳行」而非真節點名稱。只在訂閱解析用作過濾依據，
 * 所以走**高precision**：每個類別都要有明確的結構特徵，實測真節點名稱不得命中。
 *
 * 關鍵取捨：良心云這種面板會把 `套餐到期：…`／`剩余流量：… GB`／`客服👉 官网入口`
 * 做成**能解析、甚至會回應**的条目（用戶實測延遲 1024–1640ms），結構欄位查不出問題，只能靠名稱。
 * 為不误傷 `日本01 不限流量` 這種真節點，`流量` 一律要求再帶 `剩余／已用／重置／套餐`
 * 才算資訊行；`到期／过期／剩余／重置／套餐` 本身幾乎不會出現在真節點名裡，可獨立判定。
 *
 * 回傳原因字串（供計數回報），非資訊行回傳 null。名稱本身不寫進日誌——訂閱內容不得入日誌。
 */
internal fun promoNamePattern(name: String): String? {
    if (name.isEmpty()) return null
    // 名稱裡夹了完整網址（`客服👉 - https://…` 那種）——真節點名稱不會带 scheme。
    if (name.contains("://")) return "url-in-name"
    // 客服／官網／加群：這些是面板的對外入口，永遠不是節點。
    if (name.contains("客服") || name.contains("官網") || name.contains(
            "官网",
        )
    ) {
        return "support-site"
    }
    // 流量／到期／重置等用量行：`流量` 需搭配用量語境才判，避免 `不限流量` 節點被誤殺。
    val quotaWord = name.contains("到期") || name.contains("过期") ||
        name.contains("expire", ignoreCase = true) || name.contains("剩余") ||
        name.contains("已用") || name.contains("重置") || name.contains("套餐") ||
        name.contains("quota", ignoreCase = true) || name.contains("reset", ignoreCase = true) ||
        (
            name.contains("流量") && (
                name.contains("剩余") || name.contains(
                    "已用",
                ) || name.contains("重置") || name.contains("套餐")
                )
            )
    if (quotaWord) return "quota-info"
    if (name.contains(
            "訂閱",
        ) || name.contains("订阅") || name.contains("续费") || name.contains("subscription", ignoreCase = true)
    ) {
        return "subscription-info"
    }
    if (name.contains("t.me", ignoreCase = true)) return "telegram-link"
    return null
}

/**
 * 判定一顆已解析的節點是不是「訂閱資訊行／促銷条目」，回傳可計數的原因，否則 null。
 *
 * 兩類獨立原因（任一成立即入庫前剔除）：
 * 1. `no-endpoint`：協定解析雖然成功、但根本沒有可連線的位址（`serverAddress` 仍空）。
 *    必須在 [AbstractBean.initializeDefaultValues] 之前查——它會把空位址補成 `127.0.0.1:1080`。
 * 2. 名稱命中 [promoNamePattern]：用量／到期／官網／客服／訂閱等資訊行特徵。
 * 手動貼連結不經此閘門（呼叫端只在 `subscription == true` 時套用）。
 */
internal fun fakeSubscriptionNode(bean: AbstractBean): String? {
    if (bean.serverAddress.isNullOrEmpty()) return "no-endpoint"
    return promoNamePattern(bean.displayName())
}

suspend fun parseProxies(text: String, subscription: Boolean = false): List<AbstractBean> {
    val lines = text.linesNoComments()
    val links = lines.flatMap { it.split(' ') }
    val linksByLine = lines

    val entities = ArrayList<AbstractBean>()
    val entitiesByLine = ArrayList<AbstractBean>()
    // An http(s) link that fails to parse as an HTTP proxy is a subscription candidate.
    // Don't abort import immediately (issue #1128): a file may contain valid profile
    // links alongside a plain promo/Telegram URL. Remember the first candidate and only
    // treat the input as a subscription if NO profiles parsed at all.
    var subscriptionCandidate: String? = null
    var promoSkipped = 0

    fun rememberSubscriptionCandidate(link: String) {
        if (subscriptionCandidate == null) {
            val clashUrl = HttpUrl.Builder()
                .scheme("https")
                .host("install-config")
                .addQueryParameter("url", link)
                .build()
                .toString()
                .replaceFirst("https://", "clash://")
            // Defer: only thrown later if no profile links were parsed.
            subscriptionCandidate = clashUrl
        }
    }

    fun String.parseLink(entities: ArrayList<AbstractBean>) {
        if (startsWith("clash://install-config?") || startsWith("sn://subscription?")) {
            throw SubscriptionFoundException(this)
        }

        if (startsWith("sn://")) {
            Logs.d("Trying universal parser")
            runCatching {
                entities.add(parseUniversal(this))
            }.onFailure {
                Logs.w("Universal parser rejected input")
            }
        } else if (startsWith("socks://") || startsWith("socks4://") || startsWith("socks4a://") || startsWith(
                "socks5://",
            )
        ) {
            Logs.d("Trying SOCKS parser")
            runCatching {
                entities.add(parseSOCKS(this))
            }.onFailure {
                Logs.w("SOCKS parser rejected input")
            }
        } else if (matches("(http|https)://.*".toRegex())) {
            // 面板常在訂閱裡附上自己的官網／客服連結。這種網址的路徑剛好是 "/"，
            // parseHttp 會把它收成一顆沒有帳號密碼、永遠測速超時的假節點，所以訂閱
            // 解析要求「代理證據」（見 hasHttpProxyEvidence）；手動貼連結的路徑照舊全收。
            // 被擋下的網址仍走原本那條「說不定是訂閱網址」的路，行為跟解析失敗一致。
            if (subscription && !hasHttpProxyEvidence(this)) {
                promoSkipped++
                Logs.w("HTTP parser skipped input: no proxy evidence")
                rememberSubscriptionCandidate(this)
            } else {
                Logs.d("Trying HTTP parser")
                runCatching {
                    entities.add(parseHttp(this))
                }.onFailure {
                    Logs.w("HTTP parser rejected input")
                    rememberSubscriptionCandidate(this)
                }
            }
        } else if (startsWith("vmess://")) {
            Logs.d("Trying V2Ray parser")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w("V2Ray parser rejected input")
            }
        } else if (startsWith("vless://")) {
            Logs.d("Trying VLESS parser")
            runCatching {
                entities.add(parseV2Ray(this))
            }.onFailure {
                Logs.w("VLESS parser rejected input")
            }
        } else if (startsWith("trojan://")) {
            Logs.d("Trying Trojan parser")
            runCatching {
                entities.add(parseTrojan(this))
            }.onFailure {
                Logs.w("Trojan parser rejected input")
            }
        } else if (startsWith("trojan-go://")) {
            Logs.d("Trying Trojan-Go parser")
            runCatching {
                entities.add(parseTrojanGo(this))
            }.onFailure {
                Logs.w("Trojan-Go parser rejected input")
            }
        } else if (startsWith("ss://")) {
            Logs.d("Trying Shadowsocks parser")
            runCatching {
                entities.add(parseShadowsocks(this))
            }.onFailure {
                Logs.w("Shadowsocks parser rejected input")
            }
        } else if (startsWith("ssr://")) {
            Logs.d("Trying ShadowsocksR parser")
            runCatching {
                entities.add(parseShadowsocksR(this))
            }.onFailure {
                Logs.w("ShadowsocksR parser rejected input")
            }
        } else if (startsWith("naive+")) {
            Logs.d("Trying Naive parser")
            runCatching {
                entities.add(parseNaive(this))
            }.onFailure {
                Logs.w("Naive parser rejected input")
            }
        } else if (startsWith("hysteria://")) {
            Logs.d("Trying Hysteria 1 parser")
            runCatching {
                entities.add(parseHysteria1(this))
            }.onFailure {
                Logs.w("Hysteria 1 parser rejected input")
            }
        } else if (startsWith("hysteria2://") || startsWith("hy2://")) {
            Logs.d("Trying Hysteria 2 parser")
            runCatching {
                entities.add(parseHysteria2(this))
            }.onFailure {
                Logs.w("Hysteria 2 parser rejected input")
            }
        } else if (startsWith("tuic://")) {
            Logs.d("Trying TUIC parser")
            runCatching {
                entities.add(parseTuic(this))
            }.onFailure {
                Logs.w("TUIC parser rejected input")
            }
        } else if (startsWith("juicity://")) {
            Logs.d("Trying Juicity parser")
            runCatching {
                entities.add(parseJuicity(this))
            }.onFailure {
                Logs.w("Juicity parser rejected input")
            }
        } else if (startsWith("shadowquic://")) {
            Logs.d("Trying ShadowQUIC parser")
            runCatching {
                entities.add(parseShadowQUIC(this))
            }.onFailure {
                Logs.w("ShadowQUIC parser rejected input")
            }
        } else if (startsWith("tt://")) {
            Logs.d("Trying TrustTunnel parser")
            runCatching {
                entities.addAll(parseTrustTunnel(this))
            }.onFailure {
                Logs.w("TrustTunnel parser rejected input")
            }
        } else if (startsWith("masque://")) {
            Logs.d("Trying MASQUE parser")
            runCatching {
                entities.add(parseMasque(this))
            }.onFailure {
                Logs.w("MASQUE parser rejected input")
            }
        } else if (startsWith("snell://")) {
            Logs.d("Trying Snell parser")
            runCatching {
                entities.add(parseSnell(this))
            }.onFailure {
                Logs.w("Snell parser rejected input")
            }
        } else if (startsWith("anytls://")) {
            Logs.d("Trying AnyTLS parser")
            runCatching {
                entities.add(parseAnytls(this))
            }.onFailure {
                Logs.w("AnyTLS parser rejected input")
            }
        } else if (startsWith("masterdns://")) {
            Logs.d("Trying MasterDnsVPN parser")
            runCatching {
                entities.add(parseMasterDnsVpn(this))
            }.onFailure {
                Logs.w("MasterDnsVPN parser rejected input")
            }
        } else if (startsWith("olcrtc://")) {
            Logs.d("Trying olcRTC parser")
            runCatching {
                entities.add(parseOlcrtc(this))
            }.onFailure {
                Logs.w("olcRTC parser rejected input")
            }
        } else if (startsWith("byedpi://")) {
            Logs.d("Trying byeDPI parser")
            runCatching {
                entities.add(parseByeDPI(this))
            }.onFailure {
                Logs.w("byeDPI parser rejected input")
            }
        } else if (startsWith("wireguard://") || startsWith("wg://")) {
            Logs.d("Trying WireGuard parser")
            runCatching {
                entities.add(parseWireGuardLink(this))
            }.onFailure {
                Logs.w("WireGuard parser rejected input")
            }
        } else if (startsWith("vpn://")) {
            // AmneziaVPN share link: base64url of a WireGuard or AmneziaWG .conf text.
            Logs.d("Trying AmneziaVPN parser")
            runCatching {
                entities.addAll(RawUpdater.parseWireGuardConf(substringAfter("vpn://").decodeBase64UrlSafe()))
            }.onFailure {
                Logs.w("AmneziaVPN parser rejected input")
            }
        }
    }

    for (link in links) {
        link.parseLink(entities)
    }
    for (link in linksByLine) {
        link.parseLink(entitiesByLine)
    }
    // 訂閱路徑在入庫前剔除「資訊行／促銷行／沒有端點的假節點」（見 fakeSubscriptionNode）。
    // 這裡必須在 initializeDefaultValues()（下方對每顆都會跑）之前做：它會把空位址補成
    // 127.0.0.1:1080，之後就查不出 `no-endpoint` 了。手動貼連結（subscription=false）不過閘門。
    if (subscription) {
        val fakeReasons = LinkedHashMap<String, Int>()
        fun ArrayList<AbstractBean>.removeFakes() {
            val iterator = iterator()
            while (iterator.hasNext()) {
                val reason = fakeSubscriptionNode(iterator.next()) ?: continue
                fakeReasons[reason] = (fakeReasons[reason] ?: 0) + 1
                iterator.remove()
            }
        }
        // 逐空格與逐行兩份清單都要清，否則較長那份會把假節點帶進最終 result。
        entities.removeFakes()
        entitiesByLine.removeFakes()
        if (fakeReasons.isNotEmpty()) {
            // 只報「原因×次數」，名稱本身可能正是訂閱內容，不得進日誌。
            val total = fakeReasons.values.sum()
            val detail = fakeReasons.entries.joinToString(", ") { "${it.key}×${it.value}" }
            Logs.w("promo guard: skipped $total subscription info/promo row(s): $detail")
        }
    }
    // No profile links parsed but we saw an unparsable http(s) URL: treat the whole
    // input as a subscription link (single-URL paste / file). When profiles WERE found,
    // the stray URL is ignored so the profiles still import (issue #1128).
    if (entities.isEmpty() && entitiesByLine.isEmpty()) {
        subscriptionCandidate?.let { throw SubscriptionFoundException(it) }
    }
//    var isBadLink = false
    if (entities.onEach {
            it.initializeDefaultValues()
        }.size == entitiesByLine.onEach { it.initializeDefaultValues() }.size
    ) {
        run test@{
            entities.forEachIndexed { index, bean ->
                val lineBean = entitiesByLine[index]
                if (bean == lineBean && bean.displayName() != lineBean.displayName()) {
//                isBadLink = true
                    return@test
                }
            }
        }
    }
    if (promoSkipped > 0) {
        // 每個連結會被掃兩遍（逐空格與逐行），所以這個數是兩次掃描的合計，只作觀測用。
        Logs.w("promo guard: $promoSkipped http(s) link(s) rejected for lacking proxy evidence")
    }
    val result = if (entities.size > entitiesByLine.size) entities else entitiesByLine
    return result
}

fun <T : Serializable> T.applyDefaultValues(): T {
    initializeDefaultValues()
    return this
}
