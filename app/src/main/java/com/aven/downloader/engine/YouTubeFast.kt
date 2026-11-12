package com.aven.downloader.engine

import com.aven.downloader.net.Net
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo as NpPlaylist
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.Locale

/** NewPipe's HTTP layer on top of the app's OkHttp client (so it follows the DNS-over-HTTPS setting). */
private object NpDownloader : Downloader() {
    override fun execute(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", Net.DESKTOP_UA)
        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }
        Net.client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            val text = response.body?.string().orEmpty()
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                text,
                response.request.url.toString()
            )
        }
    }
}

/**
 * The fast path for YouTube. NewPipe Extractor is plain Java HTTP (no Python start-up), so a video's
 * formats arrive in about a second instead of several. Anything it can't read (live streams, age-gated or
 * login-only videos, a YouTube change it doesn't know yet) throws, and [Engine] then asks yt-dlp.
 */
object YouTubeFast {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var ready = false

    /** The headers every YouTube stream request carries. */
    val STREAM_HEADERS: Map<String, String> = mapOf("User-Agent" to Net.DESKTOP_UA)

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (ready) return
            val locale = Locale.getDefault()
            val country = locale.country.ifBlank { "US" }
            NewPipe.init(NpDownloader, Localization(locale.language.ifBlank { "en" }, country), ContentCountry(country))
            ready = true
        }
    }

    private val idRe = Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/live/|/embed/|/v/)([A-Za-z0-9_-]{11})""")

    fun videoId(url: String): String? = idRe.find(url)?.groupValues?.get(1)

    /** Runs [block] on a worker; gives up (returns null) after [ms] without waiting for a stuck socket. */
    private suspend fun <T> timed(ms: Long, block: () -> T): T? {
        val d = scope.async { block() }
        val r = withTimeoutOrNull(ms) { d.await() }
        if (r == null) d.cancel()
        return r
    }

    /** One video with its formats, or throws. */
    suspend fun fetchVideo(url: String): MediaInfo {
        val id = videoId(url) ?: throw IllegalArgumentException("not a single YouTube video")
        val info = timed(TIMEOUT_MS) {
            init()
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$id")
        } ?: throw java.io.IOException("YouTube took too long to answer")
        return toMediaInfo(url, info)
    }

    /** A YouTube playlist page (first [max] videos), or throws. */
    suspend fun fetchPlaylist(url: String, max: Int = 300): PlaylistInfo {
        val result = timed(TIMEOUT_MS * 2) {
            init()
            val service = ServiceList.YouTube
            val info = NpPlaylist.getInfo(service, url)
            val items = ArrayList<StreamInfoItem>(info.relatedItems)
            var hasMore = info.hasNextPage()
            var page = info.nextPage
            while (hasMore && page != null && items.size < max) {
                val more = NpPlaylist.getMoreItems(service, url, page)
                items += more.items
                hasMore = more.hasNextPage()
                page = more.nextPage
            }
            val entries = items.take(max).mapNotNull { it.toEntry() }
            PlaylistInfo(url, info.name.orEmpty().ifBlank { "Playlist" }, info.uploaderName, entries)
        } ?: throw java.io.IOException("YouTube took too long to answer")
        if (result.entries.isEmpty()) throw IllegalStateException("empty playlist")
        return result
    }

    private fun StreamInfoItem.toEntry(): PlaylistEntry? {
        val u = url ?: return null
        return PlaylistEntry(
            url = u,
            title = name.orEmpty().ifBlank { "Video" },
            durationSec = duration.toInt().coerceAtLeast(0),
            thumbnail = thumbnails.maxByOrNull { it.height }?.url,
            uploader = uploaderName
        )
    }

    private fun toMediaInfo(sourceUrl: String, info: StreamInfo): MediaInfo {
        if (info.streamType != StreamType.VIDEO_STREAM) throw IllegalStateException("not a normal video")
        val formats = ArrayList<StreamFormat>()
        info.videoStreams.forEach { videoFormat(it, false)?.let(formats::add) }
        info.videoOnlyStreams.forEach { videoFormat(it, true)?.let(formats::add) }

        val audio = info.audioStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        // Several dubbed languages: each language gets its own track label; ids stay unique with a suffix.
        val trackIds = audio.mapNotNull { it.audioTrackId?.takeIf { t -> t.isNotBlank() } }.distinct()
        audio.forEach { a ->
            val idx = a.audioTrackId?.let { trackIds.indexOf(it) } ?: -1
            val id = if (trackIds.size > 1 && idx >= 0) "${a.itag}-$idx" else a.itag.toString()
            val lang = if (trackIds.size > 1) (a.audioLocale?.toLanguageTag() ?: a.audioTrackId?.substringBefore('.')) else null
            val note = if (trackIds.size > 1) {
                val n = a.audioTrackName.orEmpty()
                if (a.audioTrackType == AudioTrackType.ORIGINAL) "$n, original (default)" else n
            } else null
            val codec = a.codec.orEmpty()
            val abr = if (a.averageBitrate > 0) a.averageBitrate.toDouble() else a.bitrate / 1000.0
            formats += StreamFormat(
                id = id,
                ext = a.format?.suffix ?: "m4a",
                height = 0, width = 0,
                vcodec = "none",
                acodec = codec.ifBlank { if (a.format?.suffix == "m4a") "mp4a.40.2" else "opus" },
                tbr = abr, abr = abr,
                fileSize = a.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L,
                url = a.content,
                protocol = "https",
                headers = STREAM_HEADERS,
                language = lang,
                note = note
            )
        }
        if (formats.isEmpty()) throw IllegalStateException("no formats")
        return MediaInfo(
            sourceUrl = sourceUrl,
            title = info.name.orEmpty().ifBlank { "Untitled video" },
            uploader = info.uploaderName,
            durationSec = info.duration.toInt().coerceAtLeast(0),
            thumbnail = info.thumbnails.maxByOrNull { it.height }?.url,
            extractor = "Youtube",
            isLive = false,
            formats = formats
        )
    }

    private fun videoFormat(v: VideoStream, videoOnly: Boolean): StreamFormat? {
        if (!v.isUrl || v.deliveryMethod != DeliveryMethod.PROGRESSIVE_HTTP) return null
        val codec = v.codec.orEmpty()
        return StreamFormat(
            id = v.itag.toString(),
            ext = v.format?.suffix ?: "mp4",
            height = v.height, width = v.width,
            vcodec = codec.ifBlank { null },
            acodec = if (videoOnly) "none" else "mp4a.40.2",
            tbr = v.bitrate / 1000.0, abr = 0.0,
            fileSize = v.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L,
            url = v.content,
            protocol = "https",
            headers = STREAM_HEADERS
        )
    }

    private const val TIMEOUT_MS = 20_000L
}
