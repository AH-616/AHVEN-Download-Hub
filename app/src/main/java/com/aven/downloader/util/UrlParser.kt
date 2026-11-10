package com.aven.downloader.util

enum class Platform { YOUTUBE, FACEBOOK, DRIVE, OTHER }

sealed interface UrlResult {
    /** [platform] is only a label; any site yt-dlp supports works (it says so if it doesn't). */
    data class Supported(val url: String, val platform: Platform) : UrlResult
    data class Unsupported(val message: String) : UrlResult
}

/** Pure Kotlin (no Android classes) so it can be unit-tested on the JVM. */
object UrlParser {
    private val urlRegex = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val bareRegex = Regex(
        """^(www\.|m\.|web\.|mobile\.)?(youtube\.com|youtu\.be|facebook\.com|fb\.watch|fb\.com)/\S*""",
        RegexOption.IGNORE_CASE
    )
    private val hostRegex = Regex("""^https?://([^/?#:@]+)""", RegexOption.IGNORE_CASE)
    private val hostWithDot = Regex("""^[a-z0-9-]+(\.[a-z0-9-]+)+$""")

    private val youtubeHosts = listOf("youtube.com", "youtu.be", "youtube-nocookie.com")
    private val facebookHosts = listOf("facebook.com", "fb.watch", "fb.com")

    /** Finds the first link in free text (e.g. a shared message). */
    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val found = urlRegex.find(text)?.value
        if (found != null) return found.trimEnd('.', ',', ')', ']', '!', ';')
        val trimmed = text.trim()
        return if (bareRegex.matches(trimmed)) "https://$trimmed" else null
    }

    fun parse(text: String?): UrlResult {
        val url = extractUrl(text)
            ?: return UrlResult.Unsupported("No link found. Paste a video link (YouTube, Facebook, Instagram, TikTok and more).")
        val host = hostRegex.find(url)?.groupValues?.get(1)?.lowercase()
        if (host == null || !hostWithDot.matches(host)) {
            return UrlResult.Unsupported("This doesn't look like a valid link.")
        }
        val path = pathOf(url).trim('/')

        if (matches(host, youtubeHosts)) {
            if (host.endsWith("youtu.be")) {
                return if (path.isNotEmpty()) UrlResult.Supported(url, Platform.YOUTUBE)
                else UrlResult.Unsupported("This YouTube link has no video in it.")
            }
            return if (path.isEmpty() && queryOf(url).isEmpty()) {
                UrlResult.Unsupported("That's the YouTube home page. Open a video or playlist and share its link.")
            } else UrlResult.Supported(url, Platform.YOUTUBE)
        }
        if (matches(host, facebookHosts)) {
            return if (path.isEmpty() && !host.endsWith("fb.watch")) {
                UrlResult.Unsupported("That's the Facebook home page. Open a video and share its link.")
            } else UrlResult.Supported(url, Platform.FACEBOOK)
        }
        if (isDrive(url)) return UrlResult.Supported(normalizeDrive(url), Platform.DRIVE)
        return UrlResult.Supported(url, Platform.OTHER)
    }

    private val driveHosts = listOf("drive.google.com", "docs.google.com", "drive.usercontent.google.com")

    fun isDrive(url: String): Boolean {
        val host = hostRegex.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        return driveHosts.any { host == it }
    }

    private val folderIdRegex = Regex("""/folders/([A-Za-z0-9_-]+)""")

    /** A Google Drive folder (or folderview) link: a list of files, not one video. */
    fun isDriveFolder(url: String): Boolean {
        if (!isDrive(url)) return false
        return folderIdRegex.containsMatchIn(pathOf(url)) ||
            (pathOf(url).trim('/').startsWith("drive/folderview") || pathOf(url).trim('/') == "folderview") &&
            Regex("""(?:^|&)id=[^&]+""").containsMatchIn(queryOf(url))
    }

    /**
     * Cleans up Drive links so yt-dlp recognises them: folders become
     * https://drive.google.com/drive/folders/ID (drops /u/0/, ?usp=sharing ...), file links drop tracking parameters.
     */
    fun normalizeDrive(url: String): String {
        if (!isDrive(url)) return url
        val path = pathOf(url)
        folderIdRegex.find(path)?.let { return "https://drive.google.com/drive/folders/${it.groupValues[1]}" }
        if (path.trim('/').endsWith("folderview")) {
            Regex("""(?:^|&)id=([^&]+)""").find(queryOf(url))?.let {
                return "https://drive.google.com/drive/folders/${it.groupValues[1]}"
            }
        }
        // https://drive.google.com/file/d/ID/view?usp=sharing  ->  .../file/d/ID/view
        // https://drive.google.com/open?id=ID or /uc?id=ID keep their id parameter.
        val id = Regex("""(?:^|&)id=([^&]+)""").find(queryOf(url))?.groupValues?.get(1)
        return when {
            Regex("""/file/d/[^/]+""").containsMatchIn(path) -> {
                val base = url.substringAfter("://").substringBefore('?').substringBefore('#')
                "https://$base"
            }
            id != null -> "https://drive.google.com/file/d/$id/view"
            else -> url
        }
    }

    private val knownVideoHosts = listOf(
        "youtube.com", "youtu.be", "facebook.com", "fb.watch", "fb.com", "instagram.com", "tiktok.com",
        "drive.google.com", "docs.google.com", "twitter.com", "x.com", "vimeo.com", "dailymotion.com",
        "reddit.com", "twitch.tv", "soundcloud.com", "bilibili.com", "threads.net", "pinterest.com"
    )

    /** Hosts a copied link is auto-detected for (a random copied web page must not open the popup). */
    fun isKnownVideoHost(url: String): Boolean {
        val host = hostRegex.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        return matches(host, knownVideoHosts)
    }

    fun isYoutube(url: String): Boolean {
        val host = hostRegex.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        return matches(host, youtubeHosts)
    }

    /** A link to exactly one YouTube video (watch?v=, youtu.be/, shorts/, live/, embed/). */
    fun isYoutubeSingleVideo(url: String): Boolean {
        if (!isYoutube(url)) return false
        val host = hostRegex.find(url)?.groupValues?.get(1)?.lowercase() ?: return false
        val p = pathOf(url).trim('/')
        if (host.endsWith("youtu.be")) return p.isNotEmpty()
        val hasV = Regex("""(?:^|&)v=[^&]+""").containsMatchIn(queryOf(url))
        return (p == "watch" && hasV) ||
            p.startsWith("shorts/") || p.startsWith("live/") || p.startsWith("embed/") || p.startsWith("v/")
    }

    /** A YouTube /playlist page: it is a list of videos, never a single video. */
    fun isPlaylistPage(url: String): Boolean {
        if (!isYoutube(url)) return false
        return pathOf(url).trim('/') == "playlist" && playlistId(url) != null
    }

    /**
     * For a YouTube link that carries a `list=` parameter (a playlist, or a video inside a playlist)
     * returns the plain playlist page URL. Auto-generated mixes (RD...) are ignored.
     */
    fun playlistUrlOf(url: String): String? {
        if (!isYoutube(url)) return null
        val id = playlistId(url) ?: return null
        return "https://www.youtube.com/playlist?list=$id"
    }

    private fun playlistId(url: String): String? {
        val m = Regex("""(?:^|&)list=([A-Za-z0-9_-]+)""").find(queryOf(url)) ?: return null
        val id = m.groupValues[1]
        return if (id.startsWith("RD") || id.startsWith("UL")) null else id
    }

    private fun matches(host: String, domains: List<String>) =
        domains.any { host == it || host.endsWith(".$it") }

    private fun pathOf(url: String): String {
        val noScheme = url.substringAfter("://")
        val afterHost = noScheme.substringAfter('/', "")
        return "/" + afterHost.substringBefore('?').substringBefore('#')
    }

    private fun queryOf(url: String): String =
        url.substringAfter('?', "").substringBefore('#')
}
