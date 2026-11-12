package com.aven.downloader.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.MutableContextWrapper
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.aven.downloader.AvenApp
import com.aven.downloader.data.StorageSaver
import com.aven.downloader.engine.DirectFiles
import com.aven.downloader.engine.DirectSource
import com.aven.downloader.net.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.ByteArrayInputStream

/** One thing the browser saw on a page that can be downloaded. */
data class Found(
    val url: String,
    val name: String,
    /** video | audio | hls | image | subtitle | document | compressed | program | torrent | other */
    val type: String,
    val referer: String
) {
    val isMedia get() = type == "video" || type == "audio" || type == "hls"
}

/**
 * The single in-app browser. The WebView lives here (not in a composable) so that page, scroll position and
 * the list of sniffed files survive switching tabs. All state is compose state, read by BrowserScreen.
 */
object BrowserHolder {
    private val main = Handler(Looper.getMainLooper())
    private var web: WebView? = null
    private var wrapper: MutableContextWrapper? = null
    private var started = false

    @Volatile private var blockAds = true

    var url by mutableStateOf("")
        private set
    var title by mutableStateOf("")
        private set
    var progress by mutableIntStateOf(100)
        private set
    var canBack by mutableStateOf(false)
        private set
    var canForward by mutableStateOf(false)
        private set
    var desktop by mutableStateOf(false)
        private set
    var blockedCount by mutableIntStateOf(0)
        private set

    /** Media on the current page: the "download" button of the browser. */
    val found = mutableStateListOf<Found>()
    /** Everything the page loaded (any file type): used by the Grabber. */
    val resources = mutableStateListOf<Found>()
    private val seen = HashSet<String>()

    /** Set by other screens ("Refresh link in browser", Home shortcuts); consumed by BrowserScreen. */
    var pendingLoad by mutableStateOf<String?>(null)
    /** Set when the page itself started a file download (attachment / unknown mime). */
    var pendingDownload by mutableStateOf<Found?>(null)

    var customView by mutableStateOf<View?>(null)
        private set
    private var customCb: WebChromeClient.CustomViewCallback? = null

    /** Set by "Refresh link in browser": the next file the user picks replaces the link of this failed download. */
    var refreshTarget by mutableStateOf<Long?>(null)

    fun requestLoad(u: String) { pendingLoad = u }

    private fun appContext() = AvenApp.instance

    private fun startSettingsWatch() {
        if (started) return
        started = true
        val app = AvenApp.instance
        app.appScope.launch { app.settings.flow.collect { blockAds = it.blockAds } }
    }

    /** Gives the WebView to the screen: its context is the activity while visible. */
    @SuppressLint("SetJavaScriptEnabled")
    fun attach(activity: Context): WebView {
        startSettingsWatch()
        wrapper?.baseContext = activity
        web?.let { return it }
        val w = MutableContextWrapper(activity).also { wrapper = it }
        val v = WebView(w)
        web = v
        with(v.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            builtInZoomControls = true
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            userAgentString = Net.MOBILE_UA
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true)
        v.addJavascriptInterface(Bridge(), "AHD")
        v.setDownloadListener { u, ua, cd, mime, _ ->
            val name = Net.fileNameFrom(cd, u)
            main.post { pendingDownload = Found(u, name, typeOf(name, mime), this.url) }
        }
        v.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val u = request.url.toString()
                if (blockAds && AdBlock.isBlocked(request.url.host.orEmpty())) {
                    main.post { blockedCount++ }
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                sniff(u, null)
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme.orEmpty()
                return s != "http" && s != "https" && s != "file" && s != "about"
            }

            override fun onPageStarted(view: WebView, u: String, favicon: Bitmap?) {
                url = u
                if (u != lastPage) { lastPage = u; found.clear(); resources.clear(); seen.clear(); blockedCount = 0 }
                canBack = view.canGoBack(); canForward = view.canGoForward()
            }

            override fun onPageFinished(view: WebView, u: String) {
                url = u
                canBack = view.canGoBack(); canForward = view.canGoForward()
                if (blockAds) view.evaluateJavascript(
                    "(function(){var s=document.createElement('style');s.innerHTML=\"${AdBlock.HIDE_CSS}\";document.head&&document.head.appendChild(s);})()", null
                )
                view.evaluateJavascript(SCAN_JS, null)
            }

            override fun doUpdateVisitedHistory(view: WebView, u: String, isReload: Boolean) {
                url = u
                canBack = view.canGoBack(); canForward = view.canGoForward()
            }
        }
        v.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, p: Int) { progress = p }
            override fun onReceivedTitle(view: WebView, t: String?) { title = t.orEmpty() }
            override fun onShowCustomView(view: View, cb: CustomViewCallback) {
                val a = (wrapper?.baseContext as? Activity)
                if (a == null) { cb.onCustomViewHidden(); return }
                (a.window.decorView as FrameLayout).addView(
                    view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                customView = view; customCb = cb
            }
            override fun onHideCustomView() = hideCustom()
        }
        return v
    }

    private var lastPage = ""

    fun detach() { wrapper?.baseContext = appContext() }

    fun hideCustom() {
        val v = customView ?: return
        (v.parent as? ViewGroup)?.removeView(v)
        customView = null
        customCb?.onCustomViewHidden(); customCb = null
    }

    // ---------- navigation ----------
    fun open(input: String) {
        val t = input.trim()
        if (t.isEmpty()) return
        val target = when {
            t.startsWith("http://", true) || t.startsWith("https://", true) -> t
            ' ' !in t && '.' in t -> "https://$t"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(t, "UTF-8")
        }
        web?.loadUrl(target)
    }
    fun back() { web?.goBack() }
    fun forward() { web?.goForward() }
    fun reload() { web?.reload() }
    fun stop() { web?.stopLoading() }
    fun goHome() { web?.loadUrl("about:blank"); url = ""; title = ""; found.clear(); resources.clear(); seen.clear(); lastPage = "" }

    fun setDesktop(on: Boolean) {
        desktop = on
        web?.settings?.apply {
            userAgentString = if (on) Net.DESKTOP_UA else Net.MOBILE_UA
            useWideViewPort = true
            loadWithOverviewMode = on
        }
        web?.reload()
    }

    fun clearBrowsingData() {
        web?.clearCache(true); web?.clearHistory(); web?.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
    }

    fun clearCache() { web?.clearCache(true) }

    fun userAgent(): String = web?.settings?.userAgentString ?: Net.MOBILE_UA
    fun cookieFor(u: String): String = try { CookieManager.getInstance().getCookie(u).orEmpty() } catch (e: Exception) { "" }

    // ---------- sniffing ----------
    private val skipExt = setOf("ts", "m4s", "aac_", "key")

    fun typeOf(name: String, mime: String?): String {
        val ext = DirectFiles.extOf(name.ifBlank { "x" }).ifBlank { name.substringAfterLast('.', "").lowercase() }
        if (ext == "m3u8" || ext == "mpd") return "hls"
        val m = mime.orEmpty().lowercase()
        if (m.contains("mpegurl") || m.contains("dash+xml")) return "hls"
        return when (StorageSaver.categoryFor("x.$ext")) {
            StorageSaver.Category.VIDEOS -> "video"
            StorageSaver.Category.MUSIC -> "audio"
            StorageSaver.Category.PHOTOS -> "image"
            StorageSaver.Category.SUBTITLES -> "subtitle"
            StorageSaver.Category.DOCUMENTS -> "document"
            StorageSaver.Category.COMPRESSED -> "compressed"
            StorageSaver.Category.PROGRAMS -> "program"
            StorageSaver.Category.TORRENTS -> "torrent"
            else -> when {
                m.startsWith("video/") -> "video"
                m.startsWith("audio/") -> "audio"
                m.startsWith("image/") -> "image"
                else -> "other"
            }
        }
    }

    /** Called for every request the page makes (any thread). */
    fun sniff(u: String, mime: String?) {
        if (!u.startsWith("http")) return
        val ext = DirectFiles.extOf(u)
        val isHls = ext == "m3u8" || ext == "mpd"
        if (ext.isEmpty() && !isHls && mime == null) return
        if (ext in skipExt) return
        if (ext !in DirectFiles.extensions && !isHls && ext != "ts") return
        if (ext == "ts") return
        main.post { addFound(u, mime) }
    }

    private fun addFound(u: String, mime: String?) {
        if (!seen.add(u)) return
        val name = Net.fileNameFrom(null, u)
        val type = typeOf(name, mime)
        val f = Found(u, name, type, url)
        resources.add(f)
        if (f.isMedia) found.add(f)
    }

    /** Collects links/images/media from the live DOM for the Grabber. */
    fun collectDom(cb: (List<String>) -> Unit) {
        val v = web ?: return cb(emptyList())
        v.evaluateJavascript(COLLECT_JS) { raw ->
            val out = ArrayList<String>()
            try {
                val s = JSONArray("[" + (raw ?: "null") + "]").optString(0)
                val arr = JSONArray(s)
                for (i in 0 until arr.length()) out.add(arr.getString(i))
            } catch (ignored: Exception) { }
            cb(out)
        }
    }

    private class Bridge {
        @JavascriptInterface
        fun media(json: String) {
            try {
                val a = JSONArray(json)
                for (i in 0 until a.length()) {
                    val u = a.getString(i)
                    if (u.startsWith("http")) main.post { forceFound(u) }
                }
            } catch (ignored: Exception) { }
        }
    }

    /** A `<video>` source the page itself reports: media even when the URL has no known extension. */
    private fun forceFound(u: String) {
        if (!seen.add(u)) return
        val name = Net.fileNameFrom(null, u)
        var type = typeOf(name, null)
        if (type == "other" || type == "image") type = "video"
        val f = Found(u, name, type, url)
        resources.add(f); found.add(f)
    }

    private const val SCAN_JS = "(function(){if(window.__ahdScan)return;window.__ahdScan=1;" +
        "function scan(){var o=[];document.querySelectorAll('video,audio,source').forEach(function(e){var s=e.currentSrc||e.src;" +
        "if(s&&s.indexOf('http')===0)o.push(s);});if(o.length)AHD.media(JSON.stringify(o));}" +
        "setInterval(scan,2000);scan();})();"

    private const val COLLECT_JS = "(function(){var o={};function add(u){if(u&&u.indexOf('http')===0)o[u]=1;}" +
        "document.querySelectorAll('a[href],link[href]').forEach(function(e){add(e.href);});" +
        "document.querySelectorAll('img,video,audio,source,track,embed,iframe').forEach(function(e){add(e.currentSrc);add(e.src);" +
        "var ss=e.getAttribute('srcset');if(ss)ss.split(',').forEach(function(p){try{add(new URL(p.trim().split(' ')[0],location.href).href);}catch(x){}});});" +
        "document.querySelectorAll('[data-src],[data-url],[data-href]').forEach(function(e){['data-src','data-url','data-href'].forEach(function(k){" +
        "var v=e.getAttribute(k);if(v)try{add(new URL(v,location.href).href);}catch(x){}});});" +
        "return JSON.stringify(Object.keys(o));})()"

    // ---------- downloading ----------
    fun sourceFor(f: Found, name: String, parts: Int = 0, pageUrl: String = url): DirectSource =
        DirectSource(f.url, name, f.referer.ifBlank { pageUrl }, userAgent(), cookieFor(f.url), parts)

    suspend fun enqueue(f: Found, name: String, parts: Int, size: Long): Long =
        AvenApp.instance.downloads.enqueueDirect(sourceFor(f, name, parts), f.referer.ifBlank { url }, f.type == "hls", size)

    suspend fun probeSize(f: Found): Long = withContext(Dispatchers.IO) {
        try { Net.probe(f.url, sourceFor(f, f.name).headers()).size.coerceAtLeast(0) } catch (e: Exception) { -1L }
    }
}
