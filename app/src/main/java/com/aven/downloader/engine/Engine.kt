package com.aven.downloader.engine

import android.app.Application
import com.aven.downloader.AvenApp
import com.aven.downloader.data.Settings
import com.aven.downloader.net.Net
import com.aven.downloader.util.UrlParser
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

sealed interface EngineState {
    data object Initializing : EngineState
    data object Ready : EngineState
    data class Failed(val message: String) : EngineState
}

class EngineNotReadyException(message: String) : Exception(message)

/**
 * Reads links. YouTube goes through NewPipe Extractor (fast, no Python); everything else through yt-dlp,
 * which is only asked for the stream links. Plain file links are probed directly.
 */
class Engine(private val app: Application) {
    private val _state = MutableStateFlow<EngineState>(EngineState.Initializing)
    val state: StateFlow<EngineState> = _state

    /** The YouTube "player client" that worked last time is tried first next time. */
    @Volatile private var lastGoodClient = "default"

    /** Extracts the bundled Python/yt-dlp on first launch. Takes a few seconds. (YouTube does not wait for it.) */
    fun start(scope: CoroutineScope) {
        scope.launch(Dispatchers.IO) { initialize() }
    }

    fun retryInit(scope: CoroutineScope) {
        _state.value = EngineState.Initializing
        scope.launch(Dispatchers.IO) { initialize() }
    }

    private suspend fun initialize() {
        try {
            YoutubeDL.init(app)
        } catch (t: Throwable) {
            _state.value = EngineState.Failed(t.message ?: t.javaClass.simpleName)
            return
        }
        // Ready first: the daily yt-dlp update runs in the background so the first link of the day isn't delayed.
        _state.value = EngineState.Ready
        try { autoUpdateIfDue() } catch (ignored: Throwable) { }
    }

    /** YouTube changes often, so yt-dlp is refreshed at most once a day (if enabled in Settings). */
    private suspend fun autoUpdateIfDue() {
        val repo = AvenApp.instance.settings
        val s = repo.flow.first()
        if (!s.autoUpdateYtDlp) return
        val now = System.currentTimeMillis()
        if (now - s.lastUpdateCheck < 24L * 3600 * 1000) return
        val job = CoroutineScope(SupervisorJob() + Dispatchers.IO).async {
            try {
                YoutubeDL.updateYoutubeDL(app, channelOf(s.updateChannel))
                true
            } catch (t: Throwable) { false }
        }
        // Don't keep the user waiting more than 25 s at startup; the update may still finish in the background.
        val finished = withTimeoutOrNull(25_000L) { job.await() }
        if (finished != null) repo.setLastUpdateCheck(now)
    }

    private fun channelOf(name: String): YoutubeDL.UpdateChannel =
        if (name == "nightly") YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE

    suspend fun awaitReady() {
        val s = _state.first { it !is EngineState.Initializing }
        if (s is EngineState.Failed) throw EngineNotReadyException("Downloader engine failed to start: ${s.message}")
    }

    // ------------------------------------------------------------------
    // Looking at a link
    // ------------------------------------------------------------------

    /**
     * Looks at a link: one video, or a list of videos.
     * [forcePlaylist] is used when the user chooses "open the whole playlist" for a video link that has list=.
     */
    suspend fun fetch(url: String, forcePlaylist: Boolean = false): Fetched {
        val key = "$url|$forcePlaylist"
        cacheGet(key)?.let { return it }
        val result = fetchUncached(url, forcePlaylist)
        cachePut(key, result)
        return result
    }

    // The same link is often looked at twice in a row (popup, then Retry, or Home then popup). A short-lived
    // memory cache makes the second look instant. Stream links expire after hours, so 10 minutes is safe.
    private class Cached(val at: Long, val value: Fetched)
    private val cache = LinkedHashMap<String, Cached>()

    fun clearCache() = synchronized(cache) { cache.clear() }

    private fun cacheGet(key: String): Fetched? = synchronized(cache) {
        val c = cache[key] ?: return null
        if (System.currentTimeMillis() - c.at > CACHE_MS) { cache.remove(key); null } else c.value
    }

    private fun cachePut(key: String, v: Fetched) = synchronized(cache) {
        cache[key] = Cached(System.currentTimeMillis(), v)
        while (cache.size > 20) cache.remove(cache.keys.first())
    }

    private suspend fun fetchUncached(url: String, forcePlaylist: Boolean): Fetched {
        // 1) YouTube through NewPipe: about a second, no yt-dlp start-up. Any problem -> yt-dlp below.
        if (UrlParser.isYoutube(url)) {
            if (!forcePlaylist && UrlParser.isYoutubeSingleVideo(url)) {
                try { return Fetched.Single(YouTubeFast.fetchVideo(url)) } catch (ce: CancellationException) { throw ce } catch (t: Throwable) { }
            } else {
                val target = if (forcePlaylist) (UrlParser.playlistUrlOf(url) ?: url) else url
                if (UrlParser.isPlaylistPage(target) || forcePlaylist) {
                    try {
                        val pl = YouTubeFast.fetchPlaylist(target)
                        if (pl.entries.size == 1 && !forcePlaylist) return Fetched.Single(fetchInfo(pl.entries[0].url))
                        return Fetched.Playlist(pl)
                    } catch (ce: CancellationException) { throw ce } catch (t: Throwable) { }
                }
            }
        }
        // 2) A link straight to a file (zip, pdf, mp4, mp3 ...): no yt-dlp needed.
        if (!UrlParser.isYoutube(url) && !UrlParser.isDrive(url) && DirectFiles.looksLikeFile(url)) {
            directFile(url)?.let { return it }
        }

        awaitReady()
        if (!forcePlaylist && UrlParser.isYoutubeSingleVideo(url)) return Fetched.Single(InfoParser.parse(url, runInfo(url, playlist = false)))
        if (UrlParser.isDrive(url)) {
            if (UrlParser.isDriveFolder(url)) return fetchDriveFolder(url)
            driveFile(url)?.let { return it }   // null = let yt-dlp try (streaming formats of a video)
        }

        val target = if (forcePlaylist) (UrlParser.playlistUrlOf(url) ?: url) else url
        val out = try {
            runInfo(target, playlist = true)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // yt-dlp doesn't know this site: it may still be a plain file with an odd link (no extension).
            if (!UrlParser.isYoutube(url) && "unsupported url" in (t.message ?: "").lowercase()) directFile(url)?.let { return it }
            throw t
        }
        if (!InfoParser.isPlaylist(out)) return Fetched.Single(InfoParser.parse(url, out))
        val playlist = InfoParser.parsePlaylist(target, out)
        if (playlist.entries.isEmpty()) throw IllegalArgumentException("No videos were found at this link.")
        if (playlist.entries.size == 1 && !forcePlaylist) return Fetched.Single(fetchInfo(playlist.entries[0].url))
        return Fetched.Playlist(playlist)
    }

    /** A file link: size, type and name come from the server. Null when the link is a web page. */
    private suspend fun directFile(url: String): Fetched.File? = withContext(Dispatchers.IO) {
        try {
            val p = Net.probe(url, emptyMap())
            if (p.mime.startsWith("text/html") || p.mime == "application/xhtml+xml") return@withContext null
            val ext = DirectFiles.extOf(p.fileName).ifEmpty { DirectFiles.extOf(url) }
            val video = p.mime.startsWith("video/") || ext in DirectFiles.videoExtensions
            val label = (if (video) "Original video" else "Original file") + (if (ext.isNotEmpty() && ext.length <= 5) " · ${ext.uppercase()}" else "")
            val src = DirectSource(p.finalUrl, p.fileName, referer = "", ua = Net.DESKTOP_UA)
            Fetched.File(FileInfo(url, p.fileName, p.mime.ifEmpty { null }, video, listOf(FileChoice(label, "direct:" + src.encode(), p.size.coerceAtLeast(0L)))))
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            null
        }
    }

    // ------------------------------------------------------------------
    // Google Drive
    // ------------------------------------------------------------------

    /**
     * A Drive file link: Docs/Sheets/Slides (export choices) or an original file (zip, pdf, video ...).
     * @return null when the original file can't be read directly; yt-dlp is tried then (videos often still work).
     */
    private suspend fun driveFile(url: String): Fetched.File? = withContext(Dispatchers.IO) {
        val cookies = AvenApp.instance.cookies
        val cookie = cookies.headerFor("drive.usercontent.google.com")
        val id = DriveFiles.fileId(url) ?: return@withContext null
        val kind = DriveFiles.docKind(url)
        if (kind != null) {
            val exports = DriveFiles.exportsFor(kind)
            // The first export is probed so a private document gives a clear error and the title is known.
            val name = DriveFiles.probeExport(kind, id, exports.first().fmt, cookies.headerFor("docs.google.com"))
            val title = name.substringBeforeLast('.', name)
            return@withContext Fetched.File(
                FileInfo(url, title, null, false, exports.map { FileChoice(it.label, "export:$kind:$id:${it.fmt}", 0L) })
            )
        }
        val p = try { DriveFiles.probe(id, cookie) } catch (ce: CancellationException) { throw ce } catch (t: Throwable) {
            return@withContext null   // yt-dlp gets a turn (a video may still stream even when the original is blocked)
        }
        val video = DriveFiles.isVideoName(p.name, p.mime)
        val ext = p.name.substringAfterLast('.', "").uppercase()
        val label = (if (video) "Original video" else "Original file") + (if (ext.isNotEmpty() && ext.length <= 5) " · $ext" else "")
        Fetched.File(FileInfo(url, p.name, p.mime, video, listOf(FileChoice(label, "drive:$id", p.size))))
    }

    /** Folder: yt-dlp first; if its folder extractor is broken, a simple page reader lists the files instead. */
    private suspend fun fetchDriveFolder(url: String): Fetched {
        try {
            val out = runInfo(url, playlist = true)
            if (InfoParser.isPlaylist(out)) {
                val pl = InfoParser.parsePlaylist(url, out)
                if (pl.entries.isNotEmpty()) return Fetched.Playlist(pl)
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            // fall through to the page reader
        }
        val id = DriveFiles.fileId(url) ?: Regex("/folders/([A-Za-z0-9_-]+)").find(url)?.groupValues?.get(1)
        val list = try {
            withContext(Dispatchers.IO) { DriveFiles.listFolder(id ?: "", AvenApp.instance.cookies.headerFor("drive.google.com")) }
        } catch (ce: CancellationException) { throw ce } catch (t: Throwable) { emptyList() }
        val files = list.filter { !it.isFolder }
        if (files.isEmpty()) {
            throw IllegalArgumentException(
                "DL: Couldn't read this Drive folder. The folder must be shared with \"Anyone with the link\". " +
                    "If it is, Settings → Update channel → Nightly, then Update engine, may fix it. Or open the files one by one."
            )
        }
        return Fetched.Playlist(
            PlaylistInfo(url, "Drive folder", null, files.map {
                PlaylistEntry("https://drive.google.com/file/d/${it.id}/view", it.name, 0, null, null)
            })
        )
    }

    /** One video, with its full format list. Cancelling the coroutine stops the process. */
    suspend fun fetchInfo(url: String): MediaInfo {
        if (UrlParser.isYoutubeSingleVideo(url)) {
            try { return YouTubeFast.fetchVideo(url) } catch (ce: CancellationException) { throw ce } catch (t: Throwable) { }
        }
        awaitReady()
        return InfoParser.parse(url, runInfo(url, playlist = false))
    }

    private suspend fun runInfo(url: String, playlist: Boolean): String {
        val settings = AvenApp.instance.settings.flow.first()
        return withClients(url) { client ->
            val request = YoutubeDLRequest(url)
            request.addOption("--dump-single-json")
            request.addOption("--no-warnings")
            request.addOption("--socket-timeout", "20")
            if (playlist) {
                request.addOption("--flat-playlist")
                request.addOption("--yes-playlist")
                request.addOption("--playlist-end", "300")   // a list this long is already plenty for a phone
            } else {
                request.addOption("--no-playlist")
            }
            val cookieCopy = configure(request, settings, client)
            try {
                withContext(Dispatchers.IO) {
                    runInterruptible { YoutubeDL.execute(request, null, false, null) }.out
                }
            } finally {
                cookieCopy?.delete()
            }
        }
    }

    // ------------------------------------------------------------------
    // Shared yt-dlp options
    // ------------------------------------------------------------------

    /**
     * Adds options every yt-dlp call needs: IPv4, the user's login cookies (if any) and the YouTube client.
     * @return the temporary cookie file (delete it when the call is done), or null.
     */
    fun configure(request: YoutubeDLRequest, settings: Settings, client: String?): File? {
        if (settings.forceIpv4) request.addOption("--force-ipv4")
        // skip=translated_subs: don't list auto-translated subtitles (hundreds of entries, slows YouTube parsing).
        request.addOption(
            "--extractor-args",
            if (client != null) "youtube:player_client=$client;skip=translated_subs" else "youtube:skip=translated_subs"
        )
        val copy = AvenApp.instance.cookies.copyForRun()
        if (copy != null) request.addOption("--cookies", copy.absolutePath)
        return copy
    }

    /**
     * YouTube answers differently to different "player clients". When one is refused (403 / PO token /
     * bot check) the next one is tried automatically. Other sites run once.
     */
    suspend fun <T> withClients(url: String, block: suspend (client: String?) -> T): T {
        if (!UrlParser.isYoutube(url)) return block(null)
        val order = listOf(lastGoodClient) + CLIENTS.filter { it != lastGoodClient }
        var last: Throwable? = null
        for (c in order) {
            try {
                val result = block(if (c == "default") null else c)
                lastGoodClient = c
                return result
            } catch (ce: CancellationException) {
                throw ce
            } catch (ce: YoutubeDL.CanceledException) {
                throw ce
            } catch (ie: InterruptedException) {
                throw ie
            } catch (t: Throwable) {
                last = t
                if (!shouldTryNextClient(t.message)) throw t
            }
        }
        throw last ?: IllegalStateException("No result")
    }

    // ------------------------------------------------------------------
    // Maintenance
    // ------------------------------------------------------------------

    fun ytDlpVersion(): String = try {
        YoutubeDL.versionName(app) ?: "unknown"
    } catch (t: Throwable) { "unknown" }

    /**
     * Downloads the latest yt-dlp. A failed update is not always a real failure (the library reports an error
     * when its own check fails even though the installed version is current), so the installed version is
     * compared with the newest GitHub release before an error is shown.
     */
    suspend fun updateYtDlp(channel: String = "stable"): String {
        awaitReady()
        return withContext(Dispatchers.IO) {
            val before = ytDlpVersion()
            try {
                when (YoutubeDL.updateYoutubeDL(app, channelOf(channel))) {
                    YoutubeDL.UpdateStatus.DONE -> {
                        val now = ytDlpVersion()
                        if (now != before) "Engine updated" else "Already up to date"
                    }
                    else -> "Already up to date"
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val latest = latestTag(channel)
                if (latest != null && before != "unknown" && (before == latest || before.startsWith("$latest.") || latest.startsWith("$before.")))
                    "Already up to date"
                else
                    "Couldn't update right now. Check your internet connection and try again."
            }
        }
    }

    /** Newest yt-dlp release tag on GitHub (e.g. "2025.10.22"), or null when it can't be read. */
    private fun latestTag(channel: String): String? = try {
        val repo = if (channel == "nightly") "yt-dlp/yt-dlp-nightly-builds" else "yt-dlp/yt-dlp"
        val c = java.net.URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 10_000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
    } catch (t: Throwable) { null }

    companion object {
        private const val CACHE_MS = 10 * 60 * 1000L
        private val CLIENTS = listOf("default", "tv", "mweb", "android")

        private val RETRY_MARKERS = listOf(
            "403", "po token", "sign in", "not a bot", "confirm you", "format is not available", "sabr"
        )

        /** Errors another YouTube client may fix. Private/unavailable videos and rate limits are not retried. */
        fun shouldTryNextClient(message: String?): Boolean {
            val l = message?.lowercase() ?: return false
            if ("private video" in l || "has been removed" in l || "video unavailable" in l || "http error 429" in l) return false
            return RETRY_MARKERS.any { it in l }
        }
    }
}
