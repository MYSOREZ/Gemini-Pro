package com.rx.geminipro.components

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.MimeTypeMap
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.rx.geminipro.R
import com.rx.geminipro.utils.network.BlobDownloaderInterface
import java.io.File
import java.lang.ref.WeakReference


private const val TAG = "WebViewManager"


class WebViewManager(
    private val context: Context,
    private val activity: WeakReference<ComponentActivity>
) {
    var onShowFileChooser: (ValueCallback<Array<Uri>>, Intent) -> Unit = { _, _ -> }

    var onShowToast: (message: String) -> Unit = {}

    var onStartActivity: (intent: Intent) -> Unit = {}

    var onPageFinished: (view: WebView, url: String) -> Unit = { _, _ -> }

    var onPermissionRequest: (request: PermissionRequest) -> Unit = { it.deny() }

    // --- Private State ---

    private var lastUrl: String? = null
    private var lastFailedUrl: String? = null
    private val errorUrl = "file:///android_asset/webview_error.html"

    // --- Core WebView Clients and Listeners ---

    val webViewClient: WebViewClient = object : WebViewClient() {
        @SuppressLint("QueryPermissionsNeeded")
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()

            // Handle blob URLs - don't override, let WebView handle them
            if (url.startsWith("blob:")) {
                return false
            }

            return when {
                url.startsWith("intent://") -> handleIntentUrl(url, view)
                url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") || url.startsWith("market:") -> handleExternalAppUrl(url)
                else -> false
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)

            // Inject helpers
            injectBlobDownloadHandler(view)
            injectDownloadEnhancement(view)
            injectNavigationHelper(view)
            injectContextMonitor(view)
            injectMultiFileSupport(view)

            onPageFinished.invoke(view, url)
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                val failingUrl = request.url.toString()
                Log.e(TAG, "WebView Error: ${error?.errorCode} - ${error?.description} for $failingUrl")
                lastFailedUrl = failingUrl
                view?.loadUrl(errorUrl)
            }
        }
    }

    val webChromeClient: WebChromeClient = object : WebChromeClient() {
        private var customView: View? = null
        private var customViewCallback: CustomViewCallback? = null
        private var originalOrientation: Int = 0
        private var fullscreenContainer: FrameLayout? = null
        private var onBackPressedCallback: OnBackPressedCallback? = null

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams
        ): Boolean {
            val intent = fileChooserParams.createIntent().apply {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "image/*", "video/*", "audio/*", "application/*", "text/*"
                ))
            }
            onShowFileChooser(filePathCallback, intent)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            onPermissionRequest.invoke(request)
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            val currentActivity = activity.get() ?: run {
                callback?.onCustomViewHidden()
                return
            }

            if (customView != null) {
                callback?.onCustomViewHidden()
                return
            }

            customView = view
            customViewCallback = callback
            originalOrientation = currentActivity.requestedOrientation

            val decorView = currentActivity.window.decorView as ViewGroup
            fullscreenContainer = FrameLayout(currentActivity).apply {
                setBackgroundColor(Color.BLACK)
                addView(customView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            decorView.addView(fullscreenContainer, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

            setFullscreen(true, currentActivity)

            onBackPressedCallback = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { onHideCustomView() }
            }
            currentActivity.onBackPressedDispatcher.addCallback(currentActivity, onBackPressedCallback!!)
        }

        override fun onHideCustomView() {
            val currentActivity = activity.get() ?: return
            if (customView == null) return

            setFullscreen(false, currentActivity)

            (fullscreenContainer?.parent as? ViewGroup)?.removeView(fullscreenContainer)

            fullscreenContainer = null
            customView = null
            customViewCallback?.onCustomViewHidden()

            currentActivity.requestedOrientation = originalOrientation

            onBackPressedCallback?.remove()
            onBackPressedCallback = null
        }

        private fun setFullscreen(enabled: Boolean, activity: Activity) {
            val window = activity.window
            val container = fullscreenContainer ?: return
            WindowInsetsControllerCompat(window, container).let { controller ->
                if (enabled) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(WindowInsetsCompat.Type.systemBars())
                }
            }
        }

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            super.onProgressChanged(view, newProgress)

            val currentUrl = view?.url
            if (newProgress == 100 && currentUrl != null && currentUrl != lastUrl) {
                lastUrl = currentUrl
                onPageFinished.invoke(view, currentUrl)
            }
        }
    }

    var onBlobDownloadRequested: (url: String, contentDisposition: String?, mimeType: String?) -> Unit = { _, _, _ -> }

    val downloadListener: DownloadListener = DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
        when {
            url.startsWith("blob:") -> {
                Log.d(TAG, "Blob URL detected, handling via JavaScript")
            }
            url.startsWith("data:") -> handleDataUrlDownload(url)
            else -> handleStandardDownload(url, userAgent, contentDisposition, mimeType)
        }
    }

    // --- JavaScript Injection Methods ---

    private fun injectBlobDownloadHandler(webView: WebView) {
        val script = """
            (function() {
                function interceptBlobDownloads() {
                    const observer = new MutationObserver(function(mutations) {
                        mutations.forEach(function(mutation) {
                            mutation.addedNodes.forEach(function(node) {
                                if (node.nodeType === 1) {
                                    const downloadElements = node.querySelectorAll?.('[aria-label*="Download"], [title*="Download"], .download-button, a[download]') || [];
                                    downloadElements.forEach(setupDownloadInterception);
                                    if (node.matches?.('[aria-label*="Download"], [title*="Download"], .download-button, a[download]')) {
                                        setupDownloadInterception(node);
                                    }
                                }
                            });
                        });
                    });
                    document.querySelectorAll('[aria-label*="Download"], [title*="Download"], .download-button, a[download]').forEach(setupDownloadInterception);
                    observer.observe(document.body, {
                        childList: true,
                        subtree: true
                    });
                }
                function setupDownloadInterception(element) {
                    if (element.dataset.intercepted) return;
                    element.dataset.intercepted = 'true';
                    element.addEventListener('click', function(evt) {
                        const parent = element.closest('[data-testid*="media"], .media-container, .image-container') || element.parentElement;
                        const mediaElement = parent?.querySelector('img, video, canvas, [src*="blob:"]');
                        if (mediaElement) {
                            const src = mediaElement.src || mediaElement.currentSrc;
                            if (src && src.startsWith('blob:')) {
                                evt.preventDefault();
                                evt.stopPropagation();
                                downloadBlobContent(src, mediaElement.tagName.toLowerCase());
                                return false;
                            }
                        }
                        if (element.href && element.href.startsWith('blob:')) {
                            evt.preventDefault();
                            evt.stopPropagation();
                            downloadBlobContent(element.href, 'file');
                            return false;
                        }
                    }, true);
                }
                function downloadBlobContent(blobUrl, type) {
                    fetch(blobUrl)
                        .then(response => response.blob())
                        .then(blob => {
                            const reader = new FileReader();
                            reader.onload = function() {
                                const base64Data = reader.result;
                                const mimeType = blob.type || 'application/octet-stream';
                                Android.downloadMedia(base64Data, mimeType);
                            };
                            reader.readAsDataURL(blob);
                        })
                        .catch(err => {
                            Android.showToast('Failed to download file: ' + err.message);
                        });
                }
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', interceptBlobDownloads);
                } else {
                    interceptBlobDownloads();
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectDownloadEnhancement(webView: WebView) {
        val script = """
            (function() {
                document.addEventListener('contextmenu', function(evt) {
                    const target = evt.target;
                    if (target.tagName === 'IMG' || target.tagName === 'VIDEO') {
                        const src = target.src || target.currentSrc;
                        if (src && src.startsWith('blob:')) {
                            evt.preventDefault();
                            const menu = document.createElement('div');
                            menu.style.cssText = `position: fixed; top: \${evt.clientY}px; left: \${evt.clientX}px; background: white; border: 1px solid #ccc; border-radius: 4px; padding: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); z-index: 10000; font-family: Arial, sans-serif; font-size: 14px;`;
                            const downloadOption = document.createElement('div');
                            downloadOption.textContent = 'Download ' + target.tagName.toLowerCase();
                            downloadOption.style.cssText = `padding: 4px 8px; cursor: pointer; border-radius: 2px;`;
                            downloadOption.onclick = () => {
                                fetch(src)
                                    .then(response => response.blob())
                                    .then(blob => {
                                        const reader = new FileReader();
                                        reader.onload = function() {
                                            Android.downloadMedia(reader.result, blob.type);
                                        };
                                        reader.readAsDataURL(blob);
                                    });
                                document.body.removeChild(menu);
                            };
                            menu.appendChild(downloadOption);
                            document.body.appendChild(menu);
                            setTimeout(() => {
                                document.addEventListener('click', function removeMenu() {
                                    if (document.body.contains(menu)) {
                                        document.body.removeChild(menu);
                                    }
                                    document.removeEventListener('click', removeMenu);
                                }, 100);
                            });
                        }
                    }
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // ... rest of file unchanged ...
}
