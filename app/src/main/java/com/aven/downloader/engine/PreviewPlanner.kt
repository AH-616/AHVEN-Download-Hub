package com.aven.downloader.engine

/** What the in-app player should play. */
sealed interface PreviewSource {
    data class Single(val url: String, val headers: Map<String, String>, val hls: Boolean) : PreviewSource
    data class Merged(val videoUrl: String, val audioUrl: String, val headers: Map<String, String>) : PreviewSource
}

/**
 * Chooses a stream ExoPlayer can play. Not every video has one (DASH-only, manifests,
 * protected streams), in which case this returns null and the UI says so honestly.
 */
object PreviewPlanner {
    private val progressive = setOf("https", "http")
    private val hlsProtocols = setOf("m3u8", "m3u8_native")
    private const val PREVIEW_MAX_HEIGHT = 720

    fun choose(info: MediaInfo): PreviewSource? {
        if (info.isLive) return null

        val combined = info.formats.filter {
            it.url != null && it.hasVideo && it.hasAudio && it.ext != "mhtml" &&
                (it.protocol == null || it.protocol in progressive || it.protocol in hlsProtocols)
        }
        pickByHeight(combined)?.let { f ->
            return PreviewSource.Single(f.url!!, cleanHeaders(f.headers), f.protocol in hlsProtocols)
        }

        val videoOnly = info.formats.filter {
            it.url != null && it.hasVideo && !it.hasAudio && it.ext != "mhtml" &&
                (it.protocol == null || it.protocol in progressive)
        }
        val audioOnly = info.formats.filter {
            it.url != null && it.hasAudio && !it.hasVideo &&
                (it.protocol == null || it.protocol in progressive)
        }
        val v = pickByHeight(videoOnly) ?: return null
        val a = audioOnly.maxByOrNull { (if (it.ext == "m4a") 1000 else 0) + it.abr } ?: return null
        return PreviewSource.Merged(v.url!!, a.url!!, cleanHeaders(v.headers))
    }

    /** Highest quality up to 720p (cheaper preview); otherwise the smallest available. Prefers mp4. */
    private fun pickByHeight(list: List<StreamFormat>): StreamFormat? {
        if (list.isEmpty()) return null
        val capped = list.filter { it.height in 1..PREVIEW_MAX_HEIGHT }
        val pool = capped.ifEmpty { return list.minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE } }
        return pool.maxWithOrNull(compareBy<StreamFormat> { it.height }.thenBy { if (it.ext == "mp4") 1 else 0 })
    }

    /** Accept-Encoding must not be forwarded: ExoPlayer needs identity-encoded media bytes. */
    fun cleanHeaders(h: Map<String, String>): Map<String, String> =
        h.filterKeys { !it.equals("Accept-Encoding", ignoreCase = true) }
}
