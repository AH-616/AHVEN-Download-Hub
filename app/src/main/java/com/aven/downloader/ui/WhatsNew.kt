package com.aven.downloader.ui

/** Texts of the first-install welcome popup and the one-time "What's new" popup after an update. */
object WhatsNew {
    val features = listOf(
        "Download videos and audio from YouTube, Facebook, Instagram, TikTok and 1000+ other sites",
        "Google Drive videos, zip files and documents",
        "Share any link to AHD Hub: pick the quality in a small popup and it downloads in the background",
        "Playlists with the estimated total size, audio language choice, M4A / Opus",
        "Files are sorted into folders (Videos, Music, Documents ...) inside the folder you choose",
        "Built-in browser: a download button appears for videos, audio and files, plus a Grabber that lists every file on a page"
    )

    /** Notes for each build; builds without an entry show nothing. */
    fun notesFor(versionCode: Int): List<String>? = when {
        versionCode >= 1 -> listOf(
            "New liquid-glass look with dark and light themes",
            "Built-in browser with ad blocking and a download editor (can be turned off)",
            "Grabber: scan a page and pick the file types to download",
            "Much faster YouTube lookup and download",
            "DNS over HTTPS, Clear cache, and a separate App update section in Settings",
            "Failed downloads: Redownload and Refresh link in browser",
            "Downloads are sorted into folders by file type",
            "In-app updates: new versions can be installed from inside the app",
            "Engine version and date are shown in Settings",
            "Smaller fixes and speed improvements"
        )
        else -> null
    }
}
