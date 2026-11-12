package com.aven.downloader.engine

enum class DownloadKind { VIDEO, AUDIO, FILE }

/** What the user picked. Stored with the download so retry/resume re-uses it. */
data class DownloadSpec(
    val kind: DownloadKind,
    val heightCap: Int,          // video only; 0 = best available
    val container: String,       // "mp4" (H.264/AAC preferred; the real file type follows the codecs: MP4 or WebM)
    val audioFormat: String,     // "m4a" or "opus" (old downloads may still say "mp3": treated as m4a)
    val label: String,
    val audioLang: String = "",  // "" = default/original audio track, else yt-dlp language tag (e.g. "hi")
    val pinned: String = "",     // exact yt-dlp format ids the size estimate was made for, e.g. "137+140" ("" = none)
    val fileSource: String = ""  // FILE kind only: "drive:<id>" or "export:<kind>:<id>:<fmt>"
)

data class VideoOption(val label: String, val heightCap: Int, val estimatedSize: Long, val height: Int = heightCap, val pinned: String = "")
/** One selectable audio language of a video (YouTube videos can carry several dubbed tracks). */
data class AudioTrack(val lang: String, val label: String, val isDefault: Boolean)

data class AudioOption(val label: String, val audioFormat: String, val estimatedSize: Long)

/** One yt-dlp command line argument: either a flag or an option with a value. */
data class Arg(val option: String, val value: String? = null)

/** Pure logic: which options to show for a video and which yt-dlp arguments they map to. */
object FormatPlanner {

    /** Only heights that really exist in this video's format list are offered. */
    fun videoOptions(info: MediaInfo, container: String = "mp4"): List<VideoOption> {
        val video = info.formats.filter { it.hasVideo && it.height > 0 }
        if (video.isEmpty()) {
            // Heights unknown: the only honest option is "Best available".
            return listOf(VideoOption("Best available", 0, 0))
        }
        val heights = video.map { it.height }.distinct().sortedDescending()
        val best = heights.first()
        val bp = pick(info, best, container)
        val out = mutableListOf(VideoOption("Best (${best}p)", 0, bp.size, best, bp.pinned))
        heights.drop(1).forEach { h ->
            val p = pick(info, h, container)
            out += VideoOption("${h}p", h, p.size, h, p.pinned)
        }
        return out
    }

    fun audioOptions(info: MediaInfo): List<AudioOption> {
        val dur = info.durationSec
        val audioOnly = info.formats.filter { it.hasAudio && !it.hasVideo }
        val out = mutableListOf<AudioOption>()
        if (audioOnly.isEmpty()) {
            // A site that only offers finished videos: the sound is cut out of the smallest one (no re-encoding).
            val muxed = info.formats.filter { it.hasAudio && it.hasVideo && it.url != null }
                .minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE } ?: return emptyList()
            out += AudioOption("M4A (sound of the video)", "m4a", sizeOf(muxed, dur))
            return out
        }
        val m4aSource = audioOnly.filter { isM4a(it) }.maxByOrNull { sizeOf(it, dur) }
        val m4a = m4aSource?.let { sizeOf(it, dur) }
            ?: if (dur > 0) dur.toLong() * M4A_KBPS * 1000L / 8L else bestAudio(info.formats)?.let { sizeOf(it, dur) } ?: 0L
        if (m4aSource != null || audioOnly.none { !isM4a(it) }) out += AudioOption("M4A (original quality)", "m4a", m4a)
        val opusSource = audioOnly.filter { !isM4a(it) }.maxByOrNull { sizeOf(it, dur) }
        if (opusSource != null) out += AudioOption("Opus (WebM audio)", "opus", sizeOf(opusSource, dur))
        if (out.isEmpty()) out += AudioOption("Audio (original quality)", "m4a", m4a)
        return out
    }

    /**
     * Audio languages offered for this video. Empty unless the video really has 2+ different
     * audio languages (e.g. YouTube auto-dubbed / multi-language tracks).
     */
    fun audioTracks(info: MediaInfo): List<AudioTrack> {
        val withLang = info.formats.filter { it.hasAudio && !it.hasVideo && !it.language.isNullOrBlank() }
        val byLang = withLang.groupBy { it.language!! }
        if (byLang.size < 2) return emptyList()
        val tracks = byLang.map { (lang, fs) ->
            val isDefault = fs.any { val n = it.note.orEmpty().lowercase(); "default" in n || "original" in n }
            val name = java.util.Locale.forLanguageTag(lang).getDisplayName(java.util.Locale.ENGLISH)
                .takeIf { it.isNotBlank() && it != lang } ?: lang
            AudioTrack(lang, if (isDefault) "$name (original)" else name, isDefault)
        }
        return tracks.sortedWith(compareByDescending<AudioTrack> { it.isDefault }.thenBy { it.label })
    }

    /** Picks the option closest to the user's default quality without exceeding it. */
    fun pickDefault(options: List<VideoOption>, preferredHeight: Int): Int {
        if (preferredHeight <= 0 || options.isEmpty()) return 0
        val idx = options.indexOfFirst { it.height in 1..preferredHeight }
        if (idx >= 0) return idx
        // Everything is above the preferred height (or "Best" is the only choice): pick the lowest.
        return options.lastIndex
    }

    /**
     * yt-dlp arguments for the fallback path (links the own downloader can't handle: HLS/DASH-only sites).
     * FFmpeg is not bundled, so only formats that are one finished file (or an HLS stream) are asked for.
     * @param fragments how many pieces of a segmented (HLS/DASH) video download at the same time.
     * @param chunked download in 10 MB pieces, which avoids YouTube slowing down one long connection.
     */
    fun buildArgs(spec: DownloadSpec, fragments: Int = 4, chunked: Boolean = false): List<Arg> {
        val args = mutableListOf<Arg>()
        when (spec.kind) {
            DownloadKind.VIDEO -> {
                val h = spec.heightCap
                val hv = if (h > 0) "[height<=$h]" else ""
                val l = if (spec.audioLang.isNotEmpty()) "[language^=${langKey(spec.audioLang)}]" else ""
                val selector = if (h > 0) "b$hv$l/b$hv/b/w" else "b$l/b/w"
                args += Arg("-f", selector)
            }
            DownloadKind.FILE -> return emptyList()   // plain files are downloaded by our own downloader
            DownloadKind.AUDIO -> {
                val l = if (spec.audioLang.isNotEmpty()) "[language^=${langKey(spec.audioLang)}]" else ""
                val sel = if (spec.audioFormat == "opus") {
                    if (l.isEmpty()) "ba[ext=webm]/ba/b" else "ba[ext=webm]$l/ba$l/ba[ext=webm]/ba/b"
                } else {
                    if (l.isEmpty()) "ba[ext=m4a]/ba/b" else "ba[ext=m4a]$l/ba$l/ba[ext=m4a]/ba/b"
                }
                args += Arg("-f", sel)
            }
        }
        args += Arg("--no-playlist")
        args += Arg("--no-mtime")
        args += Arg("--windows-filenames")
        args += Arg("--retries", "5")
        args += Arg("--fragment-retries", "5")
        args += Arg("--socket-timeout", "30")
        args += Arg("-N", fragments.coerceIn(1, 32).toString())
        if (chunked) args += Arg("--http-chunk-size", "10M")
        return args
    }

    private const val M4A_KBPS = 130

    // ---- helpers ----

    /**
     * Estimates the size of the file that will really be downloaded: the same video/audio stream the own
     * downloader picks at download time (H.264 + M4A first; VP9 + Opus where there is no H.264), not the biggest one on offer.
     */
    internal data class Pick(val size: Long, val pinned: String)

    internal fun pick(info: MediaInfo, height: Int, container: String): Pick {
        val dur = info.durationSec
        val v = chooseVideo(info.formats.filter { it.hasVideo && it.height == height }, container, dur) ?: return Pick(0, "")
        var total = sizeOf(v, dur)
        if (v.hasAudio) return Pick(total, v.id)
        val a = chooseAudio(info.formats, v, "")
        total += a?.let { sizeOf(it, dur) } ?: 0L
        return Pick(total, if (a != null) "${v.id}+${a.id}" else v.id)
    }

    /**
     * The video (and the separate audio stream it needs) that a download of [heightCap] ([container] "mp4") uses.
     * [heightCap] 0 = best. Used by the own downloader at download time.
     */
    internal fun chooseStreams(info: MediaInfo, heightCap: Int, container: String, lang: String): Pair<StreamFormat, StreamFormat?>? {
        val withVideo = info.formats.filter { it.hasVideo }
        if (withVideo.isEmpty()) return null
        val heights = withVideo.map { it.height }.filter { it > 0 }.distinct().sortedDescending()
        val v = if (heights.isEmpty()) {
            withVideo.maxByOrNull { it.tbr }
        } else {
            val h = if (heightCap <= 0) heights.first() else (heights.firstOrNull { it <= heightCap } ?: heights.last())
            chooseVideo(withVideo.filter { it.height == h }, container, info.durationSec)
        } ?: return null
        if (v.hasAudio) return v to null
        return v to chooseAudio(info.formats, v, lang)
    }

    private fun isH264(vcodec: String?) = vcodec != null && (vcodec.startsWith("avc") || vcodec.startsWith("h264"))
    internal fun isM4a(f: StreamFormat) = f.ext == "m4a" || (f.acodec?.startsWith("mp4a") == true && f.ext != "webm")

    /** Codecs the platform MP4 muxer takes on every supported Android version. */
    internal fun isMp4Video(vcodec: String?): Boolean = vcodec != null &&
        (isH264(vcodec) || vcodec.startsWith("hev") || vcodec.startsWith("hvc") || vcodec.startsWith("h265") || vcodec.startsWith("mp4v"))

    /** "en-US" -> "en" so it also matches "en-GB"; keeps only safe characters for the selector. */
    private fun langKey(lang: String): String = lang.substringBefore('-').filter { it.isLetterOrDigit() }

    /** Lower = preferred by yt-dlp's default sort for MKV (av1 > vp9 > h265 > h264). */
    private fun codecRank(vcodec: String?): Int = when {
        vcodec == null -> 9
        vcodec.startsWith("av01") || vcodec.startsWith("av1") -> 0
        vcodec.startsWith("vp9") || vcodec.startsWith("vp09") -> 1
        vcodec.startsWith("hev") || vcodec.startsWith("hvc") || vcodec.startsWith("h265") -> 2
        isH264(vcodec) -> 3
        else -> 4
    }

    /** Lower = preferred for a file that must play everywhere: H.264, H.265, VP9 (WebM), AV1 last. */
    private fun playRank(vcodec: String?): Int = when {
        isH264(vcodec) -> 0
        vcodec != null && (vcodec.startsWith("hev") || vcodec.startsWith("hvc") || vcodec.startsWith("h265")) -> 1
        vcodec != null && (vcodec.startsWith("vp9") || vcodec.startsWith("vp09")) -> 2
        vcodec != null && (vcodec.startsWith("av01") || vcodec.startsWith("av1")) -> 4
        else -> 3
    }

    internal fun chooseVideo(candidates: List<StreamFormat>, container: String, durationSec: Int): StreamFormat? {
        if (candidates.isEmpty()) return null
        return if (container == "mp4") {
            val best = candidates.minOf { playRank(it.vcodec) }
            candidates.filter { playRank(it.vcodec) == best }.maxByOrNull { sizeOf(it, durationSec) }
        } else {
            candidates.sortedWith(
                compareBy<StreamFormat> { codecRank(it.vcodec) }.thenByDescending { sizeOf(it, durationSec) }
            ).first()
        }
    }

    /**
     * The audio stream that goes with [video]: AAC (M4A) for H.264/H.265 video (MP4 file), Opus (WebM) for VP9/AV1.
     * [lang] "" = the original track when the video has several languages.
     */
    internal fun chooseAudio(formats: List<StreamFormat>, video: StreamFormat?, lang: String, preferOpus: Boolean = false): StreamFormat? {
        var audio = formats.filter { it.hasAudio && !it.hasVideo }
        if (audio.isEmpty()) return null
        val langs = audio.mapNotNull { it.language }.map { langKey(it) }.distinct()
        if (lang.isNotEmpty()) {
            audio.filter { it.language?.let { l -> langKey(l) == langKey(lang) } == true }.takeIf { it.isNotEmpty() }?.let { audio = it }
        } else if (langs.size > 1) {
            audio.filter { val n = it.note.orEmpty().lowercase(); "default" in n || "original" in n }.takeIf { it.isNotEmpty() }?.let { audio = it }
        }
        val wantM4a = if (preferOpus) false else video == null || isMp4Video(video.vcodec)
        val pool = (if (wantM4a) audio.filter { isM4a(it) } else audio.filter { !isM4a(it) }).ifEmpty { audio }
        return pool.maxByOrNull { if (it.abr > 0) it.abr else it.tbr }
    }

    private fun bestAudio(formats: List<StreamFormat>): StreamFormat? =
        formats.filter { it.hasAudio && !it.hasVideo }.maxByOrNull { if (it.abr > 0) it.abr else it.tbr }

    internal fun sizeOf(f: StreamFormat, durationSec: Int): Long {
        if (f.fileSize > 0) return f.fileSize
        if (f.tbr > 0 && durationSec > 0) return (f.tbr * 1000 / 8 * durationSec).toLong()
        return 0
    }
}
