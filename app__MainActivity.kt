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
import android.provider.DocumentsContract
import android.widget.ImageView
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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.R as M

/** One tab = one WebView, belonging to exactly one profile. */
private class Tab(val profileId: String) {
    lateinit var webView: WebView
    var title: String = ""
    var loading: Boolean = false
}

/**
 * Top bar (one row): [ tab button | address bar | reload ] [back] [forward] [profile emoji] [download] [menu].
 * While the address bar has focus, everything else hides: the bar is all text field with
 * copy / cut / delete / select all / go / cancel icons at its right end.
 * Profiles: floating list under the emoji. Tabs: card grid under the tab button.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var addressBar: EditText
    private lateinit var progressBar: RgbProgressBar
    private lateinit var addressPill: LinearLayout
    private lateinit var backBtn: ImageView
    private lateinit var rightGroup: LinearLayout     // back, forward, profile, download, menu
    private lateinit var fwdBtn: ImageView
    private lateinit var gridFab: ExtendedFloatingActionButton
    private var nightMode = false
    private lateinit var topRow: FrameLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var editStrip: LinearLayout     // edit icons inside the pill, shown while the address bar has focus
    private lateinit var downloadBtn: DownloadButton
    private var currentUrl = ""                      // full URL of the shown tab (the bar itself shows only the site name)
    private val dlProgress = HashMap<Int, Int>()     // active download id -> percent (-1 = unknown)
    private var askedNotify = false
    private lateinit var reloadBtn: ImageView
    private val prefs by lazy { getSharedPreferences("ui", MODE_PRIVATE) }
    private var pendingTree: Uri? = null             // folder chosen while the notification permission is asked
    private var permReq: Downloader.Request? = null
    private var folderPickMode = 0
    private var dialogFolderChanged: (() -> Unit)? = null
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

    // ---- M3 theme helpers -------------------------------------------------------------------

    /** Resolves an M3 color role (e.g. M.attr.colorSurface) from the current theme. */
    private fun c(attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha shl 24)

    /** Ripple (state layer) in onSurface at 12%, drawn over [fill], clipped to a rounded rect. */
    private fun rippleBg(fill: Int, radiusDp: Int, stroke: Int = 0, strokeDp: Int = 0): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(withAlpha(c(M.attr.colorOnSurface), 31)),
            shape(fill, stroke, strokeDp, radiusDp),
            shape(Color.BLACK, radiusDp = radiusDp),
        )

    /** Round ripple for 48 dp icon buttons. */
    private fun circleRipple(): Drawable =
        RippleDrawable(
            ColorStateList.valueOf(withAlpha(c(M.attr.colorOnSurface), 31)),
            null,
            shape(Color.BLACK, radiusDp = 24),
        )

    /** One color for the status bar and the top bar. Change it here to restyle both. */
    private fun barColor(): Int = c(M.attr.colorSurfaceContainerHigh)

    private fun pillBg(focused: Boolean): Drawable =
        shape(
            c(M.attr.colorSurfaceContainerHigh),
            if (focused) c(androidx.appcompat.R.attr.colorPrimary) else 0,
            if (focused) 2 else 0,
            24,
        )

    /** kind: 0 = filled, 1 = tonal, 2 = text. Never takes focus (keeps the address bar focused). */
    private fun mBtn(label: String, kind: Int = 0, onClick: () -> Unit): MaterialButton {
        val style = if (kind == 2) android.R.attr.borderlessButtonStyle else M.attr.materialButtonStyle
        return MaterialButton(this, null, style).apply {
            text = label
            isFocusable = false
            isFocusableInTouchMode = false
            if (kind == 1) {
                backgroundTintList = ColorStateList.valueOf(c(M.attr.colorSecondaryContainer))
                setTextColor(c(M.attr.colorOnSecondaryContainer))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun say(msg: String) {
        runOnUiThread {
            if (::rootLayout.isInitialized) Snackbar.make(rootLayout, msg, Snackbar.LENGTH_SHORT).show()
            else downloader.toast(msg)
        }
    }

    private fun outlinedField(hintText: String): TextInputLayout =
        TextInputLayout(this, null, M.attr.textInputOutlinedStyle).apply { hint = hintText }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        DynamicColors.applyToActivityIfAvailable(this)   // wallpaper colors on Android 12+, static palette otherwise
        super.onCreate(savedInstanceState)
        nightMode = isNight()
        downloader = Downloader(this)
        cacheDir.listFiles()?.filter { it.name.startsWith("dl-") }?.forEach { it.delete() } // leftovers

        val surface = c(M.attr.colorSurface)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(barColor())   // shows behind the status bar, so bar + status bar match
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
        // System bar icons follow the surface: dark icons on a light surface, light icons on a dark one.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        val barMask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(
            if (MaterialColors.isColorLight(barColor())) barMask else 0, barMask,
        )

        // Every profile needs its own storage. Never fall back to shared storage.
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            root.addView(TextView(this).apply {
                text = "This phone's Android System WebView does not support separate profiles.\n\n" +
                    "Update \"Android System WebView\" (or Chrome) from the Play Store and open the app again."
                setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
                setTextColor(c(M.attr.colorOnSurface))
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
            setTextAppearance(M.style.TextAppearance_Material3_BodyLarge)
            setTextColor(c(M.attr.colorOnSurface))
            setHintTextColor(c(M.attr.colorOnSurfaceVariant))
            background = null                                      // plain text, no pill
            textSize = 15f
            setPadding(dp(8), 0, dp(8), 0)
            setOnEditorActionListener { v, actionId, event ->
                val enter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
                if (actionId == EditorInfo.IME_ACTION_GO || enter) {
                    submitAddress()
                    true
                } else false
            }
            // Tapped: show the full address (selected, ready to replace) and the Copy/Delete/Paste strip.
            setOnFocusChangeListener { v, focused ->
                setEditMode(focused)
                if (focused) {
                    setText(currentUrl)
                    post { selectAll() }
                } else {
                    setText(siteName(currentUrl))
                }
            }
        }
        tabBtn = TextView(this).apply {
            setTextAppearance(M.style.TextAppearance_Material3_LabelLarge)
            gravity = Gravity.CENTER
            setTextColor(c(M.attr.colorOnSurface))
            // Fluent tab icon (vector) inside a 40x48 touch area, tab count inside
            textSize = 11f
            background = InsetDrawable(
                androidx.core.content.ContextCompat.getDrawable(context, R.drawable.ic_tab)!!.mutate()
                    .also { it.setTint(c(M.attr.colorOnSurface)) },
                dp(7), dp(11), dp(7), dp(11))
            foreground = circleRipple()
            contentDescription = "Tabs"
            setOnClickListener {
                unfocusAddress()
                if (gridScroll.visibility == View.VISIBLE) gridScroll.visibility = View.GONE else openGrid()
            }
        }
        reloadBtn = iconView(R.drawable.ic_refresh, padH = 8).apply {
            contentDescription = "Reload"
            setOnClickListener {
                unfocusAddress()
                val t = current
                if (t != null && t.loading) {
                    t.webView.stopLoading()
                    t.loading = false
                    progressBar.visibility = View.GONE
                    updateReloadButton()
                } else t?.webView?.reload()
            }
        }
        profileBtn = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
            contentDescription = "Profiles"
            foreground = circleRipple()
            setOnClickListener { showProfileList() }
        }
        downloadBtn = DownloadButton(this).apply {
            foreground = circleRipple()
            setOnClickListener {
                unfocusAddress()
                say("Downloads are saved in your Downloads folder")
            }
        }
        downloader.listener = { id, pct, done, failed -> onDownloadEvent(id, pct, done, failed) }
        val menuBtn = iconView(R.drawable.ic_more_vert, padH = 8).apply {
            contentDescription = "Menu"
            setOnClickListener { showMenu(this) }
        }
        // Edit icons: shown at the right end of the bar while the address is being edited.
        fun editIcon(res: Int, desc: String, tint: Int = c(M.attr.colorOnSurfaceVariant), action: () -> Unit) =
            iconView(res, tint, 6, 12).apply {
                contentDescription = desc
                isFocusable = false; isFocusableInTouchMode = false   // never steal focus from the text field
                setOnClickListener { action() }
            }
        editStrip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            val lp = { LinearLayout.LayoutParams(dp(36), ViewGroup.LayoutParams.MATCH_PARENT) }
            addView(editIcon(R.drawable.ic_copy, "Copy") { copyAddress() }, lp())
            addView(editIcon(R.drawable.ic_content_cut, "Cut") { cutAddress() }, lp())
            addView(editIcon(R.drawable.ic_delete, "Delete") { addressBar.setText("") }, lp())
            addView(editIcon(R.drawable.ic_select_all, "Select all") { addressBar.selectAll() }, lp())
            addView(editIcon(R.drawable.ic_go, "Go", c(androidx.appcompat.R.attr.colorPrimary)) { submitAddress() }, lp())
            addView(editIcon(R.drawable.ic_close, "Cancel") { unfocusAddress() }, lp())
        }
        // plain address text (no pill) + the edit icons, shown while the address is being edited
        addressPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(addressBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(editStrip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        backBtn = iconView(R.drawable.ic_arrow_back, padH = 8).apply {
            contentDescription = "Back"
            setOnClickListener { unfocusAddress(); current?.webView?.takeIf { it.canGoBack() }?.goBack() }
        }
        fwdBtn = iconView(R.drawable.ic_arrow_forward, padH = 8).apply {
            contentDescription = "Forward"
            setOnClickListener { unfocusAddress(); current?.webView?.takeIf { it.canGoForward() }?.goForward() }
        }
        // order: refresh, back, forward, profile, download, menu. Sits ON TOP of the address text:
        // a long site name runs behind these buttons, with a short fade at their left edge.
        rightGroup = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(4), 0)
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(withAlpha(barColor(), 0), barColor(), barColor())).apply {
                setColors(intArrayOf(withAlpha(barColor(), 0), barColor(), barColor()), floatArrayOf(0f, 0.06f, 1f))
            }
            addView(reloadBtn, LinearLayout.LayoutParams(dp(40), dp(48)))
            addView(backBtn, LinearLayout.LayoutParams(dp(40), dp(48)))
            addView(fwdBtn, LinearLayout.LayoutParams(dp(40), dp(48)))
            addView(profileBtn, LinearLayout.LayoutParams(dp(40), dp(48)))
            addView(downloadBtn, LinearLayout.LayoutParams(dp(40), dp(48)))
            addView(menuBtn, LinearLayout.LayoutParams(dp(40), dp(48)))
        }
        // [ tab ] address text (runs under the buttons) ........ [ buttons ]
        topRow = FrameLayout(this).apply {
            setBackgroundColor(barColor())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(addressPill, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { marginStart = dp(44) })
            addView(tabBtn, FrameLayout.LayoutParams(dp(40), dp(48), Gravity.START))
            addView(rightGroup, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48), Gravity.END))
        }

        progressBar = RgbProgressBar(this).apply { visibility = View.GONE }

        webHolder = FrameLayout(this)
        gridBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(4), dp(10), dp(96))   // bottom room for the New tab button
        }
        gridFab = ExtendedFloatingActionButton(this).apply {
            text = "New tab"
            setIconResource(R.drawable.ic_add)
            contentDescription = "New tab"
            visibility = View.GONE
            setOnClickListener { selectTab(newTab(profiles.active(), HOME)) }
        }
        gridScroll = object : ScrollView(this) {
            // the New tab button lives and dies with the tab grid
            override fun onVisibilityChanged(changedView: View, visibility: Int) {
                super.onVisibilityChanged(changedView, visibility)
                if (changedView === this && ::gridFab.isInitialized) gridFab.visibility = visibility
            }
        }.apply {
            setBackgroundColor(c(M.attr.colorSurfaceContainerLow))
            visibility = View.GONE
            addView(gridBox)
        }
        val container = FrameLayout(this).apply {
            setBackgroundColor(surface)
            addView(webHolder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(gridScroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(gridFab, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(16), dp(16)) })
        }

        root.addView(topRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progressBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
        root.addView(container, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback
        )
        WebView.setWebContentsDebuggingEnabled(true) // diagnostics only
        showProfile(profiles.activeId)
        if (savedInstanceState == null) showSplashOverlay()
    }

    /**
     * Branded launch screen, drawn right after the system splash (same cream / berry background and the
     * same icon frame, so the hand-over is seamless): the icon animates, the app name rises in, a
     * four-colour line sweeps out, then everything fades away.
     */
    private fun showSplashOverlay() {
        val bg = if (nightMode) 0xFF1F0A14.toInt() else 0xFFFFF4E8.toInt()
        val ink = if (nightMode) 0xFFFFE3EC.toInt() else 0xFF5A0A2A.toInt()
        val overlay = FrameLayout(this).apply { setBackgroundColor(bg); isClickable = true }

        val icon = ImageView(this).apply { setImageResource(R.drawable.splash_icon_anim) }
        overlay.addView(icon, FrameLayout.LayoutParams(dp(240), dp(240), Gravity.CENTER))

        val name = TextView(this).apply {
            text = getString(R.string.app_name)
            setTextAppearance(M.style.TextAppearance_Material3_TitleLarge)
            setTextColor(ink)
            letterSpacing = 0.12f
            alpha = 0f
            translationY = dp(162).toFloat()
        }
        overlay.addView(name, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        val line = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFFFFD93B.toInt(), 0xFFFF8A1F.toInt(), 0xFFF0283C.toInt(), 0xFFFF5FA2.toInt()),
            ).apply { cornerRadius = dp(2).toFloat() }
            scaleX = 0f
            translationY = dp(196).toFloat()
        }
        overlay.addView(line, FrameLayout.LayoutParams(dp(64), dp(4), Gravity.CENTER))

        addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        (icon.drawable as? android.graphics.drawable.Animatable)?.start()
        name.animate().alpha(1f).translationY(dp(154).toFloat()).setStartDelay(250).setDuration(350).start()
        line.animate().scaleX(1f).setStartDelay(400).setDuration(450)
            .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)).start()
        overlay.animate().alpha(0f).setStartDelay(1100).setDuration(280).withEndAction {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }.start()
    }

    private fun isNight() =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    /** The chrome is colored once when built, so a light/dark switch rebuilds it (tabs reload their last page). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isNight() != nightMode) recreate()
    }

    /** A 24 dp vector icon inside a 48x48 touch area (padding makes the icon size). */
    private fun iconView(res: Int, tint: Int = c(M.attr.colorOnSurfaceVariant), padH: Int = 12, padV: Int = 12) =
        ImageView(this).apply {
            setImageResource(res)
            setColorFilter(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            foreground = circleRipple()
        }

    private fun updateReloadButton() {
        reloadBtn.setImageResource(if (current?.loading == true) R.drawable.ic_close else R.drawable.ic_refresh)
        reloadBtn.contentDescription = if (current?.loading == true) "Stop" else "Reload"
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
        updateNavButtons()
    }

    /** Back / Forward look disabled (38%) when the tab has nowhere to go. */
    private fun updateNavButtons() {
        if (!::backBtn.isInitialized) return
        val w = current?.webView
        backBtn.alpha = if (w?.canGoBack() == true) 1f else 0.38f
        fwdBtn.alpha = if (w?.canGoForward() == true) 1f else 0.38f
    }

    /** Editing: the whole bar becomes the text field with edit icons at its right; everything else hides. */
    private fun setEditMode(on: Boolean) {
        val other = if (on) View.GONE else View.VISIBLE
        tabBtn.visibility = other
        reloadBtn.visibility = other
        rightGroup.visibility = other
        editStrip.visibility = if (on) View.VISIBLE else View.GONE
        (addressPill.layoutParams as? FrameLayout.LayoutParams)?.marginStart = if (on) dp(10) else dp(44)
        addressPill.requestLayout()
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

    /** The selected part of the address, or all of it when nothing is selected. */
    private fun selectedOrAll(): String {
        val e = addressBar.text
        val a = minOf(addressBar.selectionStart, addressBar.selectionEnd).coerceAtLeast(0)
        val b = maxOf(addressBar.selectionStart, addressBar.selectionEnd).coerceAtLeast(0)
        return if (a != b) e.substring(a, b) else e.toString()
    }

    private fun copyAddress() {
        val text = selectedOrAll()
        if (text.isEmpty()) { say("Nothing to copy"); return }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("address", text))
        say("Copied")
    }

    private fun cutAddress() {
        val text = selectedOrAll()
        if (text.isEmpty()) { say("Nothing to cut"); return }
        val a = minOf(addressBar.selectionStart, addressBar.selectionEnd).coerceAtLeast(0)
        val b = maxOf(addressBar.selectionStart, addressBar.selectionEnd).coerceAtLeast(0)
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("address", text))
        if (a != b) addressBar.text.delete(a, b) else addressBar.setText("")
        say("Cut")
    }

    private fun pasteIntoAddress() {
        val clip = (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        if (text.isEmpty()) { say("Nothing to paste"); return }
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
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        lateinit var popup: PopupWindow
        for (p in profiles.profiles) {
            val isActive = p.id == profiles.activeId
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = rippleBg(if (isActive) c(M.attr.colorSecondaryContainer) else Color.TRANSPARENT, 28)
                setOnClickListener {
                    popup.dismiss()
                    if (p.id != profiles.activeId) showProfile(p.id)
                }
                setOnLongClickListener { popup.dismiss(); profileActions(p); true }
            }
            row.addView(TextView(this).apply { text = p.emoji; textSize = 22f; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(52), dp(52)))
            row.addView(TextView(this).apply {
                text = p.name; setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
                setTextAppearance(if (isActive) M.style.TextAppearance_Material3_LabelLarge else M.style.TextAppearance_Material3_BodyLarge)
                setTextColor(if (isActive) c(M.attr.colorOnSecondaryContainer) else c(M.attr.colorOnSurface))
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(iconView(R.drawable.ic_more_vert).apply {
                contentDescription = "Profile options"
                setOnClickListener { popup.dismiss(); profileActions(p) }
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rippleBg(Color.TRANSPARENT, 28)
            setOnClickListener { popup.dismiss(); addProfileDialog() }
            addView(iconView(R.drawable.ic_add, c(androidx.appcompat.R.attr.colorPrimary), 14, 14), LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(TextView(context).apply {
                text = "Add profile"
                setTextAppearance(M.style.TextAppearance_Material3_LabelLarge)
                setTextColor(c(androidx.appcompat.R.attr.colorPrimary))
            })
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        val scroll = ScrollView(this).apply {
            background = shape(c(M.attr.colorSurfaceContainer), radiusDp = 28)
            clipToOutline = true
            addView(box)
        }
        val w = dp(288)
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
        popup.showAsDropDown(profileBtn, 0, 0, Gravity.START)
    }

    private fun nameDialog(title: String, initial: String, onOk: (String) -> Unit) {
        val layout = outlinedField("Profile name")
        val input = TextInputEditText(layout.context).apply { setSingleLine(true); setText(initial); setSelection(length()) }
        layout.addView(input)
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(layout) }
        val d = MaterialAlertDialogBuilder(this)
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
            say("Profile limit reached (${ProfileList.MAX_PROFILES})"); return
        }
        nameDialog("New profile", "") { n ->
            val emoji = ProfileList.EMOJIS[profiles.profiles.size % ProfileList.EMOJIS.size]
            val p = profiles.add(n, emoji)
            showProfile(p.id)
        }
    }

    private fun profileActions(p: Profile) {
        MaterialAlertDialogBuilder(this)
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
        lateinit var dialog: androidx.appcompat.app.AlertDialog
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
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Change emoji").setView(grid)
            .setNegativeButton("Cancel", null).create()
        dialog.show()
    }

    private fun confirmDelete(p: Profile) {
        if (profiles.profiles.size <= 1) { say("Keep at least one profile"); return }
        MaterialAlertDialogBuilder(this)
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
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            val self = this
            setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
                onDownloadRequested(self, profile.id, url, userAgent, contentDisposition, mimeType)
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    tab.loading = true
                    view.settings.textZoom = zoomFor(url)
                    if (tab === current) { showUrl(url); updateReloadButton() }
                    view.evaluateJavascript(NAME_HOOK, null)
                }
                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    view.settings.textZoom = zoomFor(url)
                    if (tab === current) showUrl(url)
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    tab.loading = false
                    if (tab === current) { updateReloadButton(); updateNavButtons() }
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
                        say("No file picker available")
                        true
                    }
                }

                override fun onReceivedTitle(view: WebView, title: String?) {
                    tab.title = title.orEmpty()
                    if (gridScroll.visibility == View.VISIBLE) refreshGrid()
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    if (newProgress >= 100) tab.loading = false
                    if (tab !== current) return
                    updateReloadButton()
                    progressBar.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                    progressBar.setProgressAnimated(newProgress)
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
        updateReloadButton()
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
        val mine = tabs.filter { it.profileId == profiles.activeId }
        val p = profiles.active()
        gridBox.addView(TextView(this).apply {
            text = "${mine.size} ${if (mine.size == 1) "tab" else "tabs"}  ·  ${p.emoji} ${p.name}"
            setTextAppearance(M.style.TextAppearance_Material3_TitleMedium)
            setTextColor(c(M.attr.colorOnSurfaceVariant))
            setPadding(dp(10), dp(12), dp(10), dp(8))
        })
        for (pair in mine.map { tabCard(it) }.chunked(2)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            fun lp() = LinearLayout.LayoutParams(0, dp(132), 1f).apply { setMargins(dp(5), dp(5), dp(5), dp(5)) }
            pair.forEach { row.addView(it, lp()) }
            if (pair.size == 1) row.addView(View(this), lp())
            gridBox.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun tabCard(tab: Tab): View {
        val sel = tab === current
        val host = runCatching { Uri.parse(tab.webView.url).host }.getOrNull()?.removePrefix("www.").orEmpty()
        val label = tab.title.ifBlank { host.ifBlank { "New tab" } }
        return MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(if (sel) c(M.attr.colorSecondaryContainer) else c(M.attr.colorSurfaceContainerHigh))
            strokeColor = c(androidx.appcompat.R.attr.colorPrimary)
            strokeWidth = if (sel) dp(2) else 0
            isClickable = true
            isFocusable = true
            setOnClickListener { selectTab(tab) }
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(48), dp(14))
                addView(TextView(context).apply {
                    text = label
                    maxLines = 3
                    ellipsize = TextUtils.TruncateAt.END
                    setTextAppearance(M.style.TextAppearance_Material3_LabelLarge)
                    setTextColor(if (sel) c(M.attr.colorOnSecondaryContainer) else c(M.attr.colorOnSurface))
                })
                addView(View(context), LinearLayout.LayoutParams(1, 0, 1f))
                addView(TextView(context).apply {
                    text = host
                    setSingleLine(true)
                    ellipsize = TextUtils.TruncateAt.END
                    setTextAppearance(M.style.TextAppearance_Material3_LabelMedium)
                    setTextColor(c(M.attr.colorOnSurfaceVariant))
                })
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(iconView(R.drawable.ic_close).apply {
                contentDescription = "Close tab"
                setOnClickListener { closeTab(tab) }
            }, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END))
        }
    }

    private fun showMenu(anchor: View) {
        unfocusAddress()
        profilePopup?.dismiss()
        val onSurface = c(M.attr.colorOnSurface)
        val variant = c(M.attr.colorOnSurfaceVariant)
        val primary = c(androidx.appcompat.R.attr.colorPrimary)
        val w0 = current?.webView
        val host = hostKey(w0?.url)
        val profile = profiles.active()
        val full = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        lateinit var popup: PopupWindow

        fun label(t: String, style: Int, color: Int) = TextView(this).apply {
            text = t; setTextAppearance(style); setTextColor(color)
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        }
        fun section(t: String) = label(t, M.style.TextAppearance_Material3_LabelLarge, primary)
            .apply { setPadding(dp(8), dp(16), dp(8), dp(4)) }

        /** Header card: current profile and site. */
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(c(M.attr.colorPrimaryContainer), radiusDp = 24)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(c(M.attr.colorSurface)) }
                addView(TextView(context).apply { text = profile.emoji; textSize = 22f; gravity = Gravity.CENTER },
                    FrameLayout.LayoutParams(dp(48), dp(48)))
            }, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(label(profile.name, M.style.TextAppearance_Material3_TitleMedium, c(M.attr.colorOnPrimaryContainer)))
                addView(label(host ?: "New tab", M.style.TextAppearance_Material3_BodyMedium, c(M.attr.colorOnPrimaryContainer)).apply { alpha = 0.8f })
            }, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(16) })
        }
        box.addView(header, LinearLayout.LayoutParams(full, wrap))

        /** One menu row: tonal icon badge, title + subtitle, optional trailing view. */
        fun row(icon: Int, title: String, subtitle: String, trailing: View?, enabled: Boolean = true, onClick: () -> Unit): View {
            val badge = FrameLayout(this).apply {
                background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(c(M.attr.colorSecondaryContainer)) }
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    setColorFilter(c(M.attr.colorOnSecondaryContainer))
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                }, FrameLayout.LayoutParams(dp(42), dp(42)))
            }
            return LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                alpha = if (enabled) 1f else 0.38f
                background = rippleBg(Color.TRANSPARENT, 20)
                setPadding(dp(8), dp(8), dp(12), dp(8))
                addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)))
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(label(title, M.style.TextAppearance_Material3_BodyLarge, onSurface))
                    addView(label(subtitle, M.style.TextAppearance_Material3_BodySmall, variant))
                }, LinearLayout.LayoutParams(0, wrap, 1f).apply { marginStart = dp(16); marginEnd = dp(8) })
                if (trailing != null) addView(trailing)
                setOnClickListener { if (enabled) onClick() }
            }
        }

        box.addView(section("Page"))
        box.addView(row(R.drawable.ic_text_size, "Text size", host?.let { "Saved for $it" } ?: "Open a website first",
            label("${zoomFor(w0?.url)}%", M.style.TextAppearance_Material3_LabelLarge, primary), enabled = host != null) {
            popup.dismiss(); textSizeDialog()
        }, LinearLayout.LayoutParams(full, wrap))

        box.addView(section("Downloads"))
        val auto = prefs.getBoolean("dlAuto", false)
        val sw = MaterialSwitch(this).apply { isChecked = auto; isClickable = false; isFocusable = false }
        box.addView(row(R.drawable.ic_download, "Auto-download", "Save files without asking each time", sw) {
            val on = !sw.isChecked
            sw.isChecked = on                       // stays open so the switch animates
            prefs.edit().putBoolean("dlAuto", on).apply()
            say(if (on) "Auto-download on" else "Auto-download off")
        }, LinearLayout.LayoutParams(full, wrap))

        val scroll = ScrollView(this).apply {
            background = shape(c(M.attr.colorSurfaceContainerLow), radiusDp = 32)
            clipToOutline = true
            addView(box)
        }
        val w = minOf(dp(320), resources.displayMetrics.widthPixels - dp(24))
        box.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = minOf(box.measuredHeight + dp(4), (resources.displayMetrics.heightPixels * 0.8).toInt())
        popup = PopupWindow(scroll, w, h, true).apply {
            isOutsideTouchable = true
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            elevation = dp(12).toFloat()
        }
        profilePopup = popup
        popup.showAsDropDown(anchor, 0, 0, Gravity.END)
        // M3 emphasized-decelerate entrance: fade + scale from the top-right corner
        scroll.pivotX = w.toFloat(); scroll.pivotY = 0f
        scroll.alpha = 0f; scroll.scaleX = 0.9f; scroll.scaleY = 0.9f
        scroll.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220)
            .setInterpolator(android.view.animation.PathInterpolator(0.05f, 0.7f, 0.1f, 1f)).start()
    }

    // ---- text size, remembered per site ---------------------------------------------------

    /** "https://www.example.com/x" -> "example.com"; null for pages that are not websites. */
    private fun hostKey(url: String?): String? {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) return null
        return siteName(url).lowercase().takeIf { it.isNotBlank() }
    }

    private fun zoomFor(url: String?): Int = hostKey(url)?.let { prefs.getInt("zoom:$it", 100) } ?: 100

    /** Saves the size for a site and applies it to every open tab that shows that site. */
    private fun setSiteZoom(host: String, percent: Int) {
        val e = prefs.edit()
        if (percent == 100) e.remove("zoom:$host") else e.putInt("zoom:$host", percent)
        e.apply()
        tabs.forEach { if (hostKey(it.webView.url) == host) it.webView.settings.textZoom = percent }
    }

    private fun textSizeDialog() {
        val url = current?.webView?.url
        val host = hostKey(url)
        if (host == null) { say("Open a website first"); return }
        var updating = false

        val readout = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextAppearance(M.style.TextAppearance_Material3_DisplayMedium)
            setTextColor(c(M.attr.colorOnSurface))
        }
        val sub = TextView(this).apply {
            text = "for $host"; gravity = Gravity.CENTER
            setTextAppearance(M.style.TextAppearance_Material3_BodyMedium)
            setTextColor(c(M.attr.colorOnSurfaceVariant))
        }
        val slider = Slider(this).apply {
            valueFrom = ZOOM_MIN.toFloat(); valueTo = ZOOM_MAX.toFloat(); stepSize = 1f; value = 100f
        }
        val boxLayout = outlinedField("Exact size").apply { suffixText = "%" }
        val box = TextInputEditText(boxLayout.context).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        boxLayout.addView(box)

        fun setZoom(raw: Int, fromSeek: Boolean = false, fromBox: Boolean = false) {
            val v = raw.coerceIn(ZOOM_MIN, ZOOM_MAX)
            updating = true
            readout.text = "$v%"
            if (!fromSeek) slider.value = v.toFloat()
            if (!fromBox) box.setText(v.toString())
            updating = false
            setSiteZoom(host, v)
        }
        /** Out-of-range or empty input snaps to the nearest limit (or the current value). */
        fun commitBox() {
            val n = box.text.toString().toIntOrNull()
            setZoom(if (n == null) slider.value.toInt() else n.coerceIn(ZOOM_MIN, ZOOM_MAX))
        }

        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) setZoom(value.toInt(), fromSeek = true)
        }
        box.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (updating) return
                val n = e?.toString()?.toIntOrNull() ?: return
                if (n in ZOOM_MIN..ZOOM_MAX) setZoom(n, fromBox = true)
            }
        })
        box.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                commitBox()
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                    .hideSoftInputFromWindow(v.windowToken, 0)
                v.clearFocus()
                true
            } else false
        }
        box.setOnFocusChangeListener { _, focused -> if (!focused) commitBox() }

        val reset = mBtn("Reset", 1) { setZoom(100) }
        val exactRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(boxLayout, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(reset, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) })
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(4))
            addView(readout)
            addView(sub)
            addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(exactRow)
        }
        setZoom(zoomFor(url))
        MaterialAlertDialogBuilder(this)
            .setTitle("Text size")
            .setView(col)
            .setPositiveButton("Done") { _, _ -> commitBox() }
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
            say("Finish the current download first")
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
                say("Preparing download…")
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
                        onFail = { busy = false; say("Download failed: $it") },
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
                            say("Download failed: bad data URL")
                        } else {
                            val type = url.substring(5).substringBefore(',').substringBefore(';')
                            stagedFile(url, disposition, type.ifBlank { mime }, file)
                        }
                    }
                }.start()
            }
            else -> say("This download type is not supported yet")
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

    // ---- Save dialog and folder -----------------------------------------------------------

    private fun savedTree(): Uri? = prefs.getString("dlTree", null)?.let { Uri.parse(it) }

    /** True when we still hold write access to the remembered folder and it still exists. */
    private fun treeUsable(tree: Uri): Boolean {
        val granted = contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        if (!granted) return false
        return runCatching {
            val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        }.getOrDefault(false)
    }

    /** "primary:Download/Reports" -> "Internal storage/Download/Reports"; no tree = the public Downloads folder. */
    private fun folderLabel(tree: Uri?): String {
        if (tree == null) return "Internal storage/Download"
        val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull() ?: return "Selected folder"
        val root = if (id.substringBefore(':') == "primary") "Internal storage" else "SD card"
        val path = id.substringAfter(':', "").trim('/')
        return if (path.isEmpty()) root else "$root/$path"
    }

    private fun pickFolder(mode: Int) {
        folderPickMode = mode
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            ),
            REQ_FOLDER,
        )
    }

    /**
     * The Save pop-up. With "Download automatically next time" on, it is skipped and the file
     * goes straight to the remembered folder.
     */
    private fun askFolder(req: Downloader.Request) {
        if (prefs.getBoolean("dlAuto", false)) {
            val t = savedTree()
            if (t == null || treeUsable(t)) say("Saving to ${folderLabel(t)}…")
            startDownload(req)
            return
        }
        val dot = req.fileName.lastIndexOf('.')
        val baseName = if (dot > 0) req.fileName.substring(0, dot) else req.fileName
        val extName = if (dot > 0) req.fileName.substring(dot + 1) else ""
        val ink = c(M.attr.colorOnSurface)
        val grey = c(M.attr.colorOnSurfaceVariant)
        val primary = c(androidx.appcompat.R.attr.colorPrimary)

        val nameLayout = outlinedField("File name")
        val nameField = TextInputEditText(nameLayout.context).apply { setSingleLine(true); setText(baseName) }
        nameLayout.addView(nameField)
        val extLayout = outlinedField("File type").apply { placeholderText = "e.g. pdf" }
        val extField = TextInputEditText(extLayout.context).apply {
            setSingleLine(true); setText(extName)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        extLayout.addView(extField)
        var active: EditText = nameField
        nameField.setOnFocusChangeListener { _, f -> if (f) active = nameField }
        extField.setOnFocusChangeListener { _, f -> if (f) active = extField }

        fun chip(label: String, action: () -> Unit) = Chip(this).apply {
            text = label
            isFocusable = false; isFocusableInTouchMode = false   // never steal focus from the fields
            setOnClickListener { action() }
        }
        fun chipLp() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(chip("◀") { active.setSelection((active.selectionEnd - 1).coerceAtLeast(0)) }, chipLp())
            addView(chip("▶") { active.setSelection((active.selectionEnd + 1).coerceAtMost(active.length())) }, chipLp())
            addView(chip("Select all") { active.selectAll() }, chipLp())
        }

        // Header: download badge, title and the site the file comes from.
        val source = siteName(current?.webView?.url ?: req.url).ifBlank { "this page" }
        val badge = FrameLayout(this).apply {
            background = GradientDrawable().apply { setShape(GradientDrawable.OVAL); setColor(c(M.attr.colorPrimaryContainer)) }
            addView(iconView(R.drawable.ic_download, c(M.attr.colorOnPrimaryContainer), 12, 12).apply { foreground = null },
                FrameLayout.LayoutParams(dp(48), dp(48)))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(badge, LinearLayout.LayoutParams(dp(48), dp(48)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "Save file"
                    setTextAppearance(M.style.TextAppearance_Material3_TitleLarge); setTextColor(ink)
                })
                addView(TextView(context).apply {
                    text = "from $source"; setSingleLine(true)
                    setTextAppearance(M.style.TextAppearance_Material3_BodyMedium); setTextColor(grey)
                    ellipsize = TextUtils.TruncateAt.END
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
        }

        // Folder card: where the file goes, with a Change button.
        val folderText = TextView(this).apply {
            text = folderLabel(savedTree())
            setTextAppearance(M.style.TextAppearance_Material3_BodyMedium); setTextColor(ink)
            maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        dialogFolderChanged = { folderText.text = folderLabel(savedTree()) }
        val change = mBtn("Change", 2) { pickFolder(PICK_DIALOG) }
        val folderCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(c(M.attr.colorSurfaceContainerHigh), radiusDp = 16)
            setPadding(dp(16), dp(10), dp(8), dp(10))
            addView(iconView(R.drawable.ic_folder, primary, 0, 0).apply { foreground = null }, LinearLayout.LayoutParams(dp(24), dp(24)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    text = "Save to"
                    setTextAppearance(M.style.TextAppearance_Material3_LabelMedium); setTextColor(grey)
                })
                addView(folderText)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(16) })
            addView(change)
        }
        val autoSwitch = MaterialSwitch(this).apply {
            text = "Download automatically next time"; textSize = 14f; setTextColor(ink)
            setPadding(0, dp(14), 0, dp(4))
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(4))
            addView(header)
            addView(nameLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) })
            addView(extLayout, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(12) })
            addView(tools)
            addView(View(context), LinearLayout.LayoutParams(1, dp(14)))
            addView(folderCard)
            addView(autoSwitch)
        }
        val scroll = ScrollView(this).apply { addView(box) }

        var confirmed = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(scroll)
            .setPositiveButton("Download") { _, _ ->
                confirmed = true
                if (autoSwitch.isChecked) prefs.edit().putBoolean("dlAuto", true).apply()
                val n = nameField.text.toString().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
                val e = extField.text.toString().trim().trimStart('.').replace(Regex("[\\\\/:*?\"<>|\\s]"), "")
                val full = (if (n.isEmpty()) "download" else n) + (if (e.isEmpty()) "" else ".$e")
                startDownload(
                    Downloader.Request(
                        url = req.url, fileName = full, userAgent = req.userAgent,
                        referer = req.referer, cookies = req.cookies,
                        localFile = req.localFile, userNamed = true,
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener {
                dialogFolderChanged = null
                if (!confirmed) { busy = false; req.localFile?.delete() }
            }
            .create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        nameField.requestFocus()
        nameField.setSelection(nameField.length()) // cursor at the end of the name
    }

    /** Uses the remembered folder; if it is gone, asks for a folder again instead of failing. */
    private fun startDownload(req: Downloader.Request) {
        busy = false
        val tree = savedTree()
        if (tree != null && !treeUsable(tree)) {
            prefs.edit().remove("dlTree").apply()
            say("Saved folder is not available. Choose a folder")
            pendingDownload = req
            pickFolder(PICK_FOR_DOWNLOAD)
            return
        }
        launchDownload(req, tree)
    }

    private fun launchDownload(req: Downloader.Request, tree: Uri?) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !askedNotify) {
            // Ask once for the progress notification; the download starts right after the answer.
            askedNotify = true
            permReq = req
            pendingTree = tree
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        } else {
            downloader.start(req, tree, folderLabel(tree))
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_NOTIFY) {
            val req = permReq
            permReq = null
            if (req != null) downloader.start(req, pendingTree, folderLabel(pendingTree))
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_UPLOAD) {
            val cb = uploadCallback
            uploadCallback = null
            cb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            return
        }
        if (requestCode != REQ_FOLDER) return
        val mode = folderPickMode
        folderPickMode = 0
        val tree = data?.data
        if (resultCode == RESULT_OK && tree != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            prefs.edit().putString("dlTree", tree.toString()).apply()   // remembered for next time
            dialogFolderChanged?.invoke()
            if (mode == PICK_FOR_DOWNLOAD) {
                val req = pendingDownload
                pendingDownload = null
                if (req != null) launchDownload(req, tree)
            }
        } else if (mode == PICK_FOR_DOWNLOAD) {
            pendingDownload?.localFile?.delete()   // picker cancelled
            pendingDownload = null
        }
    }

    private companion object {
        const val REQ_FOLDER = 1
        const val REQ_NOTIFY = 2
        const val PICK_DIALOG = 1
        const val PICK_FOR_DOWNLOAD = 2
        const val ZOOM_MIN = 50
        const val ZOOM_MAX = 300
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
