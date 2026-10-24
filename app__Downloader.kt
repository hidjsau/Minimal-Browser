package dev.sk2andy.materialbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

/** Step 8 (minimal): download a URL into a folder the user picked, with a progress notification. */
class Downloader(context: Context, private val accent: Int = 0) {

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

    init {
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    /** [tree] is the folder returned by ACTION_OPEN_DOCUMENT_TREE. */
    fun start(req: Request, tree: Uri) {
        val id = ids.incrementAndGet()
        post(id, req.fileName, "Starting…", folderLabel(tree), progress = -1)
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
                if (!req.userNamed && (cd != null || req.fileName.endsWith(".bin"))) {
                    finalName = android.webkit.URLUtil.guessFileName(
                        conn.url.toString(), cd, conn.contentType?.substringBefore(';')
                    )
                }
                input = conn.inputStream
                total = conn.contentLengthLong
            }
            finalName = uniqueName(tree, finalName)
            // octet-stream keeps the exact name; the folder provider adds " (1)" if the name exists.
            target = DocumentsContract.createDocument(
                app.contentResolver, parent, "application/octet-stream", finalName
            ) ?: error("Could not create file in folder")
            var done = 0L
            var lastPct = -1
            var lastBytes = 0L
            val where = folderLabel(tree)

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
                                post(id, req.fileName, "$pct%  ·  ${size(done)} of ${size(total)}", where, progress = pct)
                            }
                        } else if (done - lastBytes >= 512 * 1024) {
                            lastBytes = done
                            post(id, req.fileName, "${size(done)} downloaded", where, progress = -1)
                        }
                    }
                }
            }
            post(id, finalName, "Download complete  ·  ${size(done)}  ·  Tap to open", where, done = true, openUri = target, name = finalName)
        } catch (e: Exception) {
            target?.let { runCatching { DocumentsContract.deleteDocument(app.contentResolver, it) } }
            post(id, req.fileName, "Download failed: ${e.message ?: "error"}", folderLabel(tree), done = true, failed = true)
        } finally {
            req.localFile?.delete()
        }
    }

    /** "xyz.mp4" exists -> "xyz(1).mp4": the number goes before the last dot, never after the extension. */
    private fun uniqueName(tree: Uri, name: String): String {
        val existing = HashSet<String>()
        runCatching {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, DocumentsContract.getTreeDocumentId(tree)
            )
            app.contentResolver.query(
                kids, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> while (c.moveToNext()) c.getString(0)?.let { existing.add(it) } }
        }
        if (name !in existing) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 1
        while ("$base($i)$ext" in existing) i++
        return "$base($i)$ext"
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(app, bytes)

    /** progress: 0..100, or -1 for indeterminate. done = final state (not ongoing). */
    private fun post(
        id: Int, title: String, text: String, folder: String?,
        progress: Int = -1, done: Boolean = false, failed: Boolean = false,
        openUri: Uri? = null, name: String = title,
    ) {
        val b = Notification.Builder(app, CHANNEL)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(if (done) Notification.CATEGORY_STATUS else Notification.CATEGORY_PROGRESS)
            .setSmallIcon(
                when {
                    failed -> android.R.drawable.stat_notify_error
                    done -> android.R.drawable.stat_sys_download_done
                    else -> android.R.drawable.stat_sys_download
                }
            )
        if (accent != 0) b.setColor(accent)
        if (folder != null) b.setSubText(folder)
        if (done) {
            b.setOngoing(false).setAutoCancel(true)
            if (openUri != null && !failed) openIntent(id, openUri, name)?.let { b.setContentIntent(it) }
        } else {
            b.setOngoing(true).setProgress(100, progress.coerceAtLeast(0), progress < 0)
        }
        nm.notify(id, b.build())
    }

    /** Tapping a finished download opens it with whatever app handles that file type. */
    private fun openIntent(id: Int, uri: Uri, name: String): PendingIntent? = runCatching {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        PendingIntent.getActivity(app, id, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }.getOrNull()

    fun toast(msg: String) = main.post { Toast.makeText(app, msg, Toast.LENGTH_SHORT).show() }

    companion object {
        private const val CHANNEL = "downloads"

        /** Readable path of a picked folder, e.g. "Internal storage/Download/Reports". */
        fun folderLabel(tree: Uri): String {
            val id = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                ?: return tree.lastPathSegment ?: "Selected folder"
            if (!id.contains(':')) return Uri.decode(id)
            val volume = id.substringBefore(':')
            val path = id.substringAfter(':').trim('/')
            val root = if (volume.equals("primary", ignoreCase = true)) "Internal storage" else "SD card"
            return if (path.isEmpty()) root else "$root/$path"
        }
    }
}
