package com.aven.downloader.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import com.aven.downloader.ui.quick.QuickDownloadActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.aven.downloader.AvenApp
import com.aven.downloader.data.Settings
import com.aven.downloader.ui.downloads.DownloadsScreen
import com.aven.downloader.ui.home.FetchState
import com.aven.downloader.ui.home.HomeScreen
import com.aven.downloader.ui.home.HomeViewModel
import com.aven.downloader.ui.preview.PreviewScreen
import com.aven.downloader.ui.settings.SettingsScreen
import com.aven.downloader.ui.theme.Gold
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Language
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.mutableStateOf
import com.aven.downloader.ui.browser.BrowserScreen
import com.aven.downloader.ui.browser.GrabberScreen
import com.aven.downloader.ui.theme.GlassBackground
import com.aven.downloader.ui.theme.ThemeState
import com.aven.downloader.ui.theme.glassBorder

@Composable
fun AvenRoot() {
    val home: HomeViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val state by home.state.collectAsStateWithLifecycle()
    val settings by AvenApp.instance.settings.flow.collectAsStateWithLifecycle(initialValue = Settings())
    val event by IncomingLink.events.collectAsStateWithLifecycle()

    AppDialogs()
    LaunchedEffect(Unit) { AvenApp.instance.updater.start() }

    // A link arrived from the Share menu / deep link / another app.
    LaunchedEffect(event) {
        val e = event ?: return@LaunchedEffect
        if (e.openDownloads) tab = 2
        if (!e.text.isNullOrBlank()) {
            tab = 0
            home.submit(e.text)
        }
        IncomingLink.consume()
    }

    val context = LocalContext.current
    (state as? FetchState.OpenPopup)?.let { p ->
        LaunchedEffect(p) {
            context.startActivity(
                Intent(context, QuickDownloadActivity::class.java)
                    .putExtra(QuickDownloadActivity.EXTRA_URL, p.url)
            )
            home.dismissPreview()
        }
    }

    val loaded = state as? FetchState.Loaded
    if (loaded != null) {
        BackHandler { home.dismissPreview() }
        PreviewScreen(
            loaded = loaded,
            settings = settings,
            onBack = home::dismissPreview,
            onRefresh = home::refreshPreview,
            onDownload = { spec -> home.download(spec) { tab = 2 } }
        )
        return
    }

    var grabber by rememberSaveable { mutableStateOf(false) }
    if (grabber) {
        GrabberScreen(onBack = { grabber = false }, onDownloads = { grabber = false; tab = 2 })
        return
    }

    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val padding = PaddingValues(top = topInset, bottom = bottomInset + 84.dp)

    GlassBackground {
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                0 -> HomeScreen(home, padding)
                1 -> BrowserScreen(
                    padding = padding,
                    onSupportedVideo = { u -> tab = 0; home.submit(u) },
                    onOpenGrabber = { grabber = true },
                    onQueued = { tab = 2 }
                )
                2 -> DownloadsScreen(padding, onOpenBrowser = { tab = 1 })
                else -> SettingsScreen(padding)
            }
            GlassTabBar(
                selected = tab,
                onSelect = { tab = it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 10.dp)
            )
        }
    }
}

@Composable
private fun GlassTabBar(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val items = listOf(
        Triple("Home", Icons.Filled.Home, 0),
        Triple("Browser", Icons.Filled.Language, 1),
        Triple("Downloads", Icons.Filled.Download, 2),
        Triple("Settings", Icons.Filled.Settings, 3)
    )
    val shape = RoundedCornerShape(32.dp)
    Row(
        modifier
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(if (ThemeState.dark) Color(0xCC141A2B) else Color(0xE6FFFFFF))
            .border(glassBorder(), shape)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items.forEach { (label, icon, i) ->
            val on = selected == i
            Column(
                Modifier
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (on) Gold.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(icon, contentDescription = label, tint = if (on) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (on) Gold else MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
            }
        }
    }
}
