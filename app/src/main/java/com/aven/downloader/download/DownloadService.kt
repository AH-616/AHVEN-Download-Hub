package com.aven.downloader.download

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.ContextCompat
import com.aven.downloader.AvenApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service whose only job is to keep the process alive (and show an ongoing
 * notification) while downloads are queued or running. The work itself lives in DownloadManager.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as AvenApp
        if (!started) {
            started = true
            enterForeground(app.notifier.foreground("AHD Hub", "Starting…", null))
            scope.launch {
                app.downloads.summary.collectLatest { s ->
                    if (s.running == 0 && s.queued == 0) {
                        // Grace period: the summary can lag behind a just-enqueued item.
                        // collectLatest cancels this wait if a newer summary arrives.
                        delay(3000)
                        releaseWakeLock()
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        return@collectLatest
                    }
                    if (s.running > 0) acquireWakeLock() else releaseWakeLock()
                    val only = s.items.singleOrNull()
                    val thumb = only?.thumbnail?.let { thumbFor(it) }
                    val n = app.notifier.progress(s, thumb)
                    (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                        .notify(Notifier.FOREGROUND_ID, n)
                }
            }
        }
        return START_NOT_STICKY
    }

    private val thumbs = HashMap<String, android.graphics.Bitmap?>()

    private suspend fun thumbFor(url: String): android.graphics.Bitmap? {
        if (thumbs.containsKey(url)) return thumbs[url]
        val bmp = try {
            kotlinx.coroutines.withTimeoutOrNull(3000) {
                val req = coil.request.ImageRequest.Builder(this@DownloadService).data(url).size(256).allowHardware(false).build()
                val res = coil.Coil.imageLoader(this@DownloadService).execute(req)
                (res as? coil.request.SuccessResult)?.drawable?.toBitmap()
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Throwable) { null }
        if (thumbs.size > 8) thumbs.clear()
        thumbs[url] = bmp
        return bmp
    }

    private fun enterForeground(n: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, Notifier.FOREGROUND_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(Notifier.FOREGROUND_ID, n)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aven:download").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun ensureRunning(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
            } catch (ignored: Exception) {
                // Android 12+ can refuse to start a foreground service from the background.
                // Downloads still run while the app process is alive.
            }
        }
    }
}
