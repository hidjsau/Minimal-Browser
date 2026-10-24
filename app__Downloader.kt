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
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads a URL into a folder with a progress notification card.
 * tree == null  -> the public Downloads folder (no picker, no permission needed).
 * tree != null  -> a folder the user picked (ACTION_OPEN_DOCUMENT_TREE).
 */
class Downloader(context: Context) {

    class Request(
        val url: String,
        val fileName: String,
        val userAgent: String?,
        val referer: String?,
        val cookies: String?,
        /** Already-fetched bytes (blob:/data: downloads). When set, nothing is downloaded. */
        val localFile: java.io.File? = null,
        /** Name was chosen by the user in the Save dialog: never replace it. */
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

    /** [destLabel] is the human-readable folder shown on the notification card. */
    fun start(req: Request, tree: Uri?, destLabel: String) {
        val id = ids.incrementAndGet()
        post(id, req.fileName, destLabel, "Starting…", progress = -1)
        Thread { run(id, req, tree, destLabel) }.start()
    }

    private fun run(id: Int, req: Request, tree: Uri?, dest: String) {
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

            val created: Uri
            if (tree != null) {
                finalName = uniqueNameInTree(tree, finalName)
                val parent = DocumentsContract.buildDocumentUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree)
                )
                // octet-stream keeps the exact name; we already made it unique.
                created = DocumentsContract.createDocument(
                    app.contentResolver, parent, "application/octet-stream", finalName
                ) ?: error("Could not create file in folder")
            } else {
                finalName = uniqueNameInDownloads(finalName)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, finalName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                created = app.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create file in Downloads")
            }
            target = created

            var done = 0L
            var lastPct = -1
            var lastPosted = 0L
            input.use { inp ->
                (app.contentResolver.openOutputStream(created, "w") ?: error("Could not write the file")).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                post(id, req.fileName, dest, "$pct% · ${fmt(done)} of ${fmt(total)}", progress = pct)
                            }
                        } else if (done - lastPosted >= 256 * 1024) {
                            lastPosted = done
                            post(id, req.fileName, dest, fmt(done), progress = -1)
                        }
                    }
                }
            }
            if (tree == null) {
                // Publish the file: it becomes visible in the Files app only now.
                app.contentResolver.update(
                    created, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null
                )
            }
            post(id, finalName, dest, "Download complete · ${fmt(done)} · Tap to open", done = true, open = created)
        } catch (e: Exception) {
            target?.let {
                runCatching {
                    if (tree != null) DocumentsContract.deleteDocument(app.contentResolver, it)
                    else app.contentResolver.delete(it, null, null)
                }
            }
            post(id, req.fileName, dest, "Download failed: ${e.message ?: "error"}", done = true, failed = true)
        } finally {
            req.localFile?.delete()
        }
    }

    private fun fmt(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    }

    private fun nextFree(name: String, exists: (String) -> Boolean): String {
        if (!exists(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while (exists("$base($i)$ext")) i++
        return "$base($i)$ext"
    }

    /** "xyz.mp4" exists -> "xyz(1).mp4": the number goes before the last dot, never after the extension. */
    private fun uniqueNameInDownloads(name: String): String = nextFree(name) { n ->
        runCatching {
            app.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(n, Environment.DIRECTORY_DOWNLOADS + "/"),
                null,
            )?.use { it.count > 0 } ?: false
        }.getOrDefault(false)
    }

    private fun uniqueNameInTree(tree: Uri, name: String): String {
        val existing = HashSet<String>()
        runCatching {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            app.contentResolver.query(
                kids, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { existing.add(it) } }
        }
        return nextFree(name) { it in existing }
    }

    private fun openIntent(id: Int, uri: Uri, fileName: String): PendingIntent {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return PendingIntent.getActivity(app, id, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** progress: 0..100, or -1 for indeterminate. done = final state (not ongoing). */
    private fun post(
        id: Int, title: String, dest: String, text: String,
        progress: Int = -1, done: Boolean = false, failed: Boolean = false, open: Uri? = null,
    ) {
        val b = Notification.Builder(app, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(dest)                 // destination folder
            .setColor(ACCENT)                 // theme colour
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
                val pi = if (open != null) openIntent(id, open, title) else PendingIntent.getActivity(
                    app, id, Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                b.setContentIntent(pi)
            }
        } else b.setOngoing(true).setProgress(100, progress.coerceAtLeast(0), progress < 0)
        nm.notify(id, b.build())
        main.post { listener?.invoke(id, if (done) 100 else progress, done, failed) }
    }

    fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }

    private companion object {
        const val CHANNEL = "downloads"
        const val ACCENT = 0xFF2E9E4F.toInt()
    }
}
