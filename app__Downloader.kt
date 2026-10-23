package dev.sk2andy.materialbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Step 8 (minimal): download a URL into a folder the user picked, with a progress notification. */
class Downloader(context: Context) {

    class Request(
        val url: String,
        val fileName: String,
        val userAgent: String?,
        val referer: String?,
        val cookies: String?,
        /** Already-fetched bytes (blob:/data: downloads). When set, nothing is downloaded. */
        val localFile: java.io.File? = null,
    )

    private val app = context.applicationContext
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger(1000)

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** [tree] is the folder returned by ACTION_OPEN_DOCUMENT_TREE. */
    fun start(req: Request, tree: Uri) {
        val id = ids.incrementAndGet()
        post(id, req.fileName, "Starting…", progress = -1)
        Thread { run(id, req, tree) }.start()
    }

    private fun run(id: Int, req: Request, tree: Uri) {
        var target: Uri? = null
        try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            val input: java.io.InputStream
            val total: Long
            var finalName = req.fileName
            if (req.localFile != null) {
                input = java.io.FileInputStream(req.localFile)
                total = req.localFile.length()
            } else {
                val conn = (URL(req.url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    req.userAgent?.let { setRequestProperty("User-Agent", it) }
                    req.cookies?.let { setRequestProperty("Cookie", it) }
                    req.referer?.let { setRequestProperty("Referer", it) }
                }
                if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
                // Real name comes from the final response (redirects, signed URLs).
                val cd = conn.getHeaderField("Content-Disposition")
                if (cd != null || req.fileName.endsWith(".bin")) {
                    finalName = android.webkit.URLUtil.guessFileName(
                        conn.url.toString(), cd, conn.contentType?.substringBefore(';')
                    )
                }
                input = conn.inputStream
                total = conn.contentLengthLong
            }
            // octet-stream keeps the exact name; the folder provider adds " (1)" if the name exists.
            target = DocumentsContract.createDocument(
                app.contentResolver, parent, "application/octet-stream", finalName
            ) ?: error("Could not create file in folder")
            var done = 0L
            var lastPct = -1

            input.use { input ->
                app.contentResolver.openOutputStream(target, "w")!!.use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                post(id, req.fileName, "$pct%", progress = pct)
                            }
                        }
                    }
                }
            }
            post(id, finalName, "Download complete", done = true)
        } catch (e: Exception) {
            target?.let { runCatching { DocumentsContract.deleteDocument(app.contentResolver, it) } }
            post(id, req.fileName, "Download failed: ${e.message ?: "error"}", done = true, failed = true)
        } finally {
            req.localFile?.delete()
        }
    }

    /** progress: 0..100, or -1 for indeterminate. done = final state (not ongoing). */
    private fun post(
        id: Int, title: String, text: String,
        progress: Int = -1, done: Boolean = false, failed: Boolean = false,
    ) {
        val b = Notification.Builder(app, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setSmallIcon(
                when {
                    failed -> android.R.drawable.stat_notify_error
                    done -> android.R.drawable.stat_sys_download_done
                    else -> android.R.drawable.stat_sys_download
                }
            )
        if (done) b.setOngoing(false).setAutoCancel(true)
        else b.setOngoing(true).setProgress(100, progress.coerceAtLeast(0), progress < 0)
        nm.notify(id, b.build())
    }

    fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }

    private companion object {
        const val CHANNEL = "downloads"
    }
}
