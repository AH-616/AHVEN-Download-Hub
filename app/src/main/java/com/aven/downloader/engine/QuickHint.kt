package com.aven.downloader.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Title and picture of a video, shown while the (slower) full format list is still loading. */
data class QuickHint(val title: String, val thumbnail: String?, val author: String?)

object QuickHintFetcher {
    /** YouTube only (its public oEmbed answer takes about a second). Returns null on any problem. */
    suspend fun youtube(videoUrl: String): QuickHint? = withContext(Dispatchers.IO) {
        try {
            val api = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(videoUrl, "UTF-8")
            val c = URL(api).openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            try {
                if (c.responseCode != 200) return@withContext null
                val o = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                val title = o.optString("title", "").ifBlank { return@withContext null }
                QuickHint(
                    title,
                    o.optString("thumbnail_url", "").takeIf { it.isNotBlank() },
                    o.optString("author_name", "").takeIf { it.isNotBlank() }
                )
            } finally {
                c.disconnect()
            }
        } catch (ignored: Exception) {
            null
        }
    }
}
