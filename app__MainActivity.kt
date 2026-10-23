package dev.sk2andy.materialbrowser

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/** Step 1: one screen, one WebView, an address bar and a progressBar bar. Nothing else. */
class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar

    private lateinit var downloader: Downloader
    private var pendingDownload: Downloader.Request? = null

    private val backCallback = OnBackInvokedCallback {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloader = Downloader(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }

        addressBar = EditText(this).apply {
            setSingleLine(true)
            hint = "Search or enter address"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnEditorActionListener { v, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    webView.loadUrl(toUrl(v.text.toString()))
                    v.clearFocus()
                    true
                } else false
            }
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = android.view.View.GONE
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                onDownloadRequested(url, userAgent, contentDisposition, mimeType)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (!addressBar.hasFocus()) addressBar.setText(url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility =
                        if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
                }
            }
        }

        val status = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            val pkg = WebView.getCurrentWebViewPackage()
            text = if (pkg != null) "System WebView: ${pkg.packageName} ${pkg.versionName}"
            else "System WebView: not found"
        }

        root.addView(addressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // targetSdk 36 draws edge-to-edge, so keep content clear of system bars and keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }

        setContentView(root)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback
        )

        WebView.setWebContentsDebuggingEnabled(true) // step-1 diagnostics only
        webView.loadUrl("https://example.com")
    }

    /** Every download: ask for a folder, then save into it. */
    private fun onDownloadRequested(url: String, ua: String?, disposition: String?, mime: String?) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            downloader.toast("This download type is not supported yet")
            return
        }
        if (pendingDownload != null) {
            downloader.toast("Choose a folder for the current download first")
            return
        }
        pendingDownload = Downloader.Request(
            url = url,
            fileName = URLUtil.guessFileName(url, disposition, mime),
            userAgent = ua,
            referer = webView.url,
            cookies = CookieManager.getInstance().getCookie(url),
        )
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Ask once; the folder picker opens right after the answer.
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        } else {
            openFolderPicker()
        }
    }

    private fun openFolderPicker() =
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_NOTIFY && pendingDownload != null) openFolderPicker()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_FOLDER) return
        val req = pendingDownload
        pendingDownload = null
        val tree = data?.data
        if (resultCode == RESULT_OK && tree != null && req != null) downloader.start(req, tree)
    }

    private companion object {
        const val REQ_FOLDER = 1
        const val REQ_NOTIFY = 2
    }

    private fun toUrl(input: String): String {
        val t = input.trim()
        return when {
            t.isEmpty() -> "about:blank"
            t.startsWith("http://") || t.startsWith("https://") -> t
            t.contains(' ') || !t.contains('.') -> "https://www.google.com/search?q=" + Uri.encode(t)
            else -> "https://$t"
        }
    }

    override fun onDestroy() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }
}
