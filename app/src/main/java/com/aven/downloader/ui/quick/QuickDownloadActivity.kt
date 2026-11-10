package com.aven.downloader.ui.quick

import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.aven.downloader.ui.PermissionRequests
import com.aven.downloader.ui.theme.AvenTheme
import com.aven.downloader.util.UrlParser

/**
 * A small bottom-sheet popup (not the full app). Opens from the Share menu, the Quick Settings tile,
 * the app shortcut or another app. Pick a quality, tap Download: the download runs in the background
 * and its progress shows in the notification.
 */
class QuickDownloadActivity : ComponentActivity() {
    private val vm: QuickViewModel by viewModels()
    private var started = false
    private var wantsClipboard = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        // After rotation the ViewModel already holds the result.
        if (savedInstanceState != null) started = true else handleIntent(intent)
        setContent {
            AvenTheme {
                PermissionRequests()
                QuickPopup(vm = vm, onClose = { finish() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        started = false
        handleIntent(intent)
        if (wantsClipboard && hasWindowFocus()) readClipboard()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Android only lets an app read the clipboard while it is in the foreground with focus.
        if (hasFocus && wantsClipboard) readClipboard()
    }

    private fun handleIntent(i: Intent) {
        if (i.action == ACTION_CLIPBOARD) {
            wantsClipboard = true
            return
        }
        started = true
        val text: String? = when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> i.data?.getQueryParameter("url")
            else -> i.getStringExtra(EXTRA_URL) ?: i.getStringExtra(Intent.EXTRA_TEXT)
        }
        vm.start(text)
    }

    private fun readClipboard() {
        wantsClipboard = false
        started = true
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0)?.coerceToText(this)?.toString() else null
        if (UrlParser.extractUrl(text) == null) vm.noLink() else vm.start(text)
    }

    companion object {
        const val EXTRA_URL = "com.aven.downloader.extra.URL"
        const val ACTION_CLIPBOARD = "com.aven.downloader.action.CLIPBOARD"
        const val ACTION_DOWNLOAD = "com.aven.downloader.ACTION_DOWNLOAD"
    }
}
