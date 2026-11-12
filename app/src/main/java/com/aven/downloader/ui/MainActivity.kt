package com.aven.downloader.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.aven.downloader.AvenApp
import com.aven.downloader.ui.theme.AvenTheme
import com.aven.downloader.util.UrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val dark = com.aven.downloader.ui.theme.rememberDarkTheme()
            androidx.compose.runtime.DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            AvenTheme {
                PermissionRequests()
                AvenRoot()
            }
        }
    }

    /**
     * Android only lets an app read the clipboard while it has focus, so this runs when the window gains focus.
     * A link is acted on once; copying the same link again later is ignored until another link was copied.
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        val app = application as AvenApp
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val text = try {
            cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
        } catch (e: Exception) { null } ?: return
        val url = UrlParser.extractUrl(text) ?: return
        if (!UrlParser.isKnownVideoHost(url)) return
        val prefs = getSharedPreferences("aven_prefs", MODE_PRIVATE)
        if (prefs.getString("last_clip", null) == url) return
        app.appScope.launch {
            if (!app.settings.flow.first().autoDetectClipboard) return@launch
            prefs.edit().putString("last_clip", url).apply()
            withContext(Dispatchers.Main) { IncomingLink.post(url, false) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * Accepts a link from: the Android Share menu (ACTION_SEND), the deep link
     * aven://download?url=..., or an explicit intent with EXTRA_URL (for AHVEN later).
     */
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val text: String? = when {
            i.action == Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            i.action == Intent.ACTION_VIEW && i.data?.scheme == "aven" -> i.data?.getQueryParameter("url")
            i.hasExtra(EXTRA_URL) -> i.getStringExtra(EXTRA_URL)
            else -> null
        }
        UrlParser.extractUrl(text)?.let { getSharedPreferences("aven_prefs", MODE_PRIVATE).edit().putString("last_clip", it).apply() }
        IncomingLink.post(text, i.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false))
    }

    companion object {
        const val EXTRA_URL = "com.aven.downloader.extra.URL"
        const val EXTRA_OPEN_DOWNLOADS = "com.aven.downloader.extra.OPEN_DOWNLOADS"
    }
}

@Composable
internal fun PermissionRequests() {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) launcher.launch(wanted.toTypedArray())
    }
}
