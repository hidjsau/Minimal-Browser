package dev.sk2andy.materialbrowser

import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Downloads a URL straight into the public Downloads folder (no folder picker), with a progress notification. */
class Downloader(context: Context) {

    class Request(
        val url: String,
        val fileName: String,
        val userAgent: String?,
        val referer: String?,
        val cookies: String?,
        /** Already-fetched bytes (blob:/data: downloads). When set, nothing is downloaded. */
        val localFile: java.io.File? = null,
        /** Name was chosen by the user in the rename dialog: never replace it. */
        val userNamed: Boolean = false,
    )

    private val app = context.applicationContext
    private val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger(1000)

    /**
     * Progress feedback for the UI, always called on the main thread.
     * pct: 0..100, or -1 when unknown. done = this download has ended; failed = it ended with an error.
     */
    var listener: ((id: Int, pct: Int, done: Boolean, failed: Boolean) -> Unit)? = null

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun start(req: Request) {
        val id = ids.incrementAndGet()
        post(id, req.fileName, "Starting…", progress = -1)
        Thread { run(id, req) }.start()
    }

    private fun run(id: Int, req: Request) {
        var target: Uri? = null
        try {
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
                if (!req.userNamed && (cd != null || req.fileName.endsWith(".bin"))) {
                    finalName = android.webkit.URLUtil.guessFileName(
                        conn.url.toString(), cd, conn.contentType?.substringBefore(';')
                    )
                }
                input = conn.inputStream
                total = conn.contentLengthLong
            }
            finalName = uniqueName(finalName)
            // octet-stream keeps the exact name (MediaStore does not append an extension to it).
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            target = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Could not create file in Downloads")
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
            // Publish the file: it becomes visible in the Files app / Downloads only now.
            app.contentResolver.update(
                target, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null
            )
            post(id, finalName, "Saved to Downloads", done = true)
        } catch (e: Exception) {
            target?.let { runCatching { app.contentResolver.delete(it, null, null) } }
            post(id, req.fileName, "Download failed: ${e.message ?: "error"}", done = true, failed = true)
        } finally {
            req.localFile?.delete()
        }
    }

    /** "xyz.mp4" exists -> "xyz(1).mp4": the number goes before the last dot, never after the extension. */
    private fun uniqueName(name: String): String {
        fun exists(n: String): Boolean = runCatching {
            app.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(n, Environment.DIRECTORY_DOWNLOADS + "/"),
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (exists("$base($i)$ext")) i++
        return "$base($i)$ext"
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
        if (done) {
            b.setOngoing(false).setAutoCancel(true)
            if (!failed) {
                // Tapping the finished notification opens the system Downloads list.
                b.setContentIntent(
                    PendingIntent.getActivity(
                        app, id, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
            }
        } else b.setOngoing(true).setProgress(100, progress.coerceAtLeast(0), progress < 0)
        nm.notify(id, b.build())
        main.post { listener?.invoke(id, if (done) 100 else progress, done, failed) }
    }

    fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }

    private companion object {
        const val CHANNEL = "downloads"
    }
}
