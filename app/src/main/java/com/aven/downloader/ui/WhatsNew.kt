package com.aven.downloader.ui

/** Texts of the first-install welcome popup and the one-time "What's new" popup after an update. */
object WhatsNew {
    val features = listOf(
        "Download videos and audio from YouTube, Facebook, Instagram, TikTok and 1000+ other sites",
        "Google Drive videos, zip files and documents",
        "Share any link to AHD Hub: pick the quality in a small popup and it downloads in the background",
        "Playlists with the estimated total size, audio language choice, MP3 / M4A",
        "Files are sorted into folders (Videos, Music, Documents ...) inside the folder you choose"
    )

    /** Notes for each build; builds without an entry show nothing. */
    fun notesFor(versionCode: Int): List<String>? = when {
        versionCode >= 1 -> listOf(
            "Downloads are sorted into folders by file type",
            "In-app updates: new versions can be installed from inside the app",
            "Engine version and date are shown in Settings",
            "Smaller fixes and speed improvements"
        )
        else -> null
    }
}
