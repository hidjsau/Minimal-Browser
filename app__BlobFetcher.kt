package dev.sk2andy.materialbrowser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reads a blob: URL into a temp file.
 * A blob only exists inside the page that made it, so a hidden helper WebView is loaded with the
 * page's own origin, fetch()es the blob there and hands the bytes back in base64 chunks.
 * Call fetch() on the main thread. Callbacks run on the main thread.
 */
class BlobFetcher(private val context: Context, private val profileId: String? = null) {

    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    fun fetch(
        blobUrl: String,
        pageUrl: String,
        onDone: (file: File, mimeType: String?) -> Unit,
        onFail: (String) -> Unit,
    ) {
        val file = File(context.cacheDir, "dl-${System.nanoTime()}.tmp")
        val finished = AtomicBoolean(false)
        val helper = WebView(context)
        // Same profile as the tab, or the blob would be read from the wrong storage.
        profileId?.let { runCatching { androidx.webkit.WebViewCompat.setProfile(helper, it) } }
        var mime: String? = null
        var out: FileOutputStream? = null
        lateinit var timeout: Runnable

        fun finish(error: String?) {
            if (!finished.compareAndSet(false, true)) return
            main.removeCallbacks(timeout)
            runCatching { out?.close() }
            main.post {
                runCatching { helper.stopLoading() }
                runCatching { helper.removeJavascriptInterface(BRIDGE) }
                runCatching { helper.destroy() }
                if (error == null) onDone(file, mime)
                else {
                    file.delete()
                    onFail(error)
                }
            }
        }
        timeout = Runnable { finish("timed out") }

        helper.settings.javaScriptEnabled = true
        helper.settings.allowFileAccess = false
        helper.settings.allowContentAccess = false
        helper.addJavascriptInterface(object {
            @JavascriptInterface fun begin(type: String?) {
                mime = type?.takeIf { it.isNotBlank() }
                out = FileOutputStream(file)
            }
            @JavascriptInterface fun chunk(b64: String) {
                runCatching { out!!.write(Base64.decode(b64, Base64.DEFAULT)) }
                    .onFailure { finish("write error") }
                main.removeCallbacks(timeout); main.postDelayed(timeout, IDLE_MS)
            }
            @JavascriptInterface fun end() = finish(null)
            @JavascriptInterface fun fail(msg: String?) = finish(msg ?: "error")
        }, BRIDGE)

        var started = false
        helper.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                if (started) return
                started = true
                view.evaluateJavascript(script(blobUrl), null)
            }
        }
        main.postDelayed(timeout, IDLE_MS)
        // Same origin as the page that created the blob, but only an empty document is loaded.
        helper.loadDataWithBaseURL(pageUrl, "<html></html>", "text/html", "utf-8", null)
    }

    private fun script(blobUrl: String) = """
        (async () => {
          try {
            const r = await fetch(${JSONObject.quote(blobUrl)});
            const b = await r.blob();
            $BRIDGE.begin(b.type);
            const CH = 384 * 1024;
            for (let o = 0; o < b.size; o += CH) {
              const u = new Uint8Array(await b.slice(o, o + CH).arrayBuffer());
              let s = '';
              for (let i = 0; i < u.length; i += 0x8000)
                s += String.fromCharCode.apply(null, u.subarray(i, i + 0x8000));
              $BRIDGE.chunk(btoa(s));
            }
            $BRIDGE.end();
          } catch (e) { $BRIDGE.fail(String(e)); }
        })();
    """.trimIndent()

    private companion object {
        const val BRIDGE = "MinDl"
        const val IDLE_MS = 60_000L
    }
}
