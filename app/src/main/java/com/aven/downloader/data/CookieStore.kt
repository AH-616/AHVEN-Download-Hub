package com.aven.downloader.data

import android.content.Context
import android.net.Uri
import java.io.File

/** A site the user can log in to (inside the app) so yt-dlp can use their own session cookies. */
data class CookieSite(
    val id: String,
    val label: String,
    val loginUrl: String,
    /** URLs whose cookies are read from the WebView after login. */
    val cookieUrls: List<String>,
    /** At least one of these cookie names must exist, otherwise the login is not considered done. */
    val keyCookies: List<String>
)

object CookieSites {
    val all = listOf(
        CookieSite(
            "youtube", "YouTube",
            "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fm.youtube.com%2F",
            listOf("https://www.youtube.com", "https://m.youtube.com", "https://accounts.google.com", "https://www.google.com"),
            listOf("SAPISID", "__Secure-3PSID", "SID")
        ),
        CookieSite(
            "drive", "Google Drive",
            "https://accounts.google.com/ServiceLogin?service=wise&continue=https%3A%2F%2Fdrive.google.com%2F",
            listOf(
                "https://drive.google.com", "https://docs.google.com", "https://drive.usercontent.google.com",
                "https://accounts.google.com", "https://www.google.com"
            ),
            listOf("SAPISID", "__Secure-3PSID", "SID")
        ),
        CookieSite(
            "facebook", "Facebook", "https://m.facebook.com/login",
            listOf("https://www.facebook.com", "https://m.facebook.com"),
            listOf("c_user", "xs")
        ),
        CookieSite(
            "instagram", "Instagram", "https://www.instagram.com/accounts/login/",
            listOf("https://www.instagram.com"),
            listOf("sessionid")
        ),
        CookieSite(
            "tiktok", "TikTok", "https://www.tiktok.com/login",
            listOf("https://www.tiktok.com"),
            listOf("sessionid", "sessionid_ss")
        )
    )

    fun byId(id: String?): CookieSite? = all.firstOrNull { it.id == id }
}

/**
 * Keeps the user's own login cookies (optional) and writes them in the Netscape cookies.txt
 * format that yt-dlp understands. Needed for age-restricted / private-to-you content and when
 * YouTube asks "Sign in to confirm you're not a bot".
 */
class CookieStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("aven_cookies", Context.MODE_PRIVATE)
    private val file get() = File(context.filesDir, "cookies.txt")

    /** @return false when the key login cookie is missing (nothing is saved then). */
    fun saveSite(site: CookieSite, headersByUrl: Map<String, String?>): Boolean {
        val names = headersByUrl.values.filterNotNull()
            .flatMap { it.split(";") }
            .map { it.trim().substringBefore('=') }
        if (site.keyCookies.none { it in names }) return false
        val expiry = System.currentTimeMillis() / 1000 + 365L * 24 * 3600
        val lines = StringBuilder()
        for ((url, header) in headersByUrl) {
            if (header.isNullOrBlank()) continue
            val host = Uri.parse(url).host ?: continue
            val domain = "." + host.removePrefix("www.")
            for (part in header.split(";")) {
                val kv = part.trim()
                val i = kv.indexOf('=')
                if (i <= 0) continue
                lines.append(domain).append('\t').append("TRUE").append('\t').append('/').append('\t')
                    .append("TRUE").append('\t').append(expiry).append('\t')
                    .append(kv.substring(0, i)).append('\t').append(kv.substring(i + 1)).append('\n')
            }
        }
        prefs.edit().putString("site_${site.id}", lines.toString()).apply()
        rebuild()
        return true
    }

    /** Accepts a cookies.txt exported from a browser. */
    fun importText(text: String): Boolean {
        val lines = text.lines().filter { l ->
            val t = l.trim()
            t.isNotEmpty() && (!t.startsWith("#") || t.startsWith("#HttpOnly_")) && t.split('\t').size >= 7
        }
        if (lines.isEmpty()) return false
        prefs.edit().putString("imported", lines.joinToString("\n") + "\n").apply()
        rebuild()
        return true
    }

    fun clearAll() {
        prefs.edit().clear().apply()
        file.delete()
    }

    fun hasSite(id: String): Boolean = !prefs.getString("site_$id", null).isNullOrBlank()
    fun hasImported(): Boolean = !prefs.getString("imported", null).isNullOrBlank()
    fun hasAny(): Boolean = file.exists() && file.length() > 0

    private fun rebuild() {
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        CookieSites.all.forEach { s -> prefs.getString("site_${s.id}", null)?.let { sb.append(it) } }
        prefs.getString("imported", null)?.let { sb.append(it) }
        file.writeText(sb.toString())
    }

    /** "a=b; c=d" Cookie header for [host] from the saved cookies (used by our own downloader). Google hosts only. */
    fun headerFor(host: String): String? {
        if (!host.endsWith("google.com")) return null
        if (!hasAny()) return null
        return try {
            val pairs = file.readLines().mapNotNull { raw ->
                val l = raw.removePrefix("#HttpOnly_")
                if (l.startsWith("#")) return@mapNotNull null
                val f = l.split('\t')
                if (f.size < 7) return@mapNotNull null
                val d = f[0].trimStart('.')
                if (host == d || host.endsWith(".$d")) "${f[5]}=${f[6]}" else null
            }
            if (pairs.isEmpty()) null else pairs.joinToString("; ")
        } catch (e: Exception) { null }
    }

    /**
     * yt-dlp rewrites the cookie file it is given, so every run gets its own copy
     * (parallel downloads must not write to the same file). The caller deletes the copy.
     */
    fun copyForRun(): File? {
        if (!hasAny()) return null
        return try {
            val copy = File(context.cacheDir, "cookies-${System.nanoTime()}.txt")
            file.copyTo(copy, overwrite = true)
            copy
        } catch (ignored: Exception) { null }
    }
}
