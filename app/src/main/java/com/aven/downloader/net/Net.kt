package com.aven.downloader.net

import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.dnsoverhttps.DnsOverHttps
import java.io.IOException
import java.net.InetAddress
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** What a quick look at a file URL found out. */
class Probed(
    val finalUrl: String,
    val size: Long,           // -1 = unknown
    val ranges: Boolean,      // server accepts HTTP Range (multi-connection download possible)
    val mime: String,         // "" = unknown
    val fileName: String
)

/**
 * The one place that decides how the app talks to the network. Everything the app downloads itself
 * (files, YouTube lookups, quick probes) uses [client] / [downloadClient]; they follow the
 * "DNS over HTTPS" setting. (yt-dlp's Python and the in-app web pages use the system DNS.)
 */
object Net {
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val plain: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile private var dns: Dns = Dns.SYSTEM

    /** For API calls and quick probes. */
    @Volatile var client: OkHttpClient = plain
        private set

    /**
     * For file data. HTTP/1.1 only: every ranged part gets its own TCP connection instead of sharing one
     * HTTP/2 connection, which is what makes parallel parts faster on CDNs that cap each connection.
     */
    @Volatile var downloadClient: OkHttpClient = downloadFrom(plain)
        private set

    private fun downloadFrom(base: OkHttpClient) = base.newBuilder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .build()

    /** Called whenever the DoH setting changes. [mode]: off | cloudflare | google | adguard | custom. */
    @Synchronized
    fun configure(mode: String, customUrl: String) {
        val newDns = try { dohFor(mode, customUrl) } catch (t: Throwable) { null } ?: Dns.SYSTEM
        dns = newDns
        val c = if (newDns === Dns.SYSTEM) plain else plain.newBuilder().dns(newDns).build()
        client = c
        downloadClient = downloadFrom(c)
    }

    private fun dohFor(mode: String, customUrl: String): Dns? {
        val cfg: Pair<String?, List<String>> = when (mode) {
            "cloudflare" -> "https://cloudflare-dns.com/dns-query" to listOf("1.1.1.1", "1.0.0.1")
            "google" -> "https://dns.google/dns-query" to listOf("8.8.8.8", "8.8.4.4")
            "adguard" -> "https://dns.adguard-dns.com/dns-query" to listOf("94.140.14.14", "94.140.15.15")
            "custom" -> customUrl.trim().takeIf { it.startsWith("https://") } to emptyList()
            else -> return null
        }
        val url = cfg.first?.toHttpUrlOrNull() ?: return null
        val doh = DnsOverHttps.Builder()
            .client(plain)                       // the bootstrap client must not use DoH itself
            .url(url)
            .apply { if (cfg.second.isNotEmpty()) bootstrapDnsHosts(cfg.second.map { InetAddress.getByName(it) }) }
            .includeIPv6(false)
            .build()
        // If the DoH server can't be reached, fall back to the phone's own DNS instead of failing every download.
        return object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                try { doh.lookup(hostname) } catch (e: Exception) { Dns.SYSTEM.lookup(hostname) }
        }
    }

    /** Builds a request with the given headers; headers the server can't accept (bad characters) are skipped. */
    fun request(url: String, headers: Map<String, String>, range: String? = null): Request {
        val b = Request.Builder().url(url)
        headers.forEach { (k, v) -> try { b.header(k, v) } catch (e: IllegalArgumentException) { } }
        if (range != null) b.header("Range", range)
        return b.build()
    }

    fun newCall(c: OkHttpClient, req: Request): Call = c.newCall(req)

    /**
     * Looks at a file URL without downloading it: size, range support, type and file name.
     * Asks for the first byte only, so it works with servers that refuse HEAD.
     */
    fun probe(url: String, headers: Map<String, String>): Probed {
        val h = HashMap(headers)
        if (h.keys.none { it.equals("User-Agent", true) }) h["User-Agent"] = DESKTOP_UA
        h["Accept-Encoding"] = "identity"
        val call = client.newCall(request(url, h, "bytes=0-0"))
        call.execute().use { r ->
            val code = r.code
            if (code !in 200..299) throw IOException("HTTP $code")
            val mime = r.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
            var size = -1L
            var ranges = false
            if (code == 206) {
                ranges = true
                size = r.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull() ?: -1L
            } else {
                size = r.header("Content-Length")?.toLongOrNull() ?: -1L
                ranges = r.header("Accept-Ranges")?.contains("bytes", true) == true && size > 0
            }
            val finalUrl = r.request.url.toString()
            val name = fileNameFrom(r.header("Content-Disposition"), finalUrl)
            return Probed(finalUrl, size, ranges, mime, name)
        }
    }

    fun fileNameFrom(contentDisposition: String?, url: String): String {
        if (contentDisposition != null) {
            Regex("""filename\*\s*=\s*[^']*'[^']*'([^;]+)""", RegexOption.IGNORE_CASE).find(contentDisposition)?.let {
                runCatching { URLDecoder.decode(it.groupValues[1].trim().trim('"'), "UTF-8") }.getOrNull()?.takeIf { n -> n.isNotBlank() }?.let { n -> return clean(n) }
            }
            Regex("""filename\s*=\s*"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(contentDisposition)?.let {
                val n = it.groupValues[1].trim()
                if (n.isNotBlank()) return clean(n)
            }
        }
        val path = url.substringAfter("://").substringAfter('/', "").substringBefore('?').substringBefore('#')
        val last = path.substringAfterLast('/')
        val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
        if (decoded.isNotBlank()) return clean(decoded)
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':')
        return clean(host.ifBlank { "download" })
    }

    private fun clean(n: String) = n.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().ifBlank { "download" }.take(150)
}
