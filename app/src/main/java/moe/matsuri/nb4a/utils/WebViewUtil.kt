package moe.matsuri.nb4a.utils

import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import io.nekohasekai.sagernet.ktx.Logs

object WebViewUtil {
    fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        if (error != null) {
            Logs.e("WebView error description: ${error.description}")
        }
        Logs.e("WebView error: $error")
    }
}
