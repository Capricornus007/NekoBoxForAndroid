package io.nekohasekai.sagernet.ui

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.webkit.*
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutWebviewBinding
import moe.matsuri.nb4a.utils.WebViewUtil

// Fragment必须有一个无参public的构造函数，否则在数据恢复的时候，会报crash

class WebviewFragment : ToolbarFragment(R.layout.layout_webview), Toolbar.OnMenuItemClickListener {
    private val defaultPanelUrl = "http://127.0.0.1:9090/ui"

    lateinit var mWebView: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // layout
        toolbar.setTitle(R.string.menu_dashboard)
        toolbar.inflateMenu(R.menu.yacd_menu)
        toolbar.setOnMenuItemClickListener(this)

        val binding = LayoutWebviewBinding.bind(view)

        // webview
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        mWebView = binding.webview
        mWebView.settings.apply {
            domStorageEnabled = true
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            databaseEnabled = false
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }
        mWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean =
                request?.url?.let { normalizePanelUrl(it.toString()) == null } ?: true
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean =
                url?.let { normalizePanelUrl(it) == null } ?: true
            override fun onReceivedError(
                view: WebView?, request: WebResourceRequest?, error: WebResourceError?
            ) {
                WebViewUtil.onReceivedError(view, request, error)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
            }
        }
        loadSafeUrl(DataStore.yacdURL)
    }

    @SuppressLint("CheckResult")
    override fun onMenuItemClick(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_set_url -> {
                val view = EditText(context).apply {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setText(DataStore.yacdURL)
                }
                MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.set_panel_url)
                    .setView(view)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val candidateUrl = view.text.toString()
                        val safeUrl = normalizePanelUrl(candidateUrl)
                        if (safeUrl == null) {
                            Toast.makeText(
                                requireContext(), R.string.invalid_panel_url, Toast.LENGTH_SHORT
                            ).show()
                            return@setPositiveButton
                        }
                        DataStore.yacdURL = safeUrl
                        loadSafeUrl(safeUrl)
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            R.id.close -> {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        return true
    }

    override fun onDestroyView() {
        if (::mWebView.isInitialized) {
            mWebView.stopLoading()
            mWebView.webViewClient = WebViewClient()
            mWebView.removeAllViews()
            mWebView.destroy()
        }
        super.onDestroyView()
    }

    private fun loadSafeUrl(url: String) {
        val safeUrl = normalizePanelUrl(url) ?: defaultPanelUrl
        DataStore.yacdURL = safeUrl
        val uri = Uri.parse(safeUrl)
        val dashboard = isLoopbackHost(uri.host) && uri.port == 9090 && (uri.path == "/ui" || uri.path == "/ui/")
        val loadUrl = if (dashboard) uri.buildUpon().clearQuery().apply {
            uri.queryParameterNames.filterNot { it in setOf("hostname", "port", "secret") }.forEach { name ->
                uri.getQueryParameters(name).forEach { appendQueryParameter(name, it) }
            }
            appendQueryParameter("hostname", uri.host)
            appendQueryParameter("port", "9090")
            appendQueryParameter("secret", DataStore.clashApiSecret)
        }.build().toString() else safeUrl
        mWebView.loadUrl(loadUrl)
    }

    private fun normalizePanelUrl(rawUrl: String): String? {
        val parsed = runCatching { Uri.parse(rawUrl.trim()) }.getOrNull() ?: return null
        if (parsed.host.isNullOrBlank() || parsed.encodedUserInfo != null) return null
        val scheme = parsed.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        if (scheme == "http" && !isLoopbackHost(parsed.host)) return null
        return parsed.toString()
    }

    private fun isLoopbackHost(host: String?): Boolean {
        val normalized = host?.trim()?.lowercase() ?: return false
        return normalized == "127.0.0.1" || normalized == "localhost" || normalized == "[::1]" || normalized == "::1"
    }
}
