package com.aven.downloader.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.aven.downloader.R
import com.aven.downloader.util.Formatters
import com.aven.downloader.ui.MainActivity

class Notifier(private val context: Context) {
    companion object {
        const val CH_PROGRESS = "downloads_progress"
        const val CH_DONE = "downloads_done"
        const val FOREGROUND_ID = 1001
        private const val DONE_BASE = 2000
    }

    init {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Download progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openAppIntent(): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_OPEN_DOWNLOADS, true)
        }
        return PendingIntent.getActivity(context, 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** Ongoing notification that keeps the download service in the foreground. */
    fun foreground(title: String, text: String, progress: Int?): Notification {
        val b = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
        if (progress != null) b.setProgress(100, progress.coerceIn(0, 100), false)
        else b.setProgress(0, 0, true)
        return b.build()
    }

    /** Rich progress notification: name, thumbnail, bar, %, size, speed and ETA (one line per file when several run). */
    fun progress(s: DownloadManager.Summary, thumb: android.graphics.Bitmap?): Notification {
        val b = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
        val items = s.items
        fun line(e: com.aven.downloader.data.DownloadEntity): String {
            val parts = mutableListOf("${e.progress.toInt()}%")
            if (e.totalBytes > 0) parts += Formatters.bytes((e.totalBytes * (e.progress / 100f)).toLong()) + " / " + Formatters.bytes(e.totalBytes)
            Formatters.speed(e.speedBps).takeIf { it.isNotEmpty() }?.let { parts += it }
            if (e.etaSec > 0) parts += "ETA " + Formatters.duration(e.etaSec)
            return parts.joinToString(" · ")
        }
        if (items.size == 1) {
            val e = items[0]
            b.setContentTitle(e.title)
            b.setContentText(line(e))
            b.setStyle(NotificationCompat.BigTextStyle().bigText(line(e) + (e.statusDetail?.let { "\n$it" } ?: "")))
            if (thumb != null) b.setLargeIcon(thumb)
            b.setProgress(100, e.progress.toInt().coerceIn(0, 100), e.progress <= 0f)
        } else if (items.size > 1) {
            b.setContentTitle("Downloading ${items.size} files")
            b.setContentText(items.joinToString(" | ") { it.title })
            val inbox = NotificationCompat.InboxStyle()
            items.take(5).forEach { inbox.addLine("${it.title.take(32)} — ${it.progress.toInt()}%" + Formatters.speed(it.speedBps).let { sp -> if (sp.isEmpty()) "" else " · $sp" }) }
            if (items.size > 5) inbox.setSummaryText("+${items.size - 5} more")
            b.setStyle(inbox)
            b.setProgress(100, s.progress.coerceIn(0, 100), false)
        } else {
            b.setContentTitle("Downloads waiting")
            val t = buildString {
                if (s.queued > 0) append("${s.queued} queued")
                s.blocked?.let { if (isNotEmpty()) append(" • "); append(it) }
                if (isEmpty()) append("Working…")
            }
            b.setContentText(t)
            b.setProgress(0, 0, true)
        }
        if (items.isNotEmpty() && s.queued > 0) b.setSubText("${s.queued} more queued")
        return b.build()
    }

    fun done(downloadId: Long, title: String, success: Boolean, message: String) {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        val n = NotificationCompat.Builder(context, CH_DONE)
            .setSmallIcon(if (success) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
            .setContentTitle(if (success) "Download complete" else "Download failed")
            .setContentText("$title — $message")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$title\n$message"))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            NotificationManagerCompat.from(context).notify(DONE_BASE + (downloadId % 100000).toInt(), n)
        } catch (ignored: SecurityException) { }
    }
}
