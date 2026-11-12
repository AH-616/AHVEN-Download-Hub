package com.aven.downloader.engine

/**
 * Decides whether the app's own downloader can fetch a video/audio choice (plain HTTP streams), and which
 * streams and which file type that means. Returns null for HLS/DASH-only links: yt-dlp handles those.
 */
object DirectPlanner {
    class Plan(
        val video: StreamFormat?,      // null for audio-only
        val audio: StreamFormat?,      // separate audio stream to merge in, or null
        val ext: String,               // file type of the result: mp4 | webm | m4a | weba
        val webm: Boolean,             // merge into a WebM container (VP9 + Opus)
        val extractAudio: Boolean      // audio-only from a finished video: cut the sound out of it
    )

    private fun direct(f: StreamFormat?): Boolean =
        f != null && !f.url.isNullOrEmpty() && f.ext != "mhtml" && (f.protocol == null || f.protocol == "https" || f.protocol == "http")

    fun plan(info: MediaInfo, spec: DownloadSpec): Plan? {
        if (info.isLive) return null
        return when (spec.kind) {
            DownloadKind.VIDEO -> planVideo(info, spec)
            DownloadKind.AUDIO -> planAudio(info, spec)
            DownloadKind.FILE -> null
        }
    }

    private fun planVideo(info: MediaInfo, spec: DownloadSpec): Plan? {
        var v: StreamFormat? = null
        var a: StreamFormat? = null
        // The exact streams the size estimate was made for (YouTube itags), when the user didn't pick another language.
        val ids = spec.pinned.split('+').filter { it.isNotBlank() }
        if (ids.isNotEmpty() && spec.audioLang.isEmpty()) {
            val fv = info.formats.firstOrNull { it.id == ids[0] }
            val fa = if (ids.size > 1) info.formats.firstOrNull { it.id == ids[1] } else null
            if (fv != null && fv.hasVideo && (ids.size == 1 || fa != null)) { v = fv; a = fa }
        }
        if (v == null) {
            val chosen = FormatPlanner.chooseStreams(info, spec.heightCap, "mp4", spec.audioLang) ?: return null
            v = chosen.first
            a = chosen.second
        }
        if (!direct(v) || (a != null && !direct(a))) return null
        if (a == null) return Plan(v, null, extOfVideo(v), webm = v.ext == "webm", extractAudio = false)
        val webm = v.ext == "webm" && !FormatPlanner.isM4a(a)
        return Plan(v, a, if (webm) "webm" else "mp4", webm, false)
    }

    private fun extOfVideo(v: StreamFormat): String = when (v.ext) {
        "webm" -> "webm"
        "mkv" -> "mkv"
        "mov" -> "mov"
        "", "mp4", "m4v" -> "mp4"
        else -> v.ext
    }

    private fun planAudio(info: MediaInfo, spec: DownloadSpec): Plan? {
        val a = FormatPlanner.chooseAudio(info.formats, null, spec.audioLang, preferOpus = spec.audioFormat == "opus")
        if (a != null) {
            if (!direct(a)) return null
            return Plan(null, a, if (FormatPlanner.isM4a(a)) "m4a" else "weba", webm = false, extractAudio = false)
        }
        // Only finished videos on offer: take the smallest one and cut the sound out of it.
        val muxed = info.formats.filter { it.hasAudio && it.hasVideo && direct(it) }
            .minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE } ?: return null
        return Plan(muxed, null, "m4a", webm = false, extractAudio = true)
    }
}
