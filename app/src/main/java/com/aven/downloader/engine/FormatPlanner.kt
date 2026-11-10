package com.aven.downloader.engine

enum class DownloadKind { VIDEO, AUDIO, FILE }

/** What the user picked. Stored with the download so retry/resume re-uses it. */
data class DownloadSpec(
    val kind: DownloadKind,
    val heightCap: Int,          // video only; 0 = best available
    val container: String,       // "mp4" or "mkv"
    val audioFormat: String,     // "mp3" or "m4a"
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
        val hasAudio = info.formats.any { it.hasAudio }
        if (!hasAudio) return emptyList()
        val dur = info.durationSec
        // MP3 is re-encoded at the best VBR quality (about 245 kbps), so its size depends on the length,
        // not on the size of the source stream.
        val mp3 = if (dur > 0) dur.toLong() * MP3_KBPS * 1000L / 8L else bestAudio(info.formats)?.let { sizeOf(it, dur) } ?: 0L
        val m4aSource = info.formats.filter { it.hasAudio && !it.hasVideo && isM4a(it) }
            .maxByOrNull { sizeOf(it, dur) }
        val m4a = m4aSource?.let { sizeOf(it, dur) }
            ?: if (dur > 0) dur.toLong() * M4A_KBPS * 1000L / 8L else bestAudio(info.formats)?.let { sizeOf(it, dur) } ?: 0L
        return listOf(
            AudioOption("MP3 (converted)", "mp3", mp3),
            AudioOption("M4A (original quality)", "m4a", m4a)
        )
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
     * @param fragments how many pieces of a segmented (HLS/DASH) video download at the same time.
     * @param chunked download in 10 MB pieces, which avoids YouTube slowing down one long connection.
     */
    fun buildArgs(spec: DownloadSpec, fragments: Int = 4, chunked: Boolean = false): List<Arg> {
        val args = mutableListOf<Arg>()
        when (spec.kind) {
            DownloadKind.VIDEO -> {
                val h = spec.heightCap
                val hv = if (h > 0) "[height<=$h]" else ""
                val core = if (spec.audioLang.isNotEmpty()) {
                    val l = "[language^=${langKey(spec.audioLang)}]"
                    "bv*$hv+ba$l/bv*$hv+ba/b$hv/bv*+ba/b"
                } else if (h > 0) "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b" else "bv*+ba/b"
                // The exact streams that were measured for the size estimate come first; the general
                // selector is only the fallback if those ids are no longer offered at download time.
                val pin = spec.pinned.filter { it.isLetterOrDigit() || it == '+' || it == '-' || it == '_' || it == '.' }
                val selector = if (pin.isNotEmpty() && spec.audioLang.isEmpty()) "$pin/$core" else core
                args += Arg("-f", selector)
                args += Arg("--merge-output-format", spec.container)
                // MP4: prefer H.264 + AAC so every player can open the file. MKV: keep best codecs.
                if (spec.container == "mp4") args += Arg("-S", "res,vcodec:h264,acodec:m4a")
            }
            DownloadKind.FILE -> return emptyList()   // plain files are downloaded by our own downloader
            DownloadKind.AUDIO -> {
                val l = if (spec.audioLang.isNotEmpty()) "[language^=${langKey(spec.audioLang)}]" else ""
                val sel = if (spec.audioFormat == "m4a") {
                    if (l.isEmpty()) "ba[ext=m4a]/ba/b" else "ba[ext=m4a]$l/ba$l/ba[ext=m4a]/ba/b"
                } else {
                    if (l.isEmpty()) "ba/b" else "ba$l/ba/b"
                }
                args += Arg("-f", sel)
                args += Arg("-x")
                args += Arg("--audio-format", spec.audioFormat)
                args += Arg("--audio-quality", "0")
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

    private const val MP3_KBPS = 245   // yt-dlp --audio-quality 0 = LAME V0, about 220-260 kbps
    private const val M4A_KBPS = 130

    // ---- helpers ----

    /** "en-US" -> "en" so it also matches "en-GB"; keeps only safe characters for the selector. */
    private fun langKey(lang: String): String = lang.substringBefore('-').filter { it.isLetterOrDigit() }

    /**
     * Estimates the size of the file that will really be downloaded: the same video/audio stream that
     * [buildArgs] makes yt-dlp pick (MP4: H.264 + M4A; MKV: newest codec), not the biggest one on offer.
     */
    internal data class Pick(val size: Long, val pinned: String)

    internal fun pick(info: MediaInfo, height: Int, container: String): Pick {
        val dur = info.durationSec
        val v = chooseVideo(info.formats.filter { it.hasVideo && it.height == height }, container, dur) ?: return Pick(0, "")
        var total = sizeOf(v, dur)
        if (v.hasAudio) return Pick(total, v.id)
        val a = chooseAudio(info.formats, container, dur)
        total += a?.let { sizeOf(it, dur) } ?: 0L
        return Pick(total, if (a != null) "${v.id}+${a.id}" else v.id)
    }

    private fun isH264(vcodec: String?) = vcodec != null && (vcodec.startsWith("avc") || vcodec.startsWith("h264"))
    private fun isM4a(f: StreamFormat) = f.ext == "m4a" || (f.acodec?.startsWith("mp4a") == true && f.ext != "webm")

    /** Lower = preferred by yt-dlp's default sort for MKV (av1 > vp9 > h265 > h264). */
    private fun codecRank(vcodec: String?): Int = when {
        vcodec == null -> 9
        vcodec.startsWith("av01") || vcodec.startsWith("av1") -> 0
        vcodec.startsWith("vp9") || vcodec.startsWith("vp09") -> 1
        vcodec.startsWith("hev") || vcodec.startsWith("hvc") || vcodec.startsWith("h265") -> 2
        isH264(vcodec) -> 3
        else -> 4
    }

    internal fun chooseVideo(candidates: List<StreamFormat>, container: String, durationSec: Int): StreamFormat? {
        if (candidates.isEmpty()) return null
        return if (container == "mp4") {
            val h264 = candidates.filter { isH264(it.vcodec) }
            (if (h264.isEmpty()) candidates else h264).maxByOrNull { sizeOf(it, durationSec) }
        } else {
            candidates.sortedWith(
                compareBy<StreamFormat> { codecRank(it.vcodec) }.thenByDescending { sizeOf(it, durationSec) }
            ).first()
        }
    }

    internal fun chooseAudio(formats: List<StreamFormat>, container: String, durationSec: Int): StreamFormat? {
        val audio = formats.filter { it.hasAudio && !it.hasVideo }
        if (audio.isEmpty()) return null
        val pool = if (container == "mp4") audio.filter { isM4a(it) }.ifEmpty { audio } else audio
        return pool.maxByOrNull { if (it.abr > 0) it.abr else it.tbr }
    }

    private fun bestAudio(formats: List<StreamFormat>): StreamFormat? =
        formats.filter { it.hasAudio && !it.hasVideo }.maxByOrNull { if (it.abr > 0) it.abr else it.tbr }

    private fun sizeOf(f: StreamFormat, durationSec: Int): Long {
        if (f.fileSize > 0) return f.fileSize
        if (f.tbr > 0 && durationSec > 0) return (f.tbr * 1000 / 8 * durationSec).toLong()
        return 0
    }
}
