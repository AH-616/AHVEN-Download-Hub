package com.aven.downloader.engine

data class StreamFormat(
    val id: String,
    val ext: String,
    val height: Int,
    val width: Int,
    val vcodec: String?,
    val acodec: String?,
    val tbr: Double,
    val abr: Double,
    val fileSize: Long,        // exact or approximate bytes, 0 = unknown
    val url: String?,
    val protocol: String?,
    val headers: Map<String, String>,
    val language: String? = null,      // e.g. "en-US", "hi" (audio language tag)
    val note: String? = null           // e.g. "English (US), original (default)"
) {
    val hasVideo: Boolean get() = vcodec != "none" && (vcodec != null || height > 0)
    val hasAudio: Boolean get() = acodec != "none"
}

data class MediaInfo(
    val sourceUrl: String,
    val title: String,
    val uploader: String?,
    val durationSec: Int,
    val thumbnail: String?,
    val extractor: String?,
    val isLive: Boolean,
    val formats: List<StreamFormat>
)

/** One video inside a playlist (from `yt-dlp --flat-playlist`, so no format list yet). */
data class PlaylistEntry(
    val url: String,
    val title: String,
    val durationSec: Int,      // 0 = unknown
    val thumbnail: String?,
    val uploader: String?
)

data class PlaylistInfo(
    val sourceUrl: String,
    val title: String,
    val uploader: String?,
    val entries: List<PlaylistEntry>
)

/** Result of looking at a link: one video, or a list of videos. */
sealed interface Fetched {
    data class Single(val info: MediaInfo) : Fetched
    data class Playlist(val playlist: PlaylistInfo) : Fetched
    /** A plain file (Google Drive zip / document / any file, or a Drive video offered as its original file). */
    data class File(val file: FileInfo) : Fetched
}

/** One way to download a file. [source] is "drive:<id>" or "export:<kind>:<id>:<fmt>". */
data class FileChoice(val label: String, val source: String, val estimatedSize: Long)

data class FileInfo(
    val sourceUrl: String,
    val title: String,
    val mime: String?,
    val isVideo: Boolean,
    val choices: List<FileChoice>
)
