package com.aven.downloader

import android.app.Application
import com.aven.downloader.data.AppDatabase
import com.aven.downloader.data.CookieStore
import com.aven.downloader.data.SettingsRepository
import com.aven.downloader.data.StorageSaver
import com.aven.downloader.download.DownloadManager
import com.aven.downloader.download.Notifier
import com.aven.downloader.engine.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import com.aven.downloader.net.Net

/** Application class doubles as a tiny service locator (no DI framework needed). */
class AvenApp : Application() {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database by lazy { AppDatabase.create(this) }
    val settings by lazy { SettingsRepository(this) }
    val cookies by lazy { CookieStore(this) }
    val engine by lazy { Engine(this) }
    val notifier by lazy { Notifier(this) }
    val updater by lazy { com.aven.downloader.update.Updater(this, appScope) }
    val storage by lazy { StorageSaver(this) }
    val downloads by lazy {
        DownloadManager(this, database.downloads(), engine, settings, storage, notifier)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // DNS over HTTPS follows the setting (also applied once at start).
        appScope.launch {
            settings.flow.map { it.dohMode to it.dohCustom }.distinctUntilChanged().collect { (m, c) -> Net.configure(m, c) }
        }
        engine.start(appScope)   // unpack yt-dlp / Python in the background
        downloads                // create the manager so queued items are picked up after restart
    }

    companion object {
        lateinit var instance: AvenApp
            private set
    }
}
