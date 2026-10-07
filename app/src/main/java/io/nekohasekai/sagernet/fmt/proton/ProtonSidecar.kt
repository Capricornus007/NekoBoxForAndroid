package io.nekohasekai.sagernet.fmt.proton

import android.system.Os
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
)

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
)

class ProtonNodesState(
    val ok: Boolean,
    val nodes: List<ProtonNode> = emptyList(),
    val dropped: Int = 0,
    val error: String = "",
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
        )
    }

    fun parseNodes(text: String): ProtonNodesState {
        val obj = runCatching { JSONObject(text) }.getOrNull()
            ?: return ProtonNodesState(false, error = "sidecar returned no JSON")
        if (!obj.optBoolean("ok")) {
            return ProtonNodesState(false, error = obj.optString("error", "unknown error"))
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

    // The sidecar writes 0600 itself; this directory only keeps it out of backups.
    private val sessionFile: File by lazy {
        File(SagerNet.application.getNoBackupFilesDir(), "proton-session.json")
    }

    // 自動登入的憑證只存在 noBackupFilesDir：不進設定資料庫、不進日誌、不進錯誤訊息。
    private val credentialsFile: File by lazy {
        File(SagerNet.application.getNoBackupFilesDir(), "proton-credentials.json")
    }

    fun storeCredentials(account: String, password: String): Boolean = runCatching {
        val body = JSONObject().apply {
            put("username", account)
            put("password", password)
        }
        // session 檔是 sidecar 自己建目錄的，憑證檔由這裡寫，所以目錄要自己確保存在。
        credentialsFile.parentFile?.mkdirs()
        credentialsFile.writeText(body.toString())
        // writeText 建成 0644，跟 sidecar 自己寫的 session 檔不一致，在這裡補回 0600。
        Os.chmod(credentialsFile.absolutePath, CREDENTIALS_FILE_MODE)
        true
    }.getOrElse {
        Logs.w("proton: failed to store credentials", it)
        false
    }

    fun readCredentials(): Pair<String, String>? {
        val obj = runCatching { JSONObject(credentialsFile.readText()) }.getOrNull() ?: return null
        val account = obj.optString("username", "")
        val password = obj.optString("password", "")
        return if (account.isEmpty() || password.isEmpty()) null else account to password
    }

    fun clearCredentials() {
        if (!credentialsFile.delete() && credentialsFile.exists()) {
            Logs.w("proton: failed to delete stored credentials")
        }
    }

    private fun executable(): File? {
        val dir = SagerNet.application.applicationInfo.nativeLibraryDir ?: return null
        val file = File(dir, EXECUTABLE_NAME)
        return if (file.canExecute()) file else null
    }

    fun isAvailable(): Boolean = executable() != null

    suspend fun login(username: String, password: String, totp: String = ""): ProtonLoginState =
        withContext(Dispatchers.IO) {
            val body = JSONObject().apply {
                put("username", username)
                put("password", password)
                if (totp.isNotEmpty()) put("twoFactorCode", totp)
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

    // Os.chmod 要整數模式，Kotlin 沒有八進位字面值：0b110000000 就是 0600。
    private const val CREDENTIALS_FILE_MODE = 0b110000000
}
