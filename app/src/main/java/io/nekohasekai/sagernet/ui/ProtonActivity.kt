package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.databinding.ActivityProtonBinding
import io.nekohasekai.sagernet.databinding.DialogProtonCaptchaBinding
import io.nekohasekai.sagernet.fmt.proton.ProtonCaptchaChallenge
import io.nekohasekai.sagernet.fmt.proton.ProtonJson
import io.nekohasekai.sagernet.fmt.proton.ProtonLoginState
import io.nekohasekai.sagernet.fmt.proton.ProtonNode
import io.nekohasekai.sagernet.fmt.proton.ProtonSidecar
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

class ProtonActivity : ThemedActivity() {

    private lateinit var binding: ActivityProtonBinding
    private var nodes: List<ProtonNode> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProtonBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }

        if (!DataStore.protonWarningAccepted) {
            showWarning()
        }

        if (!ProtonSidecar.isAvailable()) {
            binding.status.setText(R.string.proton_sidecar_missing)
            binding.signIn.isEnabled = false
            binding.refresh.isEnabled = false
            binding.autoLogin.isEnabled = false
            return
        }

        binding.signIn.setOnClickListener { signIn() }
        binding.signOut.setOnClickListener { signOut() }
        binding.refresh.setOnClickListener { refresh() }

        // 自動登入只是「進頁自己取節點清單」，靠的是已經登進去的那個工作階段；
        // 沒工作階段就沒什麼可沿用的，所以不讓開，也不碰密碼。
        binding.autoLogin.isChecked = DataStore.protonAutoLogin
        binding.autoLogin.setOnCheckedChangeListener { _, checked ->
            if (checked && !ProtonSidecar.hasSession()) {
                binding.autoLogin.isChecked = false
                binding.status.setText(R.string.proton_auto_login_message)
                DataStore.protonAutoLogin = false
            } else {
                DataStore.protonAutoLogin = checked
                if (checked) refresh()
            }
        }

        // 開關關著就不主動打網路：只有開了自動登入，進頁才自己取節點。
        if (DataStore.protonWarningAccepted && DataStore.protonAutoLogin && ProtonSidecar.hasSession()) {
            binding.status.setText(R.string.proton_signed_in)
            refresh()
        }
    }

    // Declining leaves nothing to show: every action on this screen is covered by
    // the warning, so it is shown before any of them are reachable.
    private fun showWarning() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.proton_warning_title)
            .setMessage(R.string.proton_warning_message)
            .setCancelable(false)
            .setPositiveButton(R.string.proton_warning_accept) { _, _ ->
                DataStore.protonWarningAccepted = true
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .show()
    }

    private fun signIn() {
        val account = binding.account.text?.toString()?.trim().orEmpty()
        val password = binding.password.text?.toString().orEmpty()
        val twoFactor = binding.twoFactor.text?.toString()?.trim().orEmpty()
        if (account.isEmpty() || password.isEmpty()) return
        runSignIn(account, password, twoFactor)
    }

    private fun runSignIn(
        account: String,
        password: String,
        twoFactor: String,
        captchaToken: String = "",
        captchaType: String = "",
    ) {
        binding.signIn.isEnabled = false
        binding.refresh.isEnabled = false
        binding.status.setText(R.string.proton_refresh)

        lifecycleScope.launch {
            val result = ProtonSidecar.login(account, password, twoFactor, captchaToken, captchaType)
            if (isFinishing || isDestroyed) return@launch
            binding.signIn.isEnabled = true
            binding.refresh.isEnabled = true
            when {
                result.ok -> {
                    // 密碼只活在這一次呼叫裡，登入成功就從畫面上抹掉；本機留下的只有
                    // sidecar 自己寫的 0600 工作階段檔。
                    binding.password.text = null
                    binding.twoFactorLayout.visibility = View.GONE
                    binding.status.setText(R.string.proton_signed_in)
                    refresh()
                }

                result.twoFactorRequired && !result.needsCaptcha() -> {
                    // Keep the code field visible so the retry is one tap away.
                    binding.twoFactorLayout.visibility = View.VISIBLE
                    binding.status.setText(R.string.proton_2fa_required)
                }

                result.needsCaptcha() -> {
                    // Proton Sentinel 對「這個出口 IP」要求先解 CAPTCHA（HTTP 422、Code 9001）。
                    // 使用者不一定有乾淨 IP 可以換到瀏覽器解一次，所以走 Proton 官方 Android
                    // 客戶端同一條路：App 內開 WebView 載驗證頁，解完把成果交回 sidecar，
                    // 由它附在重試的登入請求上（x-pm-human-verification-token）。
                    binding.status.setText(R.string.proton_captcha_required)
                    askForCaptcha(result, account, password, twoFactor)
                }

                result.reason == "captcha-rejected" -> {
                    // 剛剛那把驗證被退回：不要自動再彈一次頁面（會變成循環），講清楚就好，
                    // 使用者按「登入」會重新拿一道新的挑战。
                    binding.status.setText(R.string.proton_captcha_rejected)
                }

                else -> binding.status.text = result.error.ifEmpty { result.reason }
            }
        }
    }

    // 老 sidecar（重新建置 .so 之前的殘留產物）回的是舊 reason 字樣，一起認，
    // 免得換了 .so 之後這條路徑無聲失效。
    private fun ProtonLoginState.needsCaptcha(): Boolean = reason == "captcha-required" || reason == "human-verification-required"

    private fun askForCaptcha(
        state: ProtonLoginState,
        account: String,
        password: String,
        twoFactor: String,
    ) {
        lifecycleScope.launch {
            val challenge = ProtonSidecar.captchaBegin(
                state.captchaToken,
                state.captchaMethods,
                state.error,
                usingDarkUi(),
            )
            if (isFinishing || isDestroyed) return@launch
            if (!challenge.usable) {
                // 連 App 內可載的頁面都拿不到（Proton 沒給 token、訊息裡也沒連結）：
                // 這時只剩外部瀏覽器一條路，講明白，別假裝解好了。
                binding.status.setText(R.string.proton_captcha_unavailable)
                openInBrowser(challenge.messageUrl.ifEmpty { PROTON_LOGIN_URL })
                return@launch
            }
            showCaptchaDialog(challenge, account, password, twoFactor)
        }
    }

    private fun showCaptchaDialog(
        challenge: ProtonCaptchaChallenge,
        account: String,
        password: String,
        twoFactor: String,
    ) {
        CaptchaSession(challenge, account, password, twoFactor).start()
    }

    // Proton 官方 Android 端就是用 WebView + JavaScriptInterface 做人類驗證
    // （protoncore_android 的 HV3DialogFragment：JS 介面名稱就是 "AndroidInterface"，
    // 頁面偵測到它就改呼叫原生橋而不是 postMessage）。介面名稱與訊息格式都不能自創。
    // 這個包裡所有類別都被 proguard 的 `-keep class io.nekohasekai.sagernet.**` 保住，
    // R8 不會把 @JavascriptInterface 的方法名改掉。
    @SuppressLint("SetJavaScriptEnabled")
    private inner class CaptchaSession(
        private val challenge: ProtonCaptchaChallenge,
        private val account: String,
        private val password: String,
        private val twoFactor: String,
    ) {
        private val dialogBinding = DialogProtonCaptchaBinding.inflate(layoutInflater)
        private val web = dialogBinding.captcha

        // 驗證頁面可能連呼兩次同一個成果（iframe 與外层各報一次），只認第一把。
        private val solved = AtomicBoolean(false)
        private var usedFallback = false
        private var dialog: AlertDialog? = null

        fun start() {
            web.settings.javaScriptEnabled = true
            // 驗證頁的拼圖元件會存狀態，沒開 DOM Storage 會卡在載入。
            web.settings.domStorageEnabled = true
            web.addJavascriptInterface(Bridge(), JS_INTERFACE_NAME)
            web.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    dialogBinding.progress.visibility = View.GONE
                }

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError,
                ) {
                    if (request.isForMainFrame) onLoadFailed()
                }

                override fun onReceivedHttpError(
                    view: WebView,
                    request: WebResourceRequest,
                    errorResponse: WebResourceResponse,
                ) {
                    if (request.isForMainFrame) onLoadFailed()
                }
            }
            // 驗證頁的 console 會印出含 token 的網址與成果，一律吞掉、不進 logcat。
            web.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage?): Boolean = true
            }
            web.loadUrl(challenge.inAppUrl)

            val created = MaterialAlertDialogBuilder(this@ProtonActivity)
                .setTitle(R.string.proton_captcha_title)
                .setView(dialogBinding.root)
                .setNegativeButton(android.R.string.cancel, null)
                .create()
            dialogBinding.reload.setOnClickListener { web.reload() }
            // WebView 是會 leak 的那類 view：關掉時先從父層摘掉再 destroy，否則每開一次
            // 驗證碼就留一整顆 chromium 在記憶體裡。
            created.setOnDismissListener {
                runCatching { web.removeJavascriptInterface(JS_INTERFACE_NAME) }
                (web.parent as? ViewGroup)?.removeView(web)
                web.destroy()
            }
            created.show()
            dialog = created
            // 視窗高度交給內容決定：之前硬把視窗拉到 90% 螢幕高，而 WebView 在
            // MaterialAlertDialog 裡拿不到 weight → 結果是「按鈕上方一小條 + 下面一大塊空白」，
            // 用戶以為頁面掛了。高度改由版面給定的 420dp 決定。
            created.window?.setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        private fun onLoadFailed() {
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                dialogBinding.progress.visibility = View.GONE
                if (!usedFallback && challenge.captchaUrl.isNotEmpty()) {
                    // 首選站台（verify.proton.me）進不去時，改載 API 自己的驗證頁：
                    // 它只需要同一條 API 出口，而 sidecar 已經證明那條通得到。
                    usedFallback = true
                    web.loadUrl(challenge.captchaUrl)
                    return@runOnUiThread
                }
                dialogBinding.hint.setText(R.string.proton_captcha_failed)
            }
        }

        // JS 介面：名稱與方法都是 Proton 那边定的，兩條通道分别是
        // verify.proton.me 的 broadcast.ts（dispatch）與 /core/v4/captcha 頁面
        // （receiveResponse / receiveExpiredResponse）。
        inner class Bridge {

            @JavascriptInterface
            fun dispatch(message: String) {
                onBridgeMessage(message)
            }

            @JavascriptInterface
            fun receiveResponse(response: String) {
                deliver(response, "captcha")
            }

            @JavascriptInterface
            fun receiveExpiredResponse(response: String) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) dialogBinding.hint.setText(R.string.proton_captcha_expired)
                }
            }
        }

        // dispatch 落在 JavaBridge 執行緒：先在這裡判掉重複，再把活丢回主行程。
        private fun onBridgeMessage(raw: String) {
            val payload = runCatching { JSONObject(raw) }.getOrNull() ?: return
            when (payload.optString("type")) {
                "HUMAN_VERIFICATION_SUCCESS" -> {
                    val data = payload.optJSONObject("payload") ?: return
                    deliver(
                        data.optString("token", ""),
                        data.optString("type", "captcha").ifEmpty { "captcha" },
                    )
                }

                "NOTIFICATION" -> {
                    val text = payload.optJSONObject("payload")?.optString("text").orEmpty()
                    if (text.isNotEmpty()) {
                        runOnUiThread {
                            // Proton 頁面自己的文案（他們沒給在地化字串），只顯示、不寫日誌。
                            if (!isFinishing && !isDestroyed) dialogBinding.hint.text = text
                        }
                    }
                }

                "LOADED" -> runOnUiThread {
                    if (!isFinishing && !isDestroyed) dialogBinding.progress.visibility = View.GONE
                }

                "CLOSE" -> runOnUiThread {
                    if (!isFinishing && !isDestroyed) dialog?.dismiss()
                }
            }
        }

        private fun deliver(response: String, type: String) {
            if (response.isBlank()) return
            if (!solved.compareAndSet(false, true)) return
            lifecycleScope.launch {
                binding.status.setText(R.string.proton_captcha_solving)
                val answer = ProtonSidecar.captchaSolve(challenge.token, response, type)
                if (isFinishing || isDestroyed) return@launch
                dialog?.dismiss()
                if (!answer.ok) {
                    // 成果不屬於這一道挑戰（或被 sidecar 判定過期）：講清楚，
                    // 不要拿著它去撞登入，那只會多一次 9001。
                    binding.status.text = answer.error.ifEmpty { answer.reason }
                    return@launch
                }
                runSignIn(account, password, twoFactor, answer.token, answer.type)
            }
        }
    }

    private fun usingDarkUi(): Boolean = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private fun openInBrowser(url: String) {
        if (url.isEmpty()) return
        // 網址裡含挑戰 token：只記「開過了」，不把 URL 寫進日誌。
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Logs.w("Proton: 驗證頁面開不起來") }
    }

    private fun signOut() {
        ProtonSidecar.logout()
        // 人都登出了，開關沒有理由還開著：留著它，下次進頁就是一次打不進去的請求。
        binding.autoLogin.isChecked = false
        DataStore.protonAutoLogin = false
        nodes = emptyList()
        binding.nodeList.removeAllViews()
        binding.status.setText(R.string.proton_not_signed_in)
    }

    private fun refresh() {
        if (!ProtonSidecar.hasSession()) {
            binding.autoLogin.isChecked = false
            DataStore.protonAutoLogin = false
            binding.status.setText(R.string.proton_not_signed_in)
            return
        }
        binding.refresh.isEnabled = false
        lifecycleScope.launch {
            val result = ProtonSidecar.nodes()
            if (isFinishing || isDestroyed) return@launch
            binding.refresh.isEnabled = true
            if (!result.ok) {
                binding.status.text = result.error
                return@launch
            }
            nodes = result.nodes
            binding.status.text =
                getString(R.string.proton_node_count, result.nodes.size, result.dropped)
            renderNodes()
        }
    }

    private fun renderNodes() {
        binding.nodeList.removeAllViews()
        val gap = (8 * resources.displayMetrics.density).toInt()
        for (node in nodes) {
            val button = MaterialButton(
                this,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            )
            button.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = gap }
            button.text = getString(R.string.proton_add) + " · " + nodeLabel(node)
            button.setOnClickListener { import(node) }
            binding.nodeList.addView(button)
        }
    }

    private fun nodeLabel(node: ProtonNode): String {
        val place = listOf(node.city, node.country).filter { it.isNotEmpty() }.joinToString(" · ")
        return if (place.isEmpty()) node.name else "${node.name}   $place"
    }

    private fun import(node: ProtonNode) {
        binding.refresh.isEnabled = false
        lifecycleScope.launch {
            val pair = ProtonSidecar.keyPair()
            if (isFinishing || isDestroyed) return@launch
            if (!pair.valid) {
                binding.refresh.isEnabled = true
                binding.status.setText(R.string.proton_sidecar_missing)
                return@launch
            }
            val address = if (node.supportsIPv6) {
                "$CLIENT_IP/32, $CLIENT_IP_V6/128"
            } else {
                "$CLIENT_IP/32"
            }
            val dns = if (node.supportsIPv6) "$SERVER_IP, $SERVER_IP_V6" else SERVER_IP
            try {
                val conf = ProtonJson.wireGuardConf(node, pair.privateKey, address, dns)
                val beans = RawUpdater.parseWireGuardConf(conf)
                if (beans.isEmpty()) {
                    if (!isFinishing && !isDestroyed) binding.status.setText(R.string.proton_no_nodes)
                    return@launch
                }
                val bean = beans.first()
                bean.name = nodeLabel(node)
                ProfileManager.createProfile(DataStore.selectedGroupForImport(), bean)
                if (!isFinishing && !isDestroyed) {
                    binding.status.text = getString(R.string.proton_added, node.name)
                }
            } catch (e: Exception) {
                Logs.w("proton import failed", e)
                if (!isFinishing && !isDestroyed) {
                    binding.status.text = e.localizedMessage ?: e.toString()
                }
            } finally {
                if (!isFinishing && !isDestroyed) binding.refresh.isEnabled = true
            }
        }
    }

    companion object {
        // Proton 的驗證頁就是找這個名字的原生介面（WebClients shared/lib/broadcast/broadcast.ts
        // 的 getClient()、protoncore_android HV3DialogFragment 的 JS_INTERFACE_NAME）。
        private const val JS_INTERFACE_NAME = "AndroidInterface"

        // 拿不到 App 內可載的頁面時最後的退路：官方登入頁。
        private const val PROTON_LOGIN_URL = "https://account.proton.me/login"

        // Proton's own client constants: the in-tunnel address is fixed rather than
        // assigned, and its DNS is the server side of that same pair.
        private const val CLIENT_IP = "10.2.0.2"
        private const val SERVER_IP = "10.2.0.1"
        private const val CLIENT_IP_V6 = "2a07:b944::2:2"
        private const val SERVER_IP_V6 = "2a07:b944::2:1"
    }
}
