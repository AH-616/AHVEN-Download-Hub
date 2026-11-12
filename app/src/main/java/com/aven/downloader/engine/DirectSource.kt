package com.aven.downloader.engine

import android.util.Base64
import org.json.JSONObject

/**
 * A plain file (or HLS stream) that is fetched straight from a URL, with the headers the page used.
 * Stored inside the download record as "direct:<text>" (own multi-connection downloader) or "ytdlp:<text>" (HLS).
 */
data class DirectSource(
    val url: String,
    val name: String = "",
    val referer: String = "",
    val ua: String = "",
    val cookie: String = "",
    val parts: Int = 0          // 0 = use the Number of parts setting
) {
    fun headers(): Map<String, String> {
        val h = LinkedHashMap<String, String>()
        if (ua.isNotBlank()) h["User-Agent"] = ua
        if (referer.isNotBlank()) h["Referer"] = referer
        if (cookie.isNotBlank()) h["Cookie"] = cookie
        return h
    }

    fun encode(): String {
        val o = JSONObject()
        o.put("u", url); o.put("n", name); o.put("r", referer); o.put("a", ua); o.put("c", cookie); o.put("p", parts)
        return Base64.encodeToString(o.toString().toByteArray(Charsets.UTF_8), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    companion object {
        fun decode(text: String): DirectSource {
            val o = JSONObject(String(Base64.decode(text, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8))
            return DirectSource(
                url = o.optString("u"), name = o.optString("n"), referer = o.optString("r"),
                ua = o.optString("a"), cookie = o.optString("c"), parts = o.optInt("p", 0)
            )
        }
    }
}

/** Which links are plain files (not web pages for yt-dlp). */
object DirectFiles {
    val extensions: Set<String> = setOf(
        // archives, programs, documents
        "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "tgz", "iso", "apk", "xapk", "apks", "aab", "exe", "msi", "dmg", "deb",
        "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub", "rtf", "odt", "ods", "odp", "csv", "torrent",
        // media
        "mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "flv", "wmv", "mpg", "mpeg",
        "mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "weba", "amr",
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "srt", "vtt", "ass"
    )
    val videoExtensions: Set<String> = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "flv", "wmv", "mpg", "mpeg", "ts")
    val audioExtensions: Set<String> = setOf("mp3", "m4a", "aac", "flac", "wav", "ogg", "opus", "wma", "weba", "amr")

    fun extOf(url: String): String {
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        return if ('.' in last) last.substringAfterLast('.').lowercase() else ""
    }

    fun looksLikeFile(url: String): Boolean = extOf(url) in extensions
}
