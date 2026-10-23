package dev.sk2andy.materialbrowser

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.webkit.CookieManager
import androidx.webkit.ProfileStore
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
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

    private lateinit var webView: WebView            // the active profile's WebView
    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var container: FrameLayout
    private lateinit var profileBar: HorizontalScrollView
    private lateinit var chipRow: LinearLayout

    private lateinit var profiles: ProfileList
    private val views = HashMap<String, WebView>()   // profile id -> its WebView

    private lateinit var downloader: Downloader
    private var pendingDownload: Downloader.Request? = null
    private var busy = false

    private val backCallback = OnBackInvokedCallback {
        if (profileBar.visibility == View.VISIBLE) profileBar.visibility = View.GONE
        else if (webView.canGoBack()) webView.goBack() else finish()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloader = Downloader(this)
        cacheDir.listFiles()?.filter { it.name.startsWith("dl-") }?.forEach { it.delete() } // leftovers

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
        }
        // targetSdk 36 draws edge-to-edge, so keep content clear of system bars and keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)

        // Every profile needs its own storage. Never fall back to shared storage.
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            root.addView(TextView(this).apply {
                text = "This phone's Android System WebView does not support separate profiles.\n\n" +
                    "Update \"Android System WebView\" (or Chrome) from the Play Store and open the app again."
                textSize = 16f
                setPadding(dp(24), dp(48), dp(24), dp(24))
            })
            return
        }

        profiles = ProfileList(this)
        removeOrphanStorage()

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
        val profileBtn = TextView(this).apply {
            text = "\uD83D\uDC64"
            textSize = 22f
            gravity = Gravity.CENTER
            setOnClickListener {
                profileBar.visibility = if (profileBar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                if (profileBar.visibility == View.VISIBLE) refreshChips()
            }
        }
        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(addressBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(profileBtn, LinearLayout.LayoutParams(dp(48), dp(48)))
        }

        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        profileBar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setBackgroundColor(0xFFF6F6F4.toInt())
            addView(chipRow)
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
        }
        container = FrameLayout(this)

        val status = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.GRAY)
            setPadding(dp(12), dp(4), dp(12), dp(4))
            val pkg = WebView.getCurrentWebViewPackage()
            text = if (pkg != null) "System WebView: ${pkg.packageName} ${pkg.versionName}"
            else "System WebView: not found"
        }

        root.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(profileBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback
        )
        WebView.setWebContentsDebuggingEnabled(true) // diagnostics only
        showProfile(profiles.activeId)
    }

    // ---- profiles -------------------------------------------------------------------------

    /** Storage of profiles that are no longer in our list (e.g. a delete that was interrupted). */
    private fun removeOrphanStorage() = runCatching {
        val store = ProfileStore.getInstance()
        val known = profiles.profiles.map { it.id }.toSet()
        store.allProfileNames
            .filter { it != Profile_DEFAULT && it !in known }
            .forEach { runCatching { store.deleteProfile(it) } }
    }

    private fun createWebView(profile: Profile): WebView {
        ProfileStore.getInstance().getOrCreateProfile(profile.id)
        return WebView(this).apply {
            WebViewCompat.setProfile(this, profile.id) // first call: before any loading
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            val self = this
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                onDownloadRequested(self, profile.id, url, userAgent, contentDisposition, mimeType)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (view === webView && !addressBar.hasFocus()) addressBar.setText(url)
                    view.evaluateJavascript(NAME_HOOK, null)
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(NAME_HOOK, null)
                    if (url != null && url != "about:blank") profiles.saveUrl(profile.id, url)
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (view !== webView) return
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** Puts the profile's WebView on screen (each profile keeps its own page while hidden). */
    private fun showProfile(id: String) {
        if (::webView.isInitialized) webView.onPause()
        profiles.setActive(id)
        val profile = profiles.active()
        val v = views.getOrPut(id) { createWebView(profile) }
        container.removeAllViews()
        container.addView(v, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        v.onResume()
        webView = v
        progressBar.visibility = View.GONE
        if (v.url == null) v.loadUrl(profiles.lastUrl(id) ?: HOME) else addressBar.setText(v.url)
        refreshChips()
    }

    private fun chip(label: String, active: Boolean) = TextView(this).apply {
        text = label
        textSize = 15f
        setTextColor(0xFF1B1B1B.toInt())
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = GradientDrawable().apply {
            cornerRadius = dp(100).toFloat()
            setColor(if (active) 0xFFB7F0BE.toInt() else 0xFFE6E9E0.toInt())
        }
    }

    private fun refreshChips() {
        chipRow.removeAllViews()
        for (p in profiles.profiles) {
            val c = chip(p.name, p.id == profiles.activeId).apply {
                setOnClickListener {
                    profileBar.visibility = View.GONE
                    if (p.id != profiles.activeId) showProfile(p.id)
                }
                setOnLongClickListener { profileActions(p); true }
            }
            chipRow.addView(c, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) })
        }
        chipRow.addView(chip("+", false).apply {
            textSize = 20f
            setOnClickListener { addProfileDialog() }
        })
    }

    private fun nameDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply { setSingleLine(true); setText(initial); setSelection(length()) }
        val box = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(input) }
        val d = android.app.AlertDialog.Builder(this)
            .setTitle(title).setView(box)
            .setPositiveButton("OK") { _, _ ->
                val n = input.text.toString().trim()
                if (n.isNotEmpty()) onOk(n)
            }
            .setNegativeButton("Cancel", null).create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        d.show()
        input.requestFocus()
    }

    private fun addProfileDialog() {
        if (profiles.profiles.size >= ProfileList.MAX_PROFILES) {
            downloader.toast("Profile limit reached (${ProfileList.MAX_PROFILES})"); return
        }
        nameDialog("New profile", "") { n ->
            val p = profiles.add(n)
            profileBar.visibility = View.GONE
            showProfile(p.id)
        }
    }

    private fun profileActions(p: Profile) {
        android.app.AlertDialog.Builder(this)
            .setTitle(p.name)
            .setItems(arrayOf("Rename", "Delete")) { _, which ->
                if (which == 0) nameDialog("Rename profile", p.name) { n -> profiles.rename(p.id, n); refreshChips() }
                else confirmDelete(p)
            }.show()
    }

    private fun confirmDelete(p: Profile) {
        if (profiles.profiles.size <= 1) { downloader.toast("Keep at least one profile"); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete \"${p.name}\"?")
            .setMessage("Its logins and site data will be erased. Other profiles are not touched.")
            .setPositiveButton("Delete") { _, _ -> deleteProfile(p) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteProfile(p: Profile) {
        // 1) leave the profile if it is on screen, 2) destroy its WebView, 3) erase its storage.
        val wasActive = p.id == profiles.activeId
        views.remove(p.id)?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            v.destroy()
        }
        profiles.remove(p.id)
        if (wasActive) showProfile(profiles.activeId) else refreshChips()
        deleteStorage(p.id, 0)
    }

    /** The store refuses to delete a profile that is still in use, so retry shortly. */
    private fun deleteStorage(id: String, attempt: Int) {
        val ok = runCatching { ProfileStore.getInstance().deleteProfile(id) }.isSuccess
        if (!ok && attempt < 5) {
            Handler(Looper.getMainLooper()).postDelayed({ deleteStorage(id, attempt + 1) }, 500)
        } // anything left is removed at next start by removeOrphanStorage()
    }

    /** Every download: get the bytes (blob/data are fetched right away), then ask for a folder. */
    private fun onDownloadRequested(view: WebView, profileId: String, url: String, ua: String?, disposition: String?, mime: String?) {
        if (busy) {
            downloader.toast("Finish the current download first")
            return
        }
        val page = view.url
        when {
            url.startsWith("http://") || url.startsWith("https://") -> {
                busy = true
                askFolder(
                    Downloader.Request(
                        url = url,
                        fileName = URLUtil.guessFileName(url, disposition, mime),
                        userAgent = ua,
                        referer = page,
                        cookies = (ProfileStore.getInstance().getProfile(profileId)?.cookieManager ?: CookieManager.getInstance()).getCookie(url),
                    )
                )
            }
            url.startsWith("blob:") && page != null -> {
                busy = true
                downloader.toast("Preparing download…")
                // Real name = the page link's download="..." attribute (a blob URL has no name).
                view.evaluateJavascript(
                    "(function(){var n=window.__dlNames||{};var u=" + org.json.JSONObject.quote(url) +
                        ";if(n[u])return n[u];if(window.__dlLast&&Date.now()-window.__dlLastT<20000)return window.__dlLast;return '';})()"
                ) { raw ->
                    val pageName = raw?.trim('"')?.takeIf { it.isNotBlank() && it != "null" }
                    BlobFetcher(this, profileId).fetch(
                        blobUrl = url,
                        pageUrl = page,
                        onDone = { file, type -> stagedFile(url, disposition, type ?: mime, file, pageName) },
                        onFail = { busy = false; downloader.toast("Download failed: $it") },
                    )
                }
            }
            url.startsWith("data:") -> {
                busy = true
                Thread {
                    val file = decodeDataUrl(url)
                    runOnUiThread {
                        if (file == null) {
                            busy = false
                            downloader.toast("Download failed: bad data URL")
                        } else {
                            val type = url.substring(5).substringBefore(',').substringBefore(';')
                            stagedFile(url, disposition, type.ifBlank { mime }, file)
                        }
                    }
                }.start()
            }
            else -> downloader.toast("This download type is not supported yet")
        }
    }

    /** Blob/data bytes are already in a temp file; now only the folder is missing. */
    private fun stagedFile(url: String, disposition: String?, mime: String?, file: java.io.File, pageName: String? = null) {
        askFolder(
            Downloader.Request(
                url = url,
                fileName = pageName ?: URLUtil.guessFileName(url, disposition, mime),
                userAgent = null, referer = null, cookies = null,
                localFile = file,
            )
        )
    }

    private fun decodeDataUrl(url: String): java.io.File? = runCatching {
        val comma = url.indexOf(',')
        val header = url.substring(5, comma)
        val payload = url.substring(comma + 1)
        val bytes = if (header.endsWith(";base64")) {
            android.util.Base64.decode(Uri.decode(payload), android.util.Base64.DEFAULT)
        } else Uri.decode(payload).toByteArray()
        java.io.File(cacheDir, "dl-${System.nanoTime()}.tmp").also { it.writeBytes(bytes) }
    }.getOrNull()

    /** Rename dialog first; the folder picker opens only after the user taps Download. */
    private fun askFolder(req: Downloader.Request) {
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val dot = req.fileName.lastIndexOf('.')
        val baseName = if (dot > 0) req.fileName.substring(0, dot) else req.fileName
        val extName = if (dot > 0) req.fileName.substring(dot + 1) else ""

        val nameField = EditText(this).apply {
            setSingleLine(true); hint = "File name"; setText(baseName)
        }
        val extField = EditText(this).apply {
            setSingleLine(true); hint = "ext"; setText(extName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        var active: EditText = nameField
        nameField.setOnFocusChangeListener { _, f -> if (f) active = nameField }
        extField.setOnFocusChangeListener { _, f -> if (f) active = extField }

        fun tool(label: String, action: () -> Unit) = android.widget.Button(this).apply {
            text = label; isAllCaps = false; isFocusable = false; isFocusableInTouchMode = false
            setOnClickListener { action() }
        }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(tool("◀") { active.setSelection((active.selectionEnd - 1).coerceAtLeast(0)) })
            addView(tool("▶") { active.setSelection((active.selectionEnd + 1).coerceAtMost(active.length())) })
            addView(tool("Select all") { active.selectAll() })
        }
        val extRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(TextView(context).apply { text = "Extension  ."; textSize = 14f })
            addView(extField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
            addView(nameField)
            addView(extRow)
            addView(tools)
        }

        var confirmed = false
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Save file as")
            .setView(box)
            .setPositiveButton("Download") { _, _ ->
                confirmed = true
                val n = nameField.text.toString().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                val e = extField.text.toString().trim().trimStart('.').replace(Regex("[\\\\/:*?\"<>|\\s]"), "")
                val full = (if (n.isEmpty()) "download" else n) + (if (e.isEmpty()) "" else ".$e")
                continueToFolder(
                    Downloader.Request(
                        url = req.url, fileName = full, userAgent = req.userAgent,
                        referer = req.referer, cookies = req.cookies,
                        localFile = req.localFile, userNamed = true,
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                if (!confirmed) { busy = false; req.localFile?.delete() }
            }
            .create()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        nameField.requestFocus()
        nameField.setSelection(nameField.length()) // cursor at the end of the name
    }

    private fun continueToFolder(req: Downloader.Request) {
        pendingDownload = req
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
        busy = false
        val tree = data?.data
        if (resultCode == RESULT_OK && tree != null && req != null) downloader.start(req, tree)
        else req?.localFile?.delete() // picker cancelled
    }

    private companion object {
        const val REQ_FOLDER = 1
        const val REQ_NOTIFY = 2
        const val HOME = "https://www.google.com"
        const val Profile_DEFAULT = "Default"
        /** Remembers download="name" of page-generated (blob) links, even if the link is removed later. */
        const val NAME_HOOK = """
(function(){
  if (window.__dlHook) return; window.__dlHook = true;
  var names = window.__dlNames = {};
  function rec(a){
    try {
      if (a && a.download && a.href && a.href.indexOf('blob:') === 0) {
        names[a.href] = a.download; window.__dlLast = a.download; window.__dlLastT = Date.now();
      }
    } catch (e) {}
  }
  var oc = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function(){ rec(this); return oc.apply(this, arguments); };
  var od = EventTarget.prototype.dispatchEvent;
  EventTarget.prototype.dispatchEvent = function(ev){
    if (this instanceof HTMLAnchorElement && ev && ev.type === 'click') rec(this);
    return od.apply(this, arguments);
  };
  document.addEventListener('click', function(e){
    var t = e.target; rec(t && t.closest ? t.closest('a[download]') : null);
  }, true);
})();
"""
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
        if (::profiles.isInitialized) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        views.values.forEach { v -> (v.parent as? ViewGroup)?.removeView(v); v.destroy() }
        views.clear()
        super.onDestroy()
    }
}
