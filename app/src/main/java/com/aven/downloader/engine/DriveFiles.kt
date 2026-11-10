package com.aven.downloader.engine

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Google Drive helpers that work without yt-dlp: original files (zip, pdf, apk, video ...), Google Docs /
 * Sheets / Slides exports, and a best-effort folder listing. Network code here is untested against the
 * live site; Google can change these pages at any time.
 */
object DriveFiles {
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"

    data class Probe(val url: String, val name: String, val size: Long, val mime: String?, val ranges: Boolean)

    // ---------- pure helpers (unit-tested) ----------

    private val idPath = Regex("""/(?:file|document|spreadsheets|presentation)/d/([A-Za-z0-9_-]+)""")
    private val idQuery = Regex("""(?:^|[?&])id=([A-Za-z0-9_-]+)""")

    fun fileId(url: String): String? =
        idPath.find(url)?.groupValues?.get(1) ?: idQuery.find(url)?.groupValues?.get(1)

    /** "document" | "spreadsheets" | "presentation" for Google Docs/Sheets/Slides links, otherwise null. */
    fun docKind(url: String): String? {
        if (!url.contains("docs.google.com")) return null
        return Regex("""/(document|spreadsheets|presentation)/d/""").find(url)?.groupValues?.get(1)
    }

    class Export(val label: String, val fmt: String)

    fun exportsFor(kind: String): List<Export> = when (kind) {
        "document" -> listOf(Export("PDF", "pdf"), Export("Word (DOCX)", "docx"), Export("Plain text (TXT)", "txt"))
        "spreadsheets" -> listOf(Export("Excel (XLSX)", "xlsx"), Export("PDF", "pdf"), Export("CSV (first sheet)", "csv"))
        "presentation" -> listOf(Export("PowerPoint (PPTX)", "pptx"), Export("PDF", "pdf"))
        else -> emptyList()
    }

    fun kindTitle(kind: String) = when (kind) {
        "document" -> "Google Doc"; "spreadsheets" -> "Google Sheet"; else -> "Google Slides"
    }

    fun exportUrl(kind: String, id: String, fmt: String) = "https://docs.google.com/$kind/d/$id/export?format=$fmt"

    fun originalUrl(id: String) = "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"

    private val videoExt = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp", "flv", "ts", "wmv")
    fun isVideoName(name: String, mime: String?): Boolean =
        mime?.startsWith("video/") == true || name.substringAfterLast('.', "").lowercase() in videoExt

    /** Reads the file name out of a Content-Disposition header. */
    fun filenameFrom(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        Regex("""filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)""").find(disposition)?.let {
            return try { URLDecoder.decode(it.groupValues[1].trim(), "UTF-8") } catch (e: Exception) { it.groupValues[1] }
        }
        return Regex("""filename\s*=\s*"([^"]*)"""").find(disposition)?.groupValues?.get(1)
            ?: Regex("""filename\s*=\s*([^;]+)""").find(disposition)?.groupValues?.get(1)?.trim()
    }

    /** Total size from "bytes 0-0/12345". */
    fun totalFromContentRange(h: String?): Long =
        h?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: 0L

    private fun unescape(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")

    /** Drive's "can't scan for viruses" page is a form; this builds the real download URL from it. */
    fun confirmUrl(html: String): String? {
        val form = Regex("""<form[^>]*id="download-form"[^>]*>""", RegexOption.IGNORE_CASE).find(html) ?: return null
        val action = Regex("""action="([^"]+)"""").find(form.value)?.groupValues?.get(1)?.let { unescape(it) } ?: return null
        val params = Regex("""<input[^>]*type="hidden"[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { m ->
            val n = Regex("""name="([^"]*)"""").find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
            val v = Regex("""value="([^"]*)"""").find(m.value)?.groupValues?.get(1) ?: ""
            "${java.net.URLEncoder.encode(unescape(n), "UTF-8")}=${java.net.URLEncoder.encode(unescape(v), "UTF-8")}"
        }.toList()
        if (params.isEmpty()) return null
        return action + (if ('?' in action) "&" else "?") + params.joinToString("&")
    }

    fun classify(html: String, code: Int): String {
        val l = html.lowercase()
        return when {
            "quota" in l || "too many users" in l ->
                "DL: Google Drive says this file was downloaded too many times (quota exceeded). Try again later, or make a copy of it in your own Drive."
            code == 404 -> "DL: Google Drive: file not found. Check the link."
            code == 401 || code == 403 || "servicelogin" in l || "sign in" in l || "accounts.google.com" in l ->
                "DL: This Google Drive file isn't shared with \"Anyone with the link\". Log in under Settings → Login cookies → Google Drive if you have access."
            else -> "DL: Google Drive did not return the file (HTTP $code)."
        }
    }

    /** Entries found on a Drive folder page: id, name, isFolder. */
    data class FolderEntry(val id: String, val name: String, val isFolder: Boolean)

    fun parseFolder(html: String): List<FolderEntry> {
        val out = LinkedHashMap<String, FolderEntry>()
        val entry = Regex("""id="entry-([A-Za-z0-9_-]+)"(.*?)(?=id="entry-|</body>|$)""", RegexOption.DOT_MATCHES_ALL)
        for (m in entry.findAll(html)) {
            val id = m.groupValues[1]
            val body = m.groupValues[2]
            val title = Regex("""flip-entry-title[^>]*>([^<]*)<""").find(body)?.groupValues?.get(1)?.let { unescape(it).trim() }
                ?: continue
            val isFolder = "/drive/folders/" in body && "/file/d/" !in body
            out[id] = FolderEntry(id, title, isFolder)
        }
        return out.values.toList()
    }

    // ---------- network ----------

    private fun open(url: String, cookie: String?, range: String?): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Encoding", "identity")
        if (range != null) c.setRequestProperty("Range", range)
        if (!cookie.isNullOrBlank()) c.setRequestProperty("Cookie", cookie)
        return c
    }

    private fun readBody(c: HttpURLConnection): String = try {
        val s = if (c.responseCode >= 400) c.errorStream else c.inputStream
        s?.use { ins ->
            val buf = ByteArray(300_000)
            var n = 0
            while (n < buf.size) { val r = ins.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            String(buf, 0, n, Charsets.UTF_8)
        }.orEmpty()
    } catch (e: Exception) { "" }

    /** Looks at the original file behind a Drive id: name, size and whether ranges (parts) are supported. */
    fun probe(id: String, cookie: String?): Probe {
        var url = originalUrl(id)
        for (attempt in 0..2) {
            val c = open(url, cookie, "bytes=0-0")
            try {
                val code = c.responseCode
                val ctype = c.contentType.orEmpty().lowercase()
                if (code in 200..299 && !ctype.startsWith("text/html")) {
                    val name = filenameFrom(c.getHeaderField("Content-Disposition")) ?: "drive-file-$id"
                    val ranges = code == 206
                    val size = if (ranges) totalFromContentRange(c.getHeaderField("Content-Range")) else c.contentLengthLong.coerceAtLeast(0L)
                    return Probe(url, name, size, ctype.substringBefore(';').ifEmpty { null }, ranges && size > 0)
                }
                val body = readBody(c)
                val next = if (attempt < 2) confirmUrl(body) else null
                if (next != null) { url = next; continue }
                throw IOException(classify(body, code))
            } finally { c.disconnect() }
        }
        throw IOException("DL: Google Drive did not return the file.")
    }

    /** Reads only the headers of a Docs/Sheets/Slides export to learn the file name. */
    fun probeExport(kind: String, id: String, fmt: String, cookie: String?): String {
        val c = open(exportUrl(kind, id, fmt), cookie, null)
        try {
            val code = c.responseCode
            val ctype = c.contentType.orEmpty().lowercase()
            if (code in 200..299 && !ctype.startsWith("text/html")) {
                return filenameFrom(c.getHeaderField("Content-Disposition")) ?: "${kindTitle(kind)}.$fmt"
            }
            throw IOException(classify(readBody(c), code))
        } finally { c.disconnect() }
    }

    /** Best-effort listing of a Drive folder from its public embedded view (used when yt-dlp can't read folders). */
    fun listFolder(folderId: String, cookie: String?): List<FolderEntry> {
        val c = open("https://drive.google.com/embeddedfolderview?id=$folderId#list", cookie, null)
        try {
            val code = c.responseCode
            val body = readBody(c)
            if (code !in 200..299) throw IOException(classify(body, code))
            return parseFolder(body)
        } finally { c.disconnect() }
    }
}
