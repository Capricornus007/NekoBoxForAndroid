package io.nekohasekai.sagernet.fmt.proton

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class ProtonNode(
    val id: String,
    val name: String,
    val penalty: Double,
    val tier: Int,
    val supportsIPv6: Boolean,
    val country: String,
    val city: String,
    val endpoint: String,
    val publicKey: String,
    val port: Int,
    // Proton's own utilisation number, 0-100. -1 means "the payload did not carry
    // it", which is what every cache file written before this field existed looks
    // like; treating absent as 0 would rank those nodes as the emptiest there are.
    val load: Int = -1,
    // Proton 自己的「這台好不好」排名分數，越小越好：它的客戶端在 ServerManager2.kt
    // 寫著「Sorted by score (best at front)」，而拿不到分數時的佔位值是 1_000_000.0。
    // -1 也是「payload 沒帶這個欄位」（舊快取）的意思，不能當成 0，那是「最好」。
    // 兩個新欄位都放在最後是必要的：既有呼叫端是用位置建這個物件的。
    val score: Double = -1.0,
    // 這台能走哪些協定，正規化小寫：wireguard／stealth／openvpn／protun／smart，
    // 上游不認識的鍵名原樣小寫送出（sidecar 的 protocolCaps）。空清單只代表「這份 payload
    // 沒帶這欄」＝舊 sidecar 寫的舊快取，不能解讀成「什麼都不支援」，也不能憑 wgPublicKey
    // 補一個 wireguard 進去：那正是「拿推論冒充資料」，清單會假裝筛對了。
    val protocols: List<String> = emptyList(),
    // Proton 的 Secure Core（流量先過中繼再到出口）。證據是官方 APK 反編譯出來的
    // Server.java：isSecureCoreServer() => d0(features, 1)，跟 isIPv6Supported()
    // 的 d0(features, 16) 同一個 features 位元遮罩、不同位。
    val secureCore: Boolean = false,
) {
    // 空閒度是他在清單上真正要比的東西，負載只是它的反面。
    val idlePercent: Int? get() = load.takeIf { it in 0..100 }?.let { 100 - it }

    // wgPublicKey 有值才能直接建 WireGuard 剖面；OpenVPN／ProTun 那條要的是 .ovpn 材料，
    // 目前是「列得出來、點下去會告知還沒接」的狀態。
    val hasWireGuardKey: Boolean get() = publicKey.isNotEmpty()

    /**
     * 「這台能不能走 [want]」。協定清單有就以清單為準；清單是空的（＝舊 sidecar 寫的舊快取）
     * 時只對 wireguard 退化到 wgPublicKey 判斷——這不是猜：上游 nodes.go 的 protocolCaps
     * 本來就是 `if d.X25519PublicKey != "" { seen["wireguard"] = true }`，同一份資料、
     * 同一條推導。其他協定不能這樣補，缺資料時寧可篩不出來，也不要宣稱支援。
     */
    fun canUse(want: String): Boolean = when {
        protocols.isNotEmpty() -> protocols.any { it.equals(want, ignoreCase = true) }
        want == PROTOCOL_WIREGUARD -> hasWireGuardKey
        else -> false
    }

    companion object {
        const val PROTOCOL_WIREGUARD = "wireguard"
        const val PROTOCOL_STEALTH = "stealth"
        const val PROTOCOL_OPENVPN = "openvpn"
        const val PROTOCOL_PROTUN = "protun"
        const val PROTOCOL_SMART = "smart"

        // 下拉的固定順序，跟 sidecar 的 protocolOrder 一致；來源是官方 APK 的
        // Transmission/VPN 鍵名（WireGuardTLS 之類拆出 stealth）。
        val PROTOCOL_ORDER = listOf(
            PROTOCOL_WIREGUARD,
            PROTOCOL_STEALTH,
            PROTOCOL_OPENVPN,
            PROTOCOL_PROTUN,
            PROTOCOL_SMART,
        )

        // 協定名是專有名詞，不進語系檔（全局規則 35）；上游不認識的鍵原樣顯示，
        // 那種情況寧可有個看得見的怪名字，也不要把它翻譯成某個已知的協定。
        fun protocolLabel(key: String) = when (key.lowercase()) {
            PROTOCOL_WIREGUARD -> "WireGuard"
            PROTOCOL_STEALTH -> "Stealth"
            PROTOCOL_OPENVPN -> "OpenVPN"
            PROTOCOL_PROTUN -> "ProTun"
            PROTOCOL_SMART -> "Smart"
            else -> key
        }
    }
}

sealed class ProtonOutcome {
    class Success(val json: String) : ProtonOutcome()

    // The sidecar reports failures as JSON on stdout with a non-zero exit, so the
    // stdout is carried here too; stderr alone only has the process message.
    class Failed(val message: String, val exitCode: Int = -1, val stdout: String = "") : ProtonOutcome()
}

class ProtonLoginState(
    val ok: Boolean,
    val uid: String = "",
    val twoFactorRequired: Boolean = false,
    val reason: String = "",
    val error: String = "",
    // Proton 要人先解 CAPTCHA（HTTP 422、Code 9001）時，sidecar 會把回應 Details 裡的
    // 起點 token 與可用方法一起交出來，UI 才能直接在 App 內開驗證頁面。
    // 這是短效憑證：只在記憶體裡傳，不寫 Logs、不寫檔。
    val captchaToken: String = "",
    val captchaMethods: List<String> = emptyList(),
)

// captcha-begin 的結果：兩個能在 WebView 裡載的網址。
class ProtonCaptchaChallenge(
    val ok: Boolean,
    val token: String = "",
    val methods: List<String> = emptyList(),
    val solveUrl: String = "",
    val captchaUrl: String = "",
    val messageUrl: String = "",
    val reason: String = "",
    val error: String = "",
) {
    // 首選官方驗證應用（支援 captcha 以外的方法），退而求其次才是 API 自己的驗證頁。
    val inAppUrl: String get() = solveUrl.ifEmpty { captchaUrl }
    val usable: Boolean get() = ok && inAppUrl.isNotEmpty()
}

// captcha-solve 的結果：可以直接餵回 login 的一對值。
class ProtonCaptchaAnswer(
    val ok: Boolean,
    val token: String = "",
    val type: String = "",
    val reason: String = "",
    val error: String = "",
)

class ProtonOvpnState(
    val ok: Boolean,
    // sidecar 拼好的自包含 .ovpn（材料全部 inline），交給 parseOpenVPNConfig 生剖面。
    val config: String = "",
    val host: String = "",
    val port: Int = 0,
    val proto: String = "",
    // 憑證到期（epoch 秒）：Proton 的客戶端憑證不是永久的，到期要重簽。
    val expiresAt: Long = 0L,
    val error: String = "",
    val code: String = "",
)

class ProtonRefreshState(
    val ok: Boolean,
    // 真的換到新一組 token（false＋ok＝本來就沒到期，什麼都不用做）。
    val renewed: Boolean = false,
    val error: String = "",
    val code: String = "",
)

class ProtonTierState(
    val ok: Boolean,
    // 帳號可用的節點層級上限：免費＝1、Plus＝2、Visionary/Business＝3；0＝不知道。
    val maxTier: Int = 0,
    val plan: String = "",
    val error: String = "",
    val code: String = "",
)

class ProtonNodesState(
    val ok: Boolean,
    val nodes: List<ProtonNode> = emptyList(),
    val dropped: Int = 0,
    val error: String = "",
    // sidecar 給機器的穩定代碼，跟 error 那句人話分開：跳登入頁與顯示本地化文案都要靠它，
    // 拿英文句子比對的話 Proton 一改措辭這條路就斷。目前只有 session_expired。
    val code: String = "",
    // 原始 payload 只從網路那條路帶回來，給磁碟快取用：存解析後的物件會逼這裡
    // 再實作一套序列化，而讀回來時又得走第二套解析，兩套不一致最難查。
    val raw: String = "",
    // 這次清單是按第幾層過濾過的（0＝沒過濾）。清單頁拿它標「已按免費層過濾」，
    // 免得用戶把「少掉的節點」看成「壞掉的回應」。
    val maxTier: Int = 0,
)

// Parsing is kept free of process and Android types so it can be covered by JVM
// tests; the sidecar's stdout is the only input.
object ProtonJson {

    // A non-zero exit still carries a machine-readable report on stdout, so callers
    // need to know whether that is parseable before falling back to stderr text.
    fun looksLikeJson(text: String): Boolean = text.trim().startsWith("{")

    fun parseLogin(text: String): ProtonLoginState {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ProtonLoginState(false, error = "sidecar returned no JSON")
        return ProtonLoginState(
            ok = obj.optBoolean("ok"),
            uid = obj.optString("uid", ""),
            twoFactorRequired = obj.optBoolean("twoFactorRequired"),
            reason = obj.optString("reason", ""),
            error = obj.optString("error", ""),
            captchaToken = obj.optString("hvToken", ""),
            captchaMethods = stringList(obj.optJSONArray("hvMethods")),
        )
    }

    fun parseCaptchaBegin(text: String): ProtonCaptchaChallenge {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ProtonCaptchaChallenge(false, error = "sidecar returned no JSON")
        return ProtonCaptchaChallenge(
            ok = obj.optBoolean("ok"),
            token = obj.optString("token", ""),
            methods = stringList(obj.optJSONArray("methods")),
            solveUrl = obj.optString("solveURL", ""),
            captchaUrl = obj.optString("captchaURL", ""),
            messageUrl = obj.optString("messageURL", ""),
            reason = obj.optString("reason", ""),
            error = obj.optString("error", ""),
        )
    }

    fun parseCaptchaSolve(text: String): ProtonCaptchaAnswer {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ProtonCaptchaAnswer(false, error = "sidecar returned no JSON")
        return ProtonCaptchaAnswer(
            ok = obj.optBoolean("ok"),
            token = obj.optString("token", ""),
            type = obj.optString("type", ""),
            reason = obj.optString("reason", ""),
            error = obj.optString("error", ""),
        )
    }

    private fun stringList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out
    }

    fun parseRefresh(text: String): ProtonRefreshState {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ProtonRefreshState(false, error = "sidecar returned no JSON")
        return ProtonRefreshState(
            ok = obj.optBoolean("ok"),
            renewed = obj.optBoolean("renewed"),
            error = obj.optString("error", ""),
            code = obj.optString("code", ""),
        )
    }

    fun parseTier(text: String): ProtonTierState {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ProtonTierState(false, error = "sidecar returned no JSON")
        if (!obj.optBoolean("ok")) {
            return ProtonTierState(
                false,
                error = obj.optString("error", "unknown error"),
                code = obj.optString("code", ""),
            )
        }
        return ProtonTierState(
            ok = true,
            maxTier = obj.optInt("maxTier"),
            plan = obj.optString("plan", ""),
        )
    }

    fun parseOvpn(text: String): ProtonOvpnState {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ProtonOvpnState(false, error = "sidecar returned no JSON")
        if (!obj.optBoolean("ok")) {
            return ProtonOvpnState(
                false,
                error = obj.optString("error", "unknown error"),
                code = obj.optString("code", ""),
            )
        }
        val config = obj.optString("config", "")
        if (config.isEmpty()) {
            return ProtonOvpnState(false, error = "certificate issued but no config came back")
        }
        return ProtonOvpnState(
            ok = true,
            config = config,
            host = obj.optString("host", ""),
            port = obj.optInt("port"),
            proto = obj.optString("proto", ""),
            expiresAt = obj.optLong("expiresAt"),
        )
    }

    fun parseNodes(text: String): ProtonNodesState {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ProtonNodesState(false, error = "sidecar returned no JSON")
        if (!obj.optBoolean("ok")) {
            return ProtonNodesState(
                false,
                error = obj.optString("error", "unknown error"),
                code = obj.optString("code", ""),
                dropped = obj.optInt("dropped"),
                maxTier = obj.optInt("maxTier"),
            )
        }
        val array = obj.optJSONArray("servers") ?: return ProtonNodesState(
            false,
            dropped = obj.optInt("dropped"),
            error = "no servers in response",
        )
        val nodes = ArrayList<ProtonNode>(array.length())
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            // 協定清單要先讀：下面決定「沒有 wgPublicKey」算不算壞節點。
            val protocols = stringList(item.optJSONArray("protocols"))
            // A node without a key or endpoint cannot be dialed; the sidecar is
            // supposed to drop those, and this is the second line of defence.
            val publicKey = item.optString("wgPublicKey", "")
            val endpoint = item.optString("endpoint", "").ifEmpty { item.optString("domain", "") }
            // 舊的第二道防線要求「一定要有 WG 金鑰」，那會把 sidecar 這一輪特意留下來、
            // 只能走 OpenVPN／ProTun 的機器在這裡整個倒掉，等於篩選白做。
            if (endpoint.isEmpty() || (publicKey.isEmpty() && protocols.isEmpty())) continue
            nodes += ProtonNode(
                id = item.optString("id", ""),
                name = item.optString("name", ""),
                penalty = item.optDouble("penalty", 0.0),
                load = if (item.has("load")) item.optInt("load", -1) else -1,
                score = if (item.has("score")) item.optDouble("score", -1.0) else -1.0,
                tier = item.optInt("tier"),
                supportsIPv6 = item.optBoolean("ipv6"),
                country = item.optString("country", ""),
                city = item.optString("city", ""),
                endpoint = endpoint.substringBeforeLast(':', endpoint),
                publicKey = publicKey,
                // 51820 是 WireGuard 的預設埠，不是 Proton 的：sidecar 對「非 WG 且沒帶埠」
                // 刻意回 0（JSON 端 omitempty 整個省略），這時不能替 OpenVPN 機器臆造埠號。
                port = item.optInt("port", if (publicKey.isNotEmpty()) DEFAULT_PORT else 0),
                protocols = protocols,
                secureCore = item.optBoolean("secureCore"),
            )
        }
        // An empty list is a failure, not a success with nothing in it: the UI
        // would otherwise show "no servers" as if the account had none.
        return ProtonNodesState(
            ok = nodes.isNotEmpty(),
            nodes = nodes,
            dropped = obj.optInt("dropped"),
            maxTier = obj.optInt("maxTier"),
            error = if (nodes.isEmpty()) obj.optString("error", "no usable servers") else "",
            raw = text,
        )
    }

    const val DEFAULT_PORT = 51820

    // Proton's own client keeps the tunnel warm with 60 seconds; matching it avoids
    // a second handshake path on networks that drop idle UDP state.
    fun wireGuardConf(
        node: ProtonNode,
        privateKey: String,
        address: String = "10.2.0.2/32",
        dns: String = "10.2.0.1",
    ): String = buildString {
        appendLine("[Interface]")
        appendLine("PrivateKey = $privateKey")
        appendLine("Address = $address")
        appendLine("DNS = $dns")
        appendLine()
        appendLine("[Peer]")
        appendLine("PublicKey = ${node.publicKey}")
        appendLine("AllowedIPs = 0.0.0.0/0, ::/0")
        appendLine("Endpoint = ${node.endpoint}:${node.port}")
        appendLine("PersistentKeepalive = 60")
    }
}

object ProtonSidecar {

    private const val EXECUTABLE_NAME = "libprotonvpn.so"

    // sidecar nodesOutput.code 的唯一取值：工作階段被 Proton 擋掉。UI 認這個碼決定
    // 「顯示哪句本地化文案」與「要不要把人導回登入頁」，不去比對 error 那句英文。
    const val SESSION_EXPIRED = "session_expired"

    // The sidecar writes 0600 itself; this directory only keeps it out of backups.
    private val sessionFile: File by lazy {
        File(SagerNet.application.getNoBackupFilesDir(), "proton-session.json")
    }

    private fun executable(): File? {
        val dir = SagerNet.application.applicationInfo.nativeLibraryDir ?: return null
        val file = File(dir, EXECUTABLE_NAME)
        return if (file.canExecute()) file else null
    }

    fun isAvailable(): Boolean = executable() != null

    // captchaToken/captchaType 帶上就是「解完重試」：sidecar 會把它們放在
    // x-pm-human-verification-token(-type) 上重新跑一次 SRP 登入。
    suspend fun login(
        username: String,
        password: String,
        totp: String = "",
        captchaToken: String = "",
        captchaType: String = "",
    ): ProtonLoginState = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("username", username)
            put("password", password)
            if (totp.isNotEmpty()) put("twoFactorCode", totp)
            if (captchaToken.isNotEmpty()) put("captchaToken", captchaToken)
            if (captchaType.isNotEmpty()) put("captchaType", captchaType)
        }
        // Never log the stdin payload: it carries the account password.
        when (val result = runSidecar(listOf("login", "--state", sessionFile.absolutePath), body.toString())) {
            is ProtonOutcome.Success -> ProtonJson.parseLogin(result.json)

            is ProtonOutcome.Failed -> if (ProtonJson.looksLikeJson(result.stdout)) {
                ProtonJson.parseLogin(result.stdout)
            } else {
                ProtonLoginState(false, error = result.message)
            }
        }
    }

    // 把 9001 的 Details 換成 WebView 該載的網址。協定字串全部由 sidecar 組，Proton
    // 改格式時只動 Go 那一邊。
    suspend fun captchaBegin(
        token: String,
        methods: List<String>,
        error: String,
        dark: Boolean,
    ): ProtonCaptchaChallenge = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("token", token)
            put("methods", JSONArray(methods))
            // Proton 的英文訊息有時直接附官方驗證連結， Details 缺 token 時只剩這條。
            if (error.isNotEmpty()) put("error", error)
            put("dark", dark)
        }
        when (val result = runSidecar(listOf("captcha-begin"), body.toString())) {
            is ProtonOutcome.Success -> ProtonJson.parseCaptchaBegin(result.json)

            is ProtonOutcome.Failed -> if (ProtonJson.looksLikeJson(result.stdout)) {
                ProtonJson.parseCaptchaBegin(result.stdout)
            } else {
                ProtonCaptchaChallenge(false, error = result.message)
            }
        }
    }

    // 驗證頁面交回的成果先過一轉 sidecar（補前綴、辨過期），再拿去重試登入。
    suspend fun captchaSolve(token: String, response: String, type: String): ProtonCaptchaAnswer = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("token", token)
            put("response", response)
            if (type.isNotEmpty()) put("type", type)
        }
        when (val result = runSidecar(listOf("captcha-solve"), body.toString())) {
            is ProtonOutcome.Success -> ProtonJson.parseCaptchaSolve(result.json)

            is ProtonOutcome.Failed -> if (ProtonJson.looksLikeJson(result.stdout)) {
                ProtonJson.parseCaptchaSolve(result.stdout)
            } else {
                ProtonCaptchaAnswer(false, error = result.message)
            }
        }
    }

    // 主動續期：開機進主頁時喊一次。等 401 才換，在「清單剛好沒被讀到」的日子裡
    // 等於永不續期，而 Proton 的 refresh token 是輪換制的——斷檔就只能重登
    // （2026-10-13 那輪「一直踢回登入頁」的根因之一）。
    // 回傳只給呼叫端記日誌用：失敗一律不打擾用戶，真的過期時由清單那條路導向重登。
    suspend fun refresh(): ProtonRefreshState = withContext(Dispatchers.IO) {
        if (!sessionFile.exists()) {
            return@withContext ProtonRefreshState(false, error = "not logged in")
        }
        when (val result = runSidecar(listOf("refresh", "--state", sessionFile.absolutePath), null)) {
            is ProtonOutcome.Success -> ProtonJson.parseRefresh(result.json)

            is ProtonOutcome.Failed -> if (ProtonJson.looksLikeJson(result.stdout)) {
                ProtonJson.parseRefresh(result.stdout)
            } else {
                ProtonRefreshState(false, error = result.message)
            }
        }
    }

    // 帳號的節點層級上限（免費 1／Plus 2／Visionary 3）。清單頁拿它決定 --tier，
    // 免費帳號就不會再被塞一堆連不上的付費節點。拿不到時回 ok=false，
    // 呼叫端要的是「照舊不過濾」而不是「當成免費層」，別把不知道當 0 層。
    suspend fun tier(): ProtonTierState = withContext(Dispatchers.IO) {
        if (!sessionFile.exists()) {
            return@withContext ProtonTierState(false, error = "not logged in")
        }
        when (val result = runSidecar(listOf("tier", "--state", sessionFile.absolutePath), null)) {
            is ProtonOutcome.Success -> ProtonJson.parseTier(result.json)

            is ProtonOutcome.Failed -> if (ProtonJson.looksLikeJson(result.stdout)) {
                ProtonJson.parseTier(result.stdout)
            } else {
                ProtonTierState(false, error = result.message)
            }
        }
    }

    // OpenVPN 那條路（#71）：本機生成金鑰、向 Proton 換簽客戶端憑證，換回一份自包含
    // .ovpn。材料檔寫在 app 私有目錄（不在備份範圍），憑證到期（expiresAt）要重簽。
    suspend fun ovpn(host: String, port: Int, proto: String): ProtonOvpnState =
        withContext(Dispatchers.IO) {
            if (!sessionFile.exists()) {
                return@withContext ProtonOvpnState(false, error = "not logged in")
            }
            if (host.isEmpty() || port <= 0) {
                return@withContext ProtonOvpnState(false, error = "missing server address")
            }
            val outDir = File(SagerNet.application.filesDir, "proton-openvpn").apply { mkdirs() }
            val args = mutableListOf(
                "ovpn", "--state", sessionFile.absolutePath,
                "--out-dir", outDir.absolutePath,
                "--host", host, "--port", port.toString(), "--proto", proto,
            )
            // 帳號名優先取狀態檔（登一次就存了）；舊狀態檔沒這欄才退回 App 的記錄，
            // 兩處都沒有就不傳 --username：sidecar 會省掉 auth-user-pass，
            // 而不是回頭叫用戶再打一次。
            val account = DataStore.protonAccount
            if (account.isNotBlank()) args += listOf("--username", account)
            when (val result = runSidecar(args, null)) {
                is ProtonOutcome.Success -> ProtonJson.parseOvpn(result.json)

                is ProtonOutcome.Failed -> if (ProtonJson.looksLikeJson(result.stdout)) {
                    ProtonJson.parseOvpn(result.stdout)
                } else {
                    ProtonOvpnState(false, error = result.message)
                }
            }
        }

    suspend fun nodes(country: String = "", limit: Int = 0, maxTier: Int = 0): ProtonNodesState = withContext(Dispatchers.IO) {
        if (!sessionFile.exists()) {
            return@withContext ProtonNodesState(false, error = "not logged in")
        }
        val args = ArrayList(listOf("nodes", "--state", sessionFile.absolutePath))
        if (country.isNotEmpty()) args += listOf("--country", country)
        if (limit > 0) args += listOf("--limit", limit.toString())
        if (maxTier > 0) args += listOf("--tier", maxTier.toString())
        when (val result = runSidecar(args, null)) {
            is ProtonOutcome.Success -> ProtonJson.parseNodes(result.json)

            is ProtonOutcome.Failed -> if (ProtonJson.looksLikeJson(result.stdout)) {
                ProtonJson.parseNodes(result.stdout)
            } else {
                ProtonNodesState(false, error = result.message)
            }
        }
    }

    fun logout() {
        if (!sessionFile.delete() && sessionFile.exists()) {
            Logs.w("proton: failed to delete stored session")
        }
    }

    fun hasSession(): Boolean = sessionFile.exists()

    suspend fun keyPair(): ProtonKeyPair = withContext(Dispatchers.IO) {
        when (val result = runSidecar(listOf("keypair"), null)) {
            is ProtonOutcome.Success -> {
                val obj = runCatching { JSONObject(result.json) }.getOrNull()
                if (obj?.optBoolean("ok") == true && obj.optString("privateKey").isNotEmpty()) {
                    ProtonKeyPair(obj.getString("privateKey"), obj.getString("publicKey"))
                } else {
                    null
                }
            }

            is ProtonOutcome.Failed -> {
                Logs.w("proton keypair failed: ${result.message}")
                null
            }
        } ?: ProtonKeyPair("", "")
    }

    // An empty pair means "could not generate", which the caller must not import.
    data class ProtonKeyPair(val privateKey: String, val publicKey: String) {
        val valid: Boolean get() = privateKey.isNotEmpty() && publicKey.isNotEmpty()
    }

    private fun runSidecar(args: List<String>, stdin: String?): ProtonOutcome {
        val exe = executable() ?: return ProtonOutcome.Failed("$EXECUTABLE_NAME is not installed")
        return try {
            val process = ProcessBuilder(listOf(exe.absolutePath) + args)
                .redirectErrorStream(false)
                .start()
            // The sidecar writes its report to stdout and errors to stderr, and
            // neither stream may fill its pipe buffer while we wait, so both are
            // drained before joining.
            val output = StringBuilder()
            val errorText = StringBuilder()
            val writer = process.outputStream.writer()
            if (stdin != null) writer.write(stdin)
            writer.close()
            val outThread = Thread {
                process.inputStream.bufferedReader().use { output.append(it.readText()) }
            }.also { it.start() }
            val errThread = Thread {
                process.errorStream.bufferedReader().use { errorText.append(it.readText()) }
            }.also { it.start() }
            // Process.waitFor(timeout) and destroyForcibly() need API 24/26 while this
            // app supports 23, so the deadline is enforced with a latch and the plain
            // destroy() instead.
            val finished = CountDownLatch(1)
            Thread {
                process.waitFor()
                finished.countDown()
            }.apply {
                isDaemon = true
                start()
            }
            if (!finished.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroy()
                return ProtonOutcome.Failed("sidecar timed out after ${TIMEOUT_SECONDS}s")
            }
            outThread.join(TIMEOUT_MILLIS)
            errThread.join(TIMEOUT_MILLIS)
            val code = process.exitValue()
            if (code == 0) {
                ProtonOutcome.Success(output.toString().trim())
            } else {
                ProtonOutcome.Failed(
                    errorText.toString().trim().ifEmpty { "sidecar exited with $code" },
                    code,
                    output.toString().trim(),
                )
            }
        } catch (e: Exception) {
            Logs.w("proton sidecar failed", e)
            ProtonOutcome.Failed(e.localizedMessage ?: e.toString())
        }
    }

    private const val TIMEOUT_SECONDS = 45L
    private const val TIMEOUT_MILLIS = 2000L
}
