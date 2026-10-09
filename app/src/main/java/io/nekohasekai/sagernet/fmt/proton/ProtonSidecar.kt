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
) {
    // 空閒度是他在清單上真正要比的東西，負載只是它的反面。
    val idlePercent: Int? get() = load.takeIf { it in 0..100 }?.let { 100 - it }
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

    fun parseNodes(text: String): ProtonNodesState {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ProtonNodesState(false, error = "sidecar returned no JSON")
        if (!obj.optBoolean("ok")) {
            return ProtonNodesState(
                false,
                error = obj.optString("error", "unknown error"),
                code = obj.optString("code", ""),
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
            // A node without a key or endpoint cannot be dialed; the sidecar is
            // supposed to drop those, and this is the second line of defence.
            val publicKey = item.optString("wgPublicKey", "")
            val endpoint = item.optString("endpoint", "").ifEmpty { item.optString("domain", "") }
            if (publicKey.isEmpty() || endpoint.isEmpty()) continue
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
                port = item.optInt("port", DEFAULT_PORT),
            )
        }
        // An empty list is a failure, not a success with nothing in it: the UI
        // would otherwise show "no servers" as if the account had none.
        return ProtonNodesState(
            ok = nodes.isNotEmpty(),
            nodes = nodes,
            dropped = obj.optInt("dropped"),
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

    suspend fun nodes(country: String = "", limit: Int = 0): ProtonNodesState = withContext(Dispatchers.IO) {
        if (!sessionFile.exists()) {
            return@withContext ProtonNodesState(false, error = "not logged in")
        }
        val args = ArrayList(listOf("nodes", "--state", sessionFile.absolutePath))
        if (country.isNotEmpty()) args += listOf("--country", country)
        if (limit > 0) args += listOf("--limit", limit.toString())
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
