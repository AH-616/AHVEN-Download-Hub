package com.aven.downloader.ui.browser

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aven.downloader.AvenApp
import com.aven.downloader.engine.DirectFiles
import com.aven.downloader.net.Net
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.URI

/** The file types of the "Select file types to grab" window. */
enum class GrabType(val label: String, val key: String) {
    VIDEO("Video", "video"), SUBTITLE("Subtitle", "subtitle"), AUDIO("Audio", "audio"), IMAGE("Image", "image"),
    DOCUMENT("Document", "document"), COMPRESSED("Compressed", "compressed"), PROGRAM("Program", "program"),
    TORRENT("Torrent", "torrent"), ALL("All files", "*"), CUSTOM("Custom", "custom")
}

class GrabberViewModel(application: Application) : AndroidViewModel(application) {
    val items = mutableStateListOf<Found>()
    val sizes = mutableStateMapOf<String, Long>()
    val selected = mutableStateListOf<String>()           // urls

    var types by mutableStateOf(setOf(GrabType.VIDEO, GrabType.AUDIO))
    var customExt by mutableStateOf("")
    var fullNames by mutableStateOf(false)
    var level by mutableStateOf(0)                         // 0 = this page only, 1-2 = also linked pages of the same site
    var grabbing by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set

    private var job: Job? = null
    private val probeGate = Semaphore(4)

    fun visible(): List<Found> = items.filter { matches(it) }

    private fun matches(f: Found): Boolean {
        if (GrabType.ALL in types) return true
        val t = if (f.type == "hls") "video" else f.type
        if (types.any { it.key == t }) return true
        if (GrabType.CUSTOM in types) {
            val exts = customExt.split(',', ' ', ';').map { it.trim().trimStart('.').lowercase() }.filter { it.isNotEmpty() }
            if (DirectFiles.extOf(f.url) in exts) return true
        }
        return false
    }

    fun toggleSelect(url: String) { if (url in selected) selected.remove(url) else selected.add(url) }
    fun selectAll(on: Boolean) {
        selected.clear()
        if (on) selected.addAll(visible().map { it.url })
        probeVisible()
    }

    fun totalSelected(): Pair<Int, Long> {
        val sel = visible().filter { it.url in selected }
        return sel.size to sel.sumOf { (sizes[it.url] ?: 0L).coerceAtLeast(0) }
    }

    fun setTypes(t: Set<GrabType>, custom: String) {
        types = t; customExt = custom
        selected.retainAll(visible().map { it.url }.toSet())
        probeVisible()
    }

    // ---------- grabbing ----------
    fun stop() { job?.cancel(); grabbing = false; status = "Stopped" }

    fun grab() {
        if (grabbing) return
        grabbing = true
        status = "Scanning page…"
        job = viewModelScope.launch {
            try {
                val page = BrowserHolder.url
                val domLinks = suspendCancellableCoroutine<List<String>> { c -> BrowserHolder.collectDom { c.resumeWith(Result.success(it)) } }
                val seen = HashSet<String>(items.map { it.url })
                fun add(u: String, referer: String) {
                    if (u in seen || !admit(u)) return
                    seen.add(u)
                    val name = Net.fileNameFrom(null, u)
                    items.add(Found(u, name, BrowserHolder.typeOf(name, null), referer))
                }
                BrowserHolder.resources.toList().forEach { add(it.url, it.referer) }
                domLinks.forEach { add(it, page) }

                if (level > 0 && page.startsWith("http")) {
                    val host = hostOf(page)
                    var frontier = domLinks.filter { isPage(it) && hostOf(it) == host && it != page }.distinct()
                    val visited = HashSet<String>().apply { add(page) }
                    var budget = if (level == 1) 12 else 30
                    repeat(level) {
                        val batch = frontier.filter { visited.add(it) }.take(budget)
                        budget -= batch.size
                        status = "Scanning ${batch.size} linked pages…"
                        val next = ArrayList<String>()
                        coroutineScope {
                            batch.chunked(4).forEach { chunk ->
                                chunk.map { p -> async(Dispatchers.IO) { p to fetchLinks(p) } }.awaitAll().forEach { (p, links) ->
                                    links.forEach { add(it, p); if (isPage(it) && hostOf(it) == host) next.add(it) }
                                }
                            }
                        }
                        frontier = next.distinct()
                    }
                }
                status = "${items.size} files found"
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                status = "Scan failed: ${t.message ?: "unknown error"}"
            } finally {
                grabbing = false
                probeVisible()
            }
        }
    }

    private val pageExt = setOf("", "html", "htm", "php", "asp", "aspx", "jsp", "shtml", "xhtml")
    private val junkExt = setOf("js", "css", "json", "xml", "woff", "woff2", "ttf", "otf", "map", "ico", "svg", "php_", "html", "htm", "php", "asp", "aspx", "jsp", "ts", "m4s")

    private fun isPage(u: String) = DirectFiles.extOf(u) in pageExt
    private fun hostOf(u: String) = try { URI(u).host.orEmpty().removePrefix("www.") } catch (e: Exception) { "" }

    private fun admit(u: String): Boolean {
        if (!u.startsWith("http")) return false
        val ext = DirectFiles.extOf(u)
        if (ext == "m3u8" || ext == "mpd") return true
        if (ext.isEmpty() || ext in junkExt) return false
        return ext.length <= 6   // any other file-looking link (shown under "All files")
    }

    private fun fetchLinks(pageUrl: String): List<String> {
        return try {
            val req = Net.request(pageUrl, mapOf("User-Agent" to Net.MOBILE_UA, "Cookie" to BrowserHolder.cookieFor(pageUrl)))
            Net.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return emptyList()
                val ct = r.header("Content-Type").orEmpty()
                if (ct.isNotEmpty() && !ct.contains("html", true)) return emptyList()
                val body = r.body?.source()?.let { s -> s.request(1_500_000); s.buffer.clone().readUtf8() }.orEmpty()
                val out = LinkedHashSet<String>()
                val base = URI(pageUrl)
                Regex("""(?:href|src|data-src|data-url)\s*=\s*["']([^"'#<>]+)["']""", RegexOption.IGNORE_CASE).findAll(body).forEach {
                    val raw = it.groupValues[1].trim().replace("&amp;", "&")
                    if (raw.startsWith("javascript:") || raw.startsWith("mailto:") || raw.startsWith("data:")) return@forEach
                    try { base.resolve(raw).toString().takeIf { u -> u.startsWith("http") }?.let(out::add) } catch (ignored: Exception) { }
                }
                out.toList()
            }
        } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }

    // ---------- sizes ----------
    private val probing = HashSet<String>()

    fun probeVisible() {
        visible().take(80).forEach { f ->
            if (f.type == "hls" || sizes.containsKey(f.url) || !probing.add(f.url)) return@forEach
            viewModelScope.launch {
                probeGate.withPermit { sizes[f.url] = BrowserHolder.probeSize(f) }
            }
        }
    }

    // ---------- downloading ----------
    /** Queues the selected files. @return how many were queued. */
    suspend fun enqueueSelected(): Int {
        val sel = visible().filter { it.url in selected }
        sel.forEach { BrowserHolder.enqueue(it, it.name, 0, (sizes[it.url] ?: 0L).coerceAtLeast(0)) }
        selected.clear()
        return sel.size
    }
}
