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
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.PopupMenu
import android.widget.PopupWindow
import android.widget.ScrollView
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
import android.view.inputmethod.InputMethodManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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

/** One tab = one WebView, belonging to exactly one profile. */
private class Tab(val profileId: String) {
    lateinit var webView: WebView
    var title: String = ""
}

/**
 * Top bar: [address bar] [tab button] [profile emoji] [download] [menu].
 * Profiles: floating list under the emoji. Tabs: card grid under the tab button.
 */
class MainActivity : Activity() {

    private lateinit var addressBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var topRow: LinearLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var editStrip: LinearLayout     // Copy / Delete / Paste, shown while the address bar has focus
    private lateinit var downloadBtn: DownloadButton
    private var currentUrl = ""                      // full URL of the shown tab (the bar itself shows only the site name)
    private val dlProgress = HashMap<Int, Int>()     // active download id -> percent (-1 = unknown)
    private var askedNotify = false
    private var textZoom = 100                       // page text size in percent, saved across restarts
    private lateinit var tabBtn: TextView
    private lateinit var profileBtn: TextView
    private lateinit var webHolder: FrameLayout      // shows the selected tab's WebView
    private lateinit var gridScroll: ScrollView      // tab cards, drawn over the page
    private lateinit var gridBox: LinearLayout

    private lateinit var profiles: ProfileList
    private val tabs = ArrayList<Tab>()
    private val selected = HashMap<String, Tab>()    // profile id -> its selected tab
    private var current: Tab? = null
    private var profilePopup: PopupWindow? = null

    private lateinit var downloader: Downloader
    private var pendingDownload: Downloader.Request? = null
    private var busy = false
    private var uploadCallback: android.webkit.ValueCallback<Array<Uri>>? = null

    private val backCallback = OnBackInvokedCallback {
        val w = current?.webView
        when {
            profilePopup?.isShowing == true -> profilePopup?.dismiss()
            addressBar.hasFocus() -> unfocusAddress()
            gridScroll.visibility == View.VISIBLE -> gridScroll.visibility = View.GONE
            w != null && w.canGoBack() -> w.goBack()
            else -> finish()
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun shape(fill: Int, stroke: Int = 0, strokeDp: Int = 0, radiusDp: Int = 12) =
        GradientDrawable().apply {
            cornerRadius = dp(radiusDp).toFloat()
            setColor(fill)
            if (strokeDp > 0) setStroke(dp(strokeDp), stroke)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        downloader = Downloader(this)
        textZoom = getSharedPreferences("ui", MODE_PRIVATE).getInt("textZoom", 100)
        cacheDir.listFiles()?.filter { it.name.startsWith("dl-") }?.forEach { it.delete() } // leftovers

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            isFocusableInTouchMode = true   // lets us take focus away from the address bar
        }
        rootLayout = root
        // targetSdk 36 draws edge-to-edge, so keep content clear of system bars and keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        setContentView(root)
        // White bars, so the system icons (clock, battery, nav buttons) must be dark to be visible.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.insetsController?.setSystemBarsAppearance(
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )

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
            textSize = 16f
            setTextColor(0xFF1B1B1B.toInt())
            setHintTextColor(0xFF80868B.toInt())
            background = shape(0xFFF1F3F4.toInt(), radiusDp = 22)   // soft pill, no underline
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnEditorActionListener { v, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    submitAddress()
                    true
                } else false
            }
            // Tapped: show the full address (selected, ready to replace) and the Copy/Delete/Paste strip.
            setOnFocusChangeListener { v, focused ->
                editStrip.visibility = if (focused) View.VISIBLE else View.GONE
                if (focused) {
                    setText(currentUrl)
                    post { selectAll() }
                } else {
                    setText(siteName(currentUrl))
                }
            }
        }
        tabBtn = TextView(this).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(0xFF1B1B1B.toInt())
            background = shape(Color.WHITE, 0xFF5F6368.toInt(), 2, 8)   // rectangle with the tab count inside
            setOnClickListener {
                unfocusAddress()
                if (gridScroll.visibility == View.VISIBLE) gridScroll.visibility = View.GONE else openGrid()
            }
        }
        profileBtn = TextView(this).apply {
            textSize = 22f
            gravity = Gravity.CENTER
            setOnClickListener { showProfileList() }
        }
        downloadBtn = DownloadButton(this).apply {
            setOnClickListener {
                unfocusAddress()
                downloader.toast("Downloads are saved in your Downloads folder")
            }
        }
        downloader.listener = { id, pct, done, failed -> onDownloadEvent(id, pct, done, failed) }
        val menuBtn = TextView(this).apply {
            text = "⋮"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(0xFF1B1B1B.toInt())
            setOnClickListener { showMenu(this) }
        }
        topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            addView(addressBar, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(6) })
            addView(tabBtn, LinearLayout.LayoutParams(dp(34), dp(30)).apply { marginStart = dp(4); marginEnd = dp(4) })
            addView(profileBtn, LinearLayout.LayoutParams(dp(44), dp(48)))
            addView(downloadBtn, LinearLayout.LayoutParams(dp(40), dp(48)))
            addView(menuBtn, LinearLayout.LayoutParams(dp(36), dp(48)))
        }

        fun stripBtn(label: String, action: () -> Unit) = TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            isFocusable = false; isFocusableInTouchMode = false // never steal focus from the address bar
            setTextColor(0xFF1B1B1B.toInt())
            background = shape(0xFFEEF0EC.toInt(), radiusDp = 18)
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { action() }
        }
        editStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(8))
            visibility = View.GONE
            val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) } }
            addView(stripBtn("Copy") { copyAddress() }, lp())
            addView(stripBtn("Delete") { addressBar.setText("") }, lp())
            addView(stripBtn("Paste") { pasteIntoAddress() }, lp())
            addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))   // pushes Close to the right edge
            addView(stripBtn("Go") { submitAddress() }.apply {
                background = shape(0xFFB7F0BE.toInt(), radiusDp = 18)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, lp())
            addView(stripBtn("✕  Close") { unfocusAddress() }.apply {
                background = shape(0xFFDADDE0.toInt(), radiusDp = 18)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.GONE
            progressTintList = android.content.res.ColorStateList.valueOf(0xFF2E9E4F.toInt())
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        }

        webHolder = FrameLayout(this)
        gridBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        gridScroll = ScrollView(this).apply {
            setBackgroundColor(0xFFF6F6F4.toInt())
            visibility = View.GONE
            addView(gridBox)
        }
        val container = FrameLayout(this).apply {
            addView(webHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(gridScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }

        root.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(editStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback
        )
        WebView.setWebContentsDebuggingEnabled(true) // diagnostics only
        showProfile(profiles.activeId)
    }

    // ---- address bar ----------------------------------------------------------------------

    /** "https://www.example.com/a?b=1" -> "example.com". Non-web addresses are shown as they are. */
    private fun siteName(url: String?): String {
        if (url.isNullOrBlank() || url == "about:blank") return ""
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        return if (host.isNullOrBlank()) url else host.removePrefix("www.")
    }

    /** Remember the tab's real URL; the bar shows only the site name (unless the user is typing). */
    private fun showUrl(url: String?) {
        currentUrl = url.orEmpty()
        if (!addressBar.hasFocus()) addressBar.setText(siteName(url))
    }

    private fun unfocusAddress() {
        if (!addressBar.hasFocus()) return
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(addressBar.windowToken, 0)
        rootLayout.requestFocus()   // focus listener restores the short site name and hides the strip
    }

    /** Opens what is typed in the bar (address or search) in the current tab. */
    private fun submitAddress() {
        val target = toUrl(addressBar.text.toString())
        currentUrl = target
        current?.webView?.loadUrl(target)
        unfocusAddress()
    }

    private fun copyAddress() {
        val text = addressBar.text.toString()
        if (text.isEmpty()) { downloader.toast("Nothing to copy"); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("address", text))
        downloader.toast("Copied")
    }

    private fun pasteIntoAddress() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        if (text.isEmpty()) { downloader.toast("Nothing to paste"); return }
        val e = addressBar.text
        val a = addressBar.selectionStart.coerceAtLeast(0)
        val b = addressBar.selectionEnd.coerceAtLeast(0)
        e.replace(minOf(a, b), maxOf(a, b), text)
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

    /** Switches to a profile: shows that profile's selected tab (or opens its first one). */
    private fun showProfile(id: String) {
        profiles.setActive(id)
        val profile = profiles.active()
        val tab = selected[id]?.takeIf { it in tabs }
            ?: tabs.lastOrNull { it.profileId == id }
            ?: newTab(profile, profiles.lastUrl(id) ?: HOME)
        selectTab(tab)
    }

    private fun showProfileList() {
        unfocusAddress()
        profilePopup?.dismiss()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        lateinit var popup: PopupWindow
        for (p in profiles.profiles) {
            val isActive = p.id == profiles.activeId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = shape(if (isActive) 0xFFB7F0BE.toInt() else Color.TRANSPARENT, radiusDp = 10)
                setOnClickListener {
                    popup.dismiss()
                    if (p.id != profiles.activeId) showProfile(p.id)
                }
                setOnLongClickListener { popup.dismiss(); profileActions(p); true }
            }
            row.addView(TextView(this).apply { text = p.emoji; textSize = 22f; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(44), dp(48)))
            row.addView(TextView(this).apply {
                text = p.name; textSize = 16f; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF1B1B1B.toInt())
                if (isActive) setTypeface(typeface, android.graphics.Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(TextView(this).apply {
                text = "⋮"; textSize = 20f; gravity = Gravity.CENTER
                setTextColor(0xFF1B1B1B.toInt())
                setOnClickListener { popup.dismiss(); profileActions(p) }
            }, LinearLayout.LayoutParams(dp(40), dp(48)))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(TextView(this).apply {
            text = "＋  Add profile"; textSize = 16f
            setTextColor(0xFF1B1B1B.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { popup.dismiss(); addProfileDialog() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val scroll = ScrollView(this).apply {
            background = shape(Color.WHITE, 0xFFCCCCCC.toInt(), 1, 14)
            addView(box)
        }
        val w = dp(260)
        box.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = minOf(box.measuredHeight + dp(4), (resources.displayMetrics.heightPixels * 0.6).toInt())
        popup = PopupWindow(scroll, w, h, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(8).toFloat()
        }
        profilePopup = popup
        popup.showAsDropDown(topRow, 0, 0, Gravity.END)
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
            val emoji = ProfileList.EMOJIS[profiles.profiles.size % ProfileList.EMOJIS.size]
            val p = profiles.add(n, emoji)
            showProfile(p.id)
        }
    }

    private fun profileActions(p: Profile) {
        android.app.AlertDialog.Builder(this)
            .setTitle("${p.emoji}  ${p.name}")
            .setItems(arrayOf("Rename", "Change emoji", "Delete")) { _, which ->
                when (which) {
                    0 -> nameDialog("Rename profile", p.name) { n -> profiles.rename(p.id, n) }
                    1 -> emojiDialog(p)
                    else -> confirmDelete(p)
                }
            }.show()
    }

    private fun emojiDialog(p: Profile) {
        lateinit var dialog: android.app.AlertDialog
        val grid = GridLayout(this).apply {
            columnCount = 6
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        for (e in ProfileList.EMOJIS) {
            grid.addView(TextView(this).apply {
                text = e; textSize = 26f; gravity = Gravity.CENTER
                setOnClickListener {
                    profiles.setEmoji(p.id, e)
                    updateProfileButton()
                    dialog.dismiss()
                }
            }, GridLayout.LayoutParams().apply { width = dp(48); height = dp(48) })
        }
        dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Change emoji").setView(grid)
            .setNegativeButton("Cancel", null).create()
        dialog.show()
    }

    private fun confirmDelete(p: Profile) {
        if (profiles.profiles.size <= 1) { downloader.toast("Keep at least one profile"); return }
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete \"${p.name}\"?")
            .setMessage("Its tabs, logins and site data will be erased. Other profiles are not touched.")
            .setPositiveButton("Delete") { _, _ -> deleteProfile(p) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun deleteProfile(p: Profile) {
        // 1) close its tabs and destroy their WebViews, 2) forget the profile, 3) erase its storage.
        val wasActive = p.id == profiles.activeId
        tabs.filter { it.profileId == p.id }.forEach { destroyTab(it) }
        selected.remove(p.id)
        if (current?.profileId == p.id) current = null
        profiles.remove(p.id)
        if (wasActive) showProfile(profiles.activeId) else updateTabButton()
        deleteStorage(p.id, 0)
    }

    /** The store refuses to delete a profile that is still in use, so retry shortly. */
    private fun deleteStorage(id: String, attempt: Int) {
        val ok = runCatching { ProfileStore.getInstance().deleteProfile(id) }.isSuccess
        if (!ok && attempt < 5) {
            Handler(Looper.getMainLooper()).postDelayed({ deleteStorage(id, attempt + 1) }, 500)
        } // anything left is removed at next start by removeOrphanStorage()
    }

    private fun updateProfileButton() {
        profileBtn.text = profiles.active().emoji
    }

    // ---- tabs -----------------------------------------------------------------------------

    private fun createWebView(tab: Tab, profile: Profile): WebView {
        ProfileStore.getInstance().getOrCreateProfile(profile.id)
        return WebView(this).apply {
            WebViewCompat.setProfile(this, profile.id) // first call: before any loading
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.textZoom = textZoom
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            val self = this
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                onDownloadRequested(self, profile.id, url, userAgent, contentDisposition, mimeType)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    if (tab === current) showUrl(url)
                    view.evaluateJavascript(NAME_HOOK, null)
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    if (tab === current) showUrl(url)
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    view.evaluateJavascript(NAME_HOOK, null)
                    if (url != null && url != "about:blank" && selected[tab.profileId] === tab) {
                        profiles.saveUrl(profile.id, url)
                    }
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    view: WebView,
                    callback: android.webkit.ValueCallback<Array<Uri>>,
                    params: WebChromeClient.FileChooserParams,
                ): Boolean {
                    uploadCallback?.onReceiveValue(null) // a previous chooser must always be answered
                    uploadCallback = callback
                    return try {
                        startActivityForResult(params.createIntent(), REQ_UPLOAD)
                        true
                    } catch (e: Exception) {
                        uploadCallback = null
                        callback.onReceiveValue(null)
                        downloader.toast("No file picker available")
                        true
                    }
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    tab.title = title.orEmpty()
                    if (gridScroll.visibility == View.VISIBLE) refreshGrid()
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (tab !== current) return
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                }
            }
        }
    }

    /** New tab in a profile. It loads right away but is only shown once selected. */
    private fun newTab(profile: Profile, url: String): Tab {
        val tab = Tab(profile.id)
        tab.webView = createWebView(tab, profile)
        tabs.add(tab)
        tab.webView.loadUrl(url)
        return tab
    }

    private fun selectTab(tab: Tab, hideGrid: Boolean = true) {
        current?.webView?.onPause()
        current = tab
        selected[tab.profileId] = tab
        webHolder.removeAllViews()
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        webHolder.addView(tab.webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        tab.webView.onResume()
        progressBar.visibility = View.GONE
        unfocusAddress()
        showUrl(tab.webView.url)
        if (hideGrid) gridScroll.visibility = View.GONE
        updateProfileButton()
        updateTabButton()
    }

    private fun destroyTab(tab: Tab) {
        tabs.remove(tab)
        (tab.webView.parent as? ViewGroup)?.removeView(tab.webView)
        tab.webView.destroy()
    }

    private fun closeTab(tab: Tab) {
        val wasCurrent = tab === current
        val profile = profiles.profiles.first { it.id == tab.profileId }
        destroyTab(tab)
        if (selected[tab.profileId] === tab) selected.remove(tab.profileId)
        if (wasCurrent) current = null
        val left = tabs.filter { it.profileId == tab.profileId }
        if (left.isEmpty()) selectTab(newTab(profile, HOME), hideGrid = false) // a profile always has a tab
        else if (wasCurrent) selectTab(left.last(), hideGrid = false)
        updateTabButton()
        refreshGrid()
    }

    private fun updateTabButton() {
        tabBtn.text = tabs.count { it.profileId == profiles.activeId }.toString()
    }

    private fun openGrid() {
        refreshGrid()
        gridScroll.visibility = View.VISIBLE
    }

    private fun refreshGrid() {
        gridBox.removeAllViews()
        val cards: List<View> = tabs.filter { it.profileId == profiles.activeId }.map { tabCard(it) } + plusCard()
        for (pair in cards.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun lp() = LinearLayout.LayoutParams(0, dp(110), 1f).apply { setMargins(dp(6), dp(6), dp(6), dp(6)) }
            pair.forEach { row.addView(it, lp()) }
            if (pair.size == 1) row.addView(View(this), lp())
            gridBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun tabCard(tab: Tab): View {
        val sel = tab === current
        val label = tab.title.ifBlank {
            runCatching { Uri.parse(tab.webView.url).host }.getOrNull() ?: "New tab"
        }
        return FrameLayout(this).apply {
            background = shape(Color.WHITE, if (sel) 0xFF2E9E4F.toInt() else 0xFFCCCCCC.toInt(), if (sel) 2 else 1)
            setOnClickListener { selectTab(tab) }
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(0xFF1B1B1B.toInt())
                setPadding(dp(12), dp(12), dp(44), dp(12))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(TextView(context).apply {
                text = "✕"
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(0xFF1B1B1B.toInt())
                setOnClickListener { closeTab(tab) }
            }, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END))
        }
    }

    private fun plusCard(): View = TextView(this).apply {
        text = "+"
        textSize = 34f
        gravity = Gravity.CENTER
        setTextColor(0xFF1B1B1B.toInt())
        background = shape(0xFFE6E9E0.toInt(), radiusDp = 12)
        setOnClickListener { selectTab(newTab(profiles.active(), HOME)) }
    }

    private fun showMenu(anchor: View) {
        unfocusAddress()
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Forward")
            menu.add(0, 2, 1, "Reload")
            menu.add(0, 3, 2, "Text size")
            setOnMenuItemClickListener {
                val w = current?.webView
                when (it.itemId) {
                    1 -> if (w?.canGoForward() == true) w.goForward()
                    2 -> w?.reload()
                    3 -> textSizeDialog()
                }
                true
            }
            show()
        }
    }

    private fun setTextZoom(percent: Int) {
        textZoom = percent.coerceIn(50, 250)
        getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("textZoom", textZoom).apply()
        tabs.forEach { it.webView.settings.textZoom = textZoom }
    }

    /** Page text size: A- / A+ in steps of 10%, applied to every tab right away. */
    private fun textSizeDialog() {
        val label = TextView(this).apply {
            textSize = 20f; gravity = Gravity.CENTER
            setTextColor(0xFF1B1B1B.toInt())
            text = "$textZoom%"
        }
        fun step(txt: String, delta: Int) = TextView(this).apply {
            text = txt; textSize = 22f; gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF1B1B1B.toInt())
            background = shape(0xFFEEF0EC.toInt(), radiusDp = 14)
            setOnClickListener { setTextZoom(textZoom + delta); label.text = "$textZoom%" }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
            addView(step("A−", -10), LinearLayout.LayoutParams(dp(64), dp(52)))
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(step("A+", 10), LinearLayout.LayoutParams(dp(64), dp(52)))
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Text size")
            .setView(row)
            .setNeutralButton("Reset") { _, _ -> setTextZoom(100) }
            .setPositiveButton("Done", null)
            .show()
    }

    /** Drives the top-bar download button from the downloader's events. */
    private fun onDownloadEvent(id: Int, pct: Int, done: Boolean, failed: Boolean) {
        if (done) dlProgress.remove(id) else dlProgress[id] = pct
        if (dlProgress.isNotEmpty()) {
            val known = dlProgress.values.filter { it >= 0 }
            if (known.isEmpty() || known.size < dlProgress.size && known.all { it == 0 }) downloadBtn.showWaiting()
            else downloadBtn.showProgress(known.average().toFloat() / 100f)
        } else downloadBtn.finish(success = !failed)
    }

    /** Every download: get the bytes (blob/data are fetched right away), then show the "Save file as" pop-up. */
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

    /** Blob/data bytes are already in a temp file; now only the name pop-up is missing. */
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

    /** The "Save file as" pop-up always appears; tapping Download saves straight into Downloads. */
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
                beginDownload(
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

    private fun beginDownload(req: Downloader.Request) {
        busy = false
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !askedNotify) {
            // Ask once for the progress notification; the download starts right after the answer.
            askedNotify = true
            pendingDownload = req
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        } else {
            downloader.start(req)
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_NOTIFY) {
            val req = pendingDownload
            pendingDownload = null
            if (req != null) downloader.start(req)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UPLOAD) {
            val cb = uploadCallback
            uploadCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
        }
    }

    private companion object {
        const val REQ_NOTIFY = 2
        const val REQ_UPLOAD = 3
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
        uploadCallback?.onReceiveValue(null)
        uploadCallback = null
        profilePopup?.dismiss()
        if (::profiles.isInitialized) onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        tabs.toList().forEach { destroyTab(it) }
        super.onDestroy()
    }
}
