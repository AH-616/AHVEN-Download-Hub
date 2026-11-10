package com.aven.downloader.engine

import org.json.JSONObject

/**
 * Parses the JSON printed by `yt-dlp --dump-json`. Uses org.json (built into Android),
 * so we see fields the library's own mapper doesn't expose (protocol, etc.).
 */
object InfoParser {
    fun parse(sourceUrl: String, output: String): MediaInfo {
        val o = findJson(output)
            ?: throw IllegalArgumentException("No video information was returned.")

        val formats = mutableListOf<StreamFormat>()
        val arr = o.optJSONArray("formats")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                formats += parseFormat(f)
            }
        } else {
            // Single-format result (some Facebook posts): the top-level object is the format.
            formats += parseFormat(o)
        }

        return MediaInfo(
            sourceUrl = sourceUrl,
            title = o.optString("title").ifBlank { o.optString("fulltitle") }.ifBlank { "Untitled video" },
            uploader = o.optStringOrNull("uploader") ?: o.optStringOrNull("channel"),
            durationSec = o.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
            thumbnail = o.optStringOrNull("thumbnail"),
            extractor = o.optStringOrNull("extractor_key") ?: o.optStringOrNull("extractor"),
            isLive = o.optBoolean("is_live", false),
            formats = formats
        )
    }

    /** True when the JSON describes a playlist (it has an `entries` list). */
    fun isPlaylist(output: String): Boolean = findJson(output)?.has("entries") == true

    /** Parses `yt-dlp --flat-playlist --dump-single-json` output. Entries without a usable URL are dropped. */
    fun parsePlaylist(sourceUrl: String, output: String): PlaylistInfo {
        val o = findJson(output)
            ?: throw IllegalArgumentException("No playlist information was returned.")
        val arr = o.optJSONArray("entries")
        val entries = mutableListOf<PlaylistEntry>()
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val e = arr.optJSONObject(i) ?: continue
                val id = e.optStringOrNull("id")
                var url = e.optStringOrNull("url") ?: e.optStringOrNull("webpage_url")
                if (url != null && !url.startsWith("http") && id != null) url = null
                if (url == null && id != null && (e.optStringOrNull("ie_key") ?: "").startsWith("Youtube")) {
                    url = "https://www.youtube.com/watch?v=$id"
                }
                if (url == null) continue
                var thumb = e.optStringOrNull("thumbnail")
                if (thumb == null) {
                    val th = e.optJSONArray("thumbnails")
                    if (th != null && th.length() > 0) thumb = th.optJSONObject(th.length() - 1)?.optStringOrNull("url")
                }
                entries += PlaylistEntry(
                    url = url,
                    title = e.optStringOrNull("title") ?: "Video ${entries.size + 1}",
                    durationSec = e.optDouble("duration", 0.0).let { if (it.isNaN()) 0 else it.toInt() },
                    thumbnail = thumb,
                    uploader = e.optStringOrNull("uploader") ?: e.optStringOrNull("channel")
                )
            }
        }
        return PlaylistInfo(
            sourceUrl = sourceUrl,
            title = o.optStringOrNull("title") ?: "Playlist",
            uploader = o.optStringOrNull("uploader") ?: o.optStringOrNull("channel"),
            entries = entries
        )
    }

    /** yt-dlp prints the JSON on one line; stray lines may surround it. Falls back to the first '{' .. last '}' span. */
    private fun findJson(output: String): JSONObject? {
        output.lineSequence().map { it.trim() }
            .lastOrNull { it.startsWith("{") && it.endsWith("}") }
            ?.let { line ->
                try {
                    val j = JSONObject(line)
                    if (j.has("id")) return j
                } catch (ignored: Exception) { }
            }
        val start = output.indexOf('{')
        val end = output.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try { JSONObject(output.substring(start, end + 1)) } catch (ignored: Exception) { null }
    }

    private fun parseFormat(f: JSONObject): StreamFormat {
        val headers = mutableMapOf<String, String>()
        f.optJSONObject("http_headers")?.let { h ->
            h.keys().forEach { k -> headers[k] = h.optString(k) }
        }
        val size = f.optLong("filesize", 0L).takeIf { it > 0 } ?: f.optLong("filesize_approx", 0L)
        return StreamFormat(
            id = f.optString("format_id", "?"),
            ext = f.optString("ext", ""),
            height = f.optInt("height", 0),
            width = f.optInt("width", 0),
            vcodec = f.optStringOrNull("vcodec"),
            acodec = f.optStringOrNull("acodec"),
            tbr = f.optDouble("tbr", 0.0).let { if (it.isNaN()) 0.0 else it },
            abr = f.optDouble("abr", 0.0).let { if (it.isNaN()) 0.0 else it },
            fileSize = size,
            url = f.optStringOrNull("url"),
            protocol = f.optStringOrNull("protocol"),
            headers = headers,
            language = f.optStringOrNull("language"),
            note = f.optStringOrNull("format_note")
        )
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key) || !has(key)) null else optString(key).takeIf { it.isNotBlank() && it != "null" }
}
