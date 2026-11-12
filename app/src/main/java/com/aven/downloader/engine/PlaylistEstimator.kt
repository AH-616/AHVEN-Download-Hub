package com.aven.downloader.engine

/**
 * Rough size estimate for a whole playlist. A playlist listing has titles and durations but no
 * file sizes, so size is estimated as duration x typical bitrate for the chosen quality.
 * The numbers are typical averages for YouTube-style encodes. The UI always shows "about".
 */
object PlaylistEstimator {

    data class Choice(val label: String, val kind: DownloadKind, val heightCap: Int, val audioFormat: String, val kbps: Int)

    val choices: List<Choice> = listOf(
        Choice("Best available", DownloadKind.VIDEO, 0, "m4a", 2800),
        Choice("1080p", DownloadKind.VIDEO, 1080, "m4a", 2600),
        Choice("720p", DownloadKind.VIDEO, 720, "m4a", 1400),
        Choice("480p", DownloadKind.VIDEO, 480, "m4a", 800),
        Choice("360p", DownloadKind.VIDEO, 360, "m4a", 450),
        Choice("Audio M4A", DownloadKind.AUDIO, 0, "m4a", 130)
    )

    fun bytesFor(durationSec: Int, choice: Choice): Long =
        if (durationSec <= 0) 0L else durationSec.toLong() * choice.kbps * 1000L / 8L

    data class Total(val bytes: Long, val unknownCount: Int)

    fun total(entries: List<PlaylistEntry>, choice: Choice): Total {
        var bytes = 0L
        var unknown = 0
        for (e in entries) {
            val b = bytesFor(e.durationSec, choice)
            if (b == 0L) unknown++ else bytes += b
        }
        return Total(bytes, unknown)
    }

    fun toSpec(choice: Choice, container: String): DownloadSpec =
        DownloadSpec(
            kind = choice.kind,
            heightCap = choice.heightCap,
            container = container,
            audioFormat = choice.audioFormat,
            label = if (choice.kind == DownloadKind.VIDEO) "${choice.label} · ${container.uppercase()}" else choice.label
        )
}
