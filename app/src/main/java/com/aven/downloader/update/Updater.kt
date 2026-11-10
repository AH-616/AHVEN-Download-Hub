package com.aven.downloader.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.aven.downloader.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data object UpToDate : UpdateUi
    data object NotConfigured : UpdateUi
    /** [deadlineMs] = last moment the update can be postponed. */
    data class Available(val info: ReleaseInfo, val force: Boolean, val deadlineMs: Long) : UpdateUi
    data class Downloading(val info: ReleaseInfo, val done: Long, val force: Boolean) : UpdateUi
    data class Ready(val info: ReleaseInfo, val file: File, val force: Boolean) : UpdateUi
    data class Failed(val message: String, val force: Boolean, val info: ReleaseInfo?) : UpdateUi
}

/**
 * Looks for a newer release in the public releases repo (BuildConfig.UPDATE_REPO), downloads the APK inside the
 * app and opens Android's installer. A pending update is remembered, so the 2-day limit also holds offline.
 */
class Updater(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("aven_update", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state: StateFlow<UpdateUi> = _state
    private var job: Job? = null
    private val configured get() = BuildConfig.UPDATE_REPO.contains('/')

    private fun now() = System.currentTimeMillis()

    /** Called once at app start: restores a pending update from disk, then asks GitHub. */
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        File(context.cacheDir, "updates").takeIf { it.exists() && BuildConfig.VERSION_CODE >= prefs.getInt("code", 0) }
            ?.deleteRecursively()
        val code = prefs.getInt("code", 0)
        if (code > BuildConfig.VERSION_CODE) {
            val info = ReleaseInfo(code, prefs.getString("tag", "") ?: "", prefs.getString("url", "") ?: "", prefs.getLong("size", 0L))
            _state.value = availableFrom(info)
        } else if (code != 0) prefs.edit().clear().apply()
        check(false)
    }

    private fun availableFrom(info: ReleaseInfo): UpdateUi.Available {
        val seen = prefs.getLong("seen", now())
        return UpdateUi.Available(info, UpdatePolicy.mustUpdate(BuildConfig.VERSION_CODE, info.code, seen, now()), UpdatePolicy.deadline(seen))
    }

    fun check(userInitiated: Boolean) {
        if (!configured) { if (userInitiated) _state.value = UpdateUi.NotConfigured; return }
        val cur = _state.value
        if (cur is UpdateUi.Checking || cur is UpdateUi.Downloading) return
        job?.cancel()
        if (userInitiated) _state.value = UpdateUi.Checking
        job = scope.launch {
            try {
                val json = withContext(Dispatchers.IO) { fetch("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest") }
                val rel = UpdatePolicy.parseRelease(json, Build.SUPPORTED_ABIS.toList())
                if (rel == null || rel.code <= BuildConfig.VERSION_CODE) {
                    prefs.edit().clear().apply()
                    _state.value = UpdateUi.UpToDate
                } else {
                    if (prefs.getInt("code", 0) != rel.code) {
                        prefs.edit().clear().putInt("code", rel.code).putString("tag", rel.tag).putString("url", rel.apkUrl)
                            .putLong("size", rel.size).putLong("seen", now()).apply()
                    }
                    _state.value = availableFrom(rel)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val s = _state.value
                if (s is UpdateUi.Checking) _state.value = UpdateUi.Failed("Couldn't check for updates. Check your internet connection.", false, null)
            }
        }
    }

    fun later() {
        val s = _state.value
        if (s is UpdateUi.Available && !s.force) _state.value = UpdateUi.Idle
        else if (s is UpdateUi.Failed && !s.force) _state.value = UpdateUi.Idle
        else if (s is UpdateUi.UpToDate || s is UpdateUi.NotConfigured || s is UpdateUi.Failed) _state.value = UpdateUi.Idle
    }

    fun download(info: ReleaseInfo) {
        val force = (_state.value as? UpdateUi.Available)?.force ?: (_state.value as? UpdateUi.Failed)?.force ?: false
        job?.cancel()
        job = scope.launch {
            try {
                _state.value = UpdateUi.Downloading(info, 0, force)
                val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                val part = File(dir, "AHD-Hub-${info.code}.part")
                val out = File(dir, "AHD-Hub-${info.code}.apk")
                withContext(Dispatchers.IO) {
                    val c = URL(info.apkUrl).openConnection() as HttpURLConnection
                    c.connectTimeout = 20_000; c.readTimeout = 30_000; c.instanceFollowRedirects = true
                    try {
                        if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
                        var done = 0L
                        var lastEmit = 0L
                        c.inputStream.use { ins ->
                            part.outputStream().use { os ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = ins.read(buf)
                                    if (n < 0) break
                                    os.write(buf, 0, n)
                                    done += n
                                    val t = System.nanoTime()
                                    if (t - lastEmit > 300_000_000L) { lastEmit = t; _state.value = UpdateUi.Downloading(info, done, force) }
                                }
                            }
                        }
                    } finally { c.disconnect() }
                    out.delete()
                    if (!part.renameTo(out)) throw java.io.IOException("Couldn't save the update file.")
                }
                _state.value = UpdateUi.Ready(info, out, force)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateUi.Failed("Download failed (${e.message}). Check your internet and try again.", force, info)
            }
        }
    }

    /** True if Android lets this app install APKs; otherwise the settings screen for that permission is opened. */
    fun install(file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000; c.readTimeout = 15_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (c.responseCode !in 200..299) throw java.io.IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
