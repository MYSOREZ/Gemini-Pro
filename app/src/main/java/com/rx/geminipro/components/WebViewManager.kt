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

            if (url.startsWith("blob:")) return false

            return when {
                url.startsWith("intent://") -> handleIntentUrl(url, view)
                url.startsWith("tel:") || url.startsWith("mailto:") || url.startsWith("sms:") || url.startsWith("market:") -> handleExternalAppUrl(url)
                else -> false
            }
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
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
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*", "audio/*", "application/*", "text/*"))
            }
            onShowFileChooser(filePathCallback, intent)
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) { onPermissionRequest.invoke(request) }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            val currentActivity = activity.get() ?: run { callback?.onCustomViewHidden(); return }
            if (customView != null) { callback?.onCustomViewHidden(); return }
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
            onBackPressedCallback = object : OnBackPressedCallback(true) { override fun handleOnBackPressed() { onHideCustomView() } }
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
            onBackPressedCallback?.remove(); onBackPressedCallback = null
        }

        private fun setFullscreen(enabled: Boolean, activity: Activity) {
            val window = activity.window
            val container = fullscreenContainer ?: return
            WindowInsetsControllerCompat(window, container).let { controller ->
                if (enabled) {
                    controller.hide(WindowInsetsCompat.Type.systemBars())
                    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else controller.show(WindowInsetsCompat.Type.systemBars())
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
            url.startsWith("blob:") -> Log.d(TAG, "Blob URL detected, handling via JavaScript")
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
                    observer.observe(document.body, { childList: true, subtree: true });
                }
                function setupDownloadInterception(element) {
                    if (element.dataset.intercepted) return;
                    element.dataset.intercepted = 'true';
                    element.addEventListener('click', function(evt) {
                        const parent = element.closest('[data-testid*="media"], .media-container, .image-container') || element.parentElement;
                        const mediaElement = parent?.querySelector('img, video, canvas, [src*="blob:"]');
                        if (mediaElement) {
                            const src = mediaElement.src || mediaElement.currentSrc;
                            if (src && src.startsWith('blob:')) { evt.preventDefault(); evt.stopPropagation(); downloadBlobContent(src, mediaElement.tagName.toLowerCase()); return false; }
                        }
                        if (element.href && element.href.startsWith('blob:')) { evt.preventDefault(); evt.stopPropagation(); downloadBlobContent(element.href, 'file'); return false; }
                    }, true);
                }
                function downloadBlobContent(blobUrl, type) {
                    fetch(blobUrl)
                        .then(response => response.blob())
                        .then(blob => { const reader = new FileReader(); reader.onload = function() { const base64Data = reader.result; const mimeType = blob.type || 'application/octet-stream'; Android.downloadMedia(base64Data, mimeType); }; reader.readAsDataURL(blob); })
                        .catch(err => { Android.showToast('Failed to download file: ' + err.message); });
                }
                if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', interceptBlobDownloads); } else { interceptBlobDownloads(); }
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
                            menu.style.cssText = `position: fixed; top: \${'$'}{evt.clientY}px; left: \${'$'}{evt.clientX}px; background: white; border: 1px solid #ccc; border-radius: 4px; padding: 8px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); z-index: 10000; font-family: Arial, sans-serif; font-size: 14px;`;
                            const downloadOption = document.createElement('div');
                            downloadOption.textContent = 'Download ' + target.tagName.toLowerCase();
                            downloadOption.style.cssText = `padding: 4px 8px; cursor: pointer; border-radius: 2px;`;
                            downloadOption.onclick = () => {
                                fetch(src)
                                    .then(response => response.blob())
                                    .then(blob => { const reader = new FileReader(); reader.onload = function() { Android.downloadMedia(reader.result, blob.type); }; reader.readAsDataURL(blob); });
                                document.body.removeChild(menu);
                            };
                            menu.appendChild(downloadOption);
                            document.body.appendChild(menu);
                            setTimeout(() => {
                                document.addEventListener('click', function removeMenu() {
                                    if (document.body.contains(menu)) { document.body.removeChild(menu); }
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

    private fun injectNavigationHelper(webView: WebView) {
        val script = """
            (function() {
                const navbar = document.createElement('div');
                navbar.innerHTML = `
                    <div id="gemini-nav-helper" style="position: fixed; right: 10px; top: 50%; transform: translateY(-50%); z-index: 9999; background: rgba(0,0,0,0.7); border-radius: 25px; padding: 10px; display: flex; flex-direction: column; gap: 5px; opacity: 0.7; transition: opacity 0.3s;">
                        <button onclick="scrollToTop()" style="width: 40px; height: 40px; border-radius: 50%; border: none; background: #4285f4; color: white; cursor: pointer; font-size: 16px;">↑</button>
                        <button onclick="scrollToBottom()" style="width: 40px; height: 40px; border-radius: 50%; border: none; background: #4285f4; color: white; cursor: pointer; font-size: 16px;">↓</button>
                        <button onclick="scrollToLatestMessage()" style="width: 40px; height: 40px; border-radius: 50%; border: none; background: #34a853; color: white; cursor: pointer; font-size: 16px;">⚡</button>
                    </div>`;
                window.scrollToTop = () => window.scrollTo({ top: 0, behavior: 'smooth' });
                window.scrollToBottom = () => window.scrollTo({ top: document.body.scrollHeight, behavior: 'smooth' });
                window.scrollToLatestMessage = () => {
                    const messages = document.querySelectorAll('[data-testid="message"], .message');
                    if (messages.length > 0) { messages[messages.length - 1].scrollIntoView({ behavior: 'smooth' }); }
                };
                document.body.appendChild(navbar);
                let hideTimeout;
                window.addEventListener('scroll', () => {
                    const navHelper = document.getElementById('gemini-nav-helper');
                    if (navHelper) {
                        navHelper.style.opacity = '1';
                        clearTimeout(hideTimeout);
                        hideTimeout = setTimeout(() => { navHelper.style.opacity = '0.3'; }, 3000);
                    }
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectContextMonitor(webView: WebView) {
        val script = """
            (function() {
                let messageCount = 0;
                const observer = new MutationObserver(function() {
                    const messages = document.querySelectorAll('[data-testid="message"], .message');
                    const newCount = messages.length;
                    if (newCount > messageCount && newCount > 50) { messageCount = newCount; Android.showContextWarning(messageCount); } else { messageCount = newCount; }
                });
                observer.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun injectMultiFileSupport(webView: WebView) {
        val script = """
            (function() {
                document.addEventListener('change', function(e) {
                    if (e.target.type === 'file') {
                        const files = e.target.files;
                        if (files.length > 1) { Android.showToast(files.length + ' files selected'); }
                    }
                });
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    // --- Private Helper Functions ---

    private fun handleIntentUrl(url: String, webView: WebView): Boolean {
        return try {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            val fallbackUrl = intent.getStringExtra("browser_fallback_url")
            if (intent.resolveActivity(context.packageManager) != null) { onStartActivity(intent) }
            else if (fallbackUrl != null) { webView.loadUrl(fallbackUrl) }
            else { onShowToast("Cannot handle this type of link.") }
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Error parsing intent URL: $url", ex)
            onShowToast("Error handling link.")
            false
        }
    }

    private fun handleExternalAppUrl(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, url.toUri())
            onStartActivity(intent)
            true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to start activity for URL: $url", ex)
            onShowToast("Cannot open link.")
            true
        }
    }

    private fun handleDataUrlDownload(url: String) {
        try {
            val downloader = BlobDownloaderInterface()
            val header = url.substringBefore(',')
            val mimeType = header.substringAfter("data:").substringBefore(';')
            val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: mimeType.substringAfter('/')
            val filename = "download_${'$'}{System.currentTimeMillis()}.${'$'}extension"
            val file = downloader.processBlobData(url, filename)
            file?.let { showDownloadCompleteNotification(file, mimeType) }
        } catch (ex: Exception) {
            Log.e(TAG, "Error processing data URL download", ex)
            onShowToast("Error processing download: ${'$'}{ex.message}")
        }
    }

    private fun handleStandardDownload(url: String, userAgent: String, contentDisposition: String, mimeType: String) {
        try {
            val request = DownloadManager.Request(url.toUri()).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url))
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                val filename = URLUtil.guessFileName(url, contentDisposition, mimeType)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
                setTitle(filename)
                setDescription("Downloading file...")
            }
            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            dm.enqueue(request)
            onShowToast("Starting download: ${'$'}{URLUtil.guessFileName(url, contentDisposition, mimeType)}")
        } catch (ex: Exception) {
            Log.e(TAG, "Download failed for URL: $url", ex)
            onShowToast("Download failed: ${'$'}{ex.message}")
        }
    }

    private fun showDownloadCompleteNotification(file: File, mimeType: String) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "blob_downloads"
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_HIGH)
            notificationManager.createNotificationChannel(channel)
        }
        val fileUri: Uri = try {
            FileProvider.getUriForFile(context, "${'$'}{context.packageName}.provider", file)
        } catch (ex: IllegalArgumentException) {
            Log.e(TAG, "FileProvider error: ${'$'}{ex.message}")
            onShowToast("Error creating file URI for notification")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(fileUri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (intent.resolveActivity(context.packageManager) == null) { Log.w(TAG, "No activity found to handle VIEW intent for type ${'$'}mimeType") }
        val pendingIntentFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pendingIntent = PendingIntent.getActivity(context, 0, intent, pendingIntentFlags)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.google_gemini_icon)
            .setContentTitle("Download complete")
            .setContentText(file.name)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val notificationId = System.currentTimeMillis().toInt()
        notificationManager.notify(notificationId, notification)
    }
}
