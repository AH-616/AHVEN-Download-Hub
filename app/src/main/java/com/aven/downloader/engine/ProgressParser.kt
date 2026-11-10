package com.aven.downloader.engine

/** Parsed from a yt-dlp "[download] ..." output line. */
data class ProgressLine(val percent: Float, val totalBytes: Long, val speedBytesPerSec: Long)

object ProgressParser {
    private val re = Regex(
        """\[download]\s+([\d.]+)%\s+of\s+~?\s*([\d.]+)\s*([KMGT]?i?B)(?:\s+at\s+([\d.]+)\s*([KMGT]?i?B)/s)?"""
    )
    private val fragRe = Regex("""\(frag (\d+)/(\d+)\)""")
    private val countRe = Regex("""Downloading (\d+) format\(s\)""")

    fun parse(line: String): ProgressLine? {
        val m = re.find(line) ?: return null
        val percent = m.groupValues[1].toFloatOrNull() ?: return null
        val total = toBytes(m.groupValues[2], m.groupValues[3])
        val speed = if (m.groupValues[4].isNotEmpty()) toBytes(m.groupValues[4], m.groupValues[5]) else 0L
        return ProgressLine(percent, total, speed)
    }

    /** Number of streams yt-dlp will fetch ("Downloading 2 format(s): 137+140"), or null. */
    fun streamCount(line: String): Int? = countRe.find(line)?.groupValues?.get(1)?.toIntOrNull()

    /** "(frag 12/120)" in a progress line of a segmented download -> 12 to 120, or null. */
    fun fragment(line: String): Pair<Int, Int>? {
        val m = fragRe.find(line) ?: return null
        val a = m.groupValues[1].toIntOrNull() ?: return null
        val b = m.groupValues[2].toIntOrNull() ?: return null
        return if (b > 0) a.coerceAtMost(b) to b else null
    }

    fun isDestinationLine(line: String) = line.startsWith("[download] Destination:")

    /** Short status text for post-processing lines, or null when the line isn't one. */
    fun postProcessLabel(line: String): String? = when {
        line.startsWith("[Merger]") -> "Merging video and audio…"
        line.startsWith("[ExtractAudio]") -> "Converting audio…"
        line.startsWith("[VideoRemuxer]") || line.startsWith("[VideoConvertor]") -> "Processing video…"
        line.startsWith("[Fixup") -> "Fixing container…"
        else -> null
    }

    private fun toBytes(num: String, unit: String): Long {
        val v = num.toDoubleOrNull() ?: return 0
        val mult = when (unit.uppercase()) {
            "B" -> 1.0
            "KIB", "KB" -> 1024.0
            "MIB", "MB" -> 1024.0 * 1024
            "GIB", "GB" -> 1024.0 * 1024 * 1024
            "TIB", "TB" -> 1024.0 * 1024 * 1024 * 1024
            else -> 1.0
        }
        return (v * mult).toLong()
    }
}
