package com.rx.geminipro.utils.network

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.lang.ref.WeakReference

// Renamed to avoid clash with WebAppInterface used for downloads
class NetworkErrorJavaInterface(
    private val webViewRef: WeakReference<WebView?>,
    private val getRetryUrl: () -> String
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun retryLastUrl() {
        val urlToRetry = getRetryUrl()
        mainHandler.post {
            webViewRef.get()?.let { webView ->
                webView.clearHistory()
                webView.loadUrl(urlToRetry)
            }
        }
    }
}