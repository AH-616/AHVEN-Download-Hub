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
        if (e.openDownloads) tab = 1
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
            onDownload = { spec -> home.download(spec) { tab = 1 } }
        )
        return
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                val colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                    selectedTextColor = Gold,
                    indicatorColor = Gold
                )
                NavigationBarItem(selected = tab == 0, onClick = { tab = 0 }, colors = colors,
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) }, label = { Text("Home") })
                NavigationBarItem(selected = tab == 1, onClick = { tab = 1 }, colors = colors,
                    icon = { Icon(Icons.Filled.Download, contentDescription = null) }, label = { Text("Downloads") })
                NavigationBarItem(selected = tab == 2, onClick = { tab = 2 }, colors = colors,
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) }, label = { Text("Settings") })
            }
        }
    ) { padding ->
        when (tab) {
            0 -> HomeScreen(home, padding)
            1 -> DownloadsScreen(padding)
            else -> SettingsScreen(padding)
        }
    }
}
