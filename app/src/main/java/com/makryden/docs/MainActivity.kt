package com.makryden.docs

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

/**
 * Makryden Docs - offline-first document suite.
 * The entire UI ships inside the APK (app/src/main/assets/) and loads from
 * file:///android_asset/. Only Supabase (sync/auth) and the account portal
 * touch the network.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val ASSET_HOME = "file:///android_asset/docs/index.html"
        private const val ASSET_DOCS = "file:///android_asset/docs/"
        private const val ASSET_WRITER = "file:///android_asset/writer/"
        private const val ASSET_NOTES = "file:///android_asset/notes/"
        private const val ASSET_PDF = "file:///android_asset/pdf/"
        private const val SITE = "https://makrydensystems.com"
        private const val ACCOUNT = "https://makrydensystems.com/account/"
        private const val EXIT_RESET_MS = 2000L
    }

    private lateinit var webView: WebView
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var backPressedOnce = false

    private val fileChooserLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = filePathCallback
            filePathCallback = null
            callback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.mediaPlaybackRequiresUserGesture = false

        // Accept third-party cookies so the Supabase session works from file:// origin
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = DocsWebViewClient()
        webView.webChromeClient = DocsWebChromeClient()

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                request.addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                request.addRequestHeader("User-Agent", userAgent)
                request.setTitle(URLUtil.guessFileName(url, contentDisposition, mimeType))
                request.setDescription("Makryden Docs download")
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(url, contentDisposition, mimeType))
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                dm.enqueue(request)
                Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Download failed", Toast.LENGTH_SHORT).show()
            }
        })

        // Back button: WebView history first, double-press to exit at docs home
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val current = webView.url ?: ""
                if (webView.canGoBack() && !isDocsHome(current)) {
                    webView.goBack()
                } else if (backPressedOnce) {
                    finish()
                } else {
                    backPressedOnce = true
                    Toast.makeText(this@MainActivity, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    webView.postDelayed({ backPressedOnce = false }, EXIT_RESET_MS)
                }
            }
        })

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            handleIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun isDocsHome(url: String): Boolean {
        return url == ASSET_HOME || url == ASSET_DOCS || url == "$ASSET_DOCS#index.html" ||
            url.endsWith("/android_asset/docs/index.html")
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            val type = intent.type ?: ""
            when {
                type == "application/pdf" -> {
                    webView.loadUrl(ASSET_PDF + "index.html")
                    return
                }
                type.startsWith("text/") -> {
                    webView.loadUrl(ASSET_NOTES + "index.html")
                    return
                }
            }
        }
        // Default: docs home. If WebView already has a page, don't reload.
        if (webView.url.isNullOrEmpty()) {
            webView.loadUrl(ASSET_HOME)
        }
    }

    private inner class DocsWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            return when {
                // Asset URLs stay in the WebView
                url.startsWith("file:///android_asset/") -> false
                // Account portal stays in the WebView (session bridge needs it)
                url.startsWith(ACCOUNT) -> false
                // Site doc paths rewrite to asset equivalents
                url.startsWith("$SITE/docs/") -> {
                    view.loadUrl(rewriteToAsset(url, "$SITE/docs/", ASSET_DOCS))
                    true
                }
                url.startsWith("$SITE/writer/") -> {
                    view.loadUrl(rewriteToAsset(url, "$SITE/writer/", ASSET_WRITER))
                    true
                }
                url.startsWith("$SITE/notes/") -> {
                    view.loadUrl(rewriteToAsset(url, "$SITE/notes/", ASSET_NOTES))
                    true
                }
                url.startsWith("$SITE/pdf/") -> {
                    view.loadUrl(rewriteToAsset(url, "$SITE/pdf/", ASSET_PDF))
                    true
                }
                // mailto:, tel:, etc. go to system handlers
                url.startsWith("mailto:") || url.startsWith("tel:") || url.startsWith("sms:") -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        // No handler installed; ignore
                    }
                    true
                }
                // Everything else opens in the system browser
                url.startsWith("http://") || url.startsWith("https://") -> {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (e: Exception) {
                        // No browser; ignore
                    }
                    true
                }
                else -> false
            }
        }

        private fun rewriteToAsset(url: String, sitePrefix: String, assetPrefix: String): String {
            val rest = url.removePrefix(sitePrefix)
            // rest may be "index.html", "?id=...", "index.html?id=...", or ""
            return if (rest.isEmpty() || rest == "/") {
                assetPrefix + "index.html"
            } else if (rest.startsWith("?")) {
                assetPrefix + "index.html" + rest
            } else {
                assetPrefix + rest
            }
        }
    }

    private inner class DocsWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            // Cancel any pending chooser
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            try {
                val intent = params.createIntent()
                fileChooserLauncher.launch(intent)
            } catch (e: Exception) {
                filePathCallback = null
                return false
            }
            return true
        }
    }
}
