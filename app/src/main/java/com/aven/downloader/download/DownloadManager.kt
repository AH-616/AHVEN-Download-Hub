package com.aven.downloader.download

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.aven.downloader.data.DownloadDao
import com.aven.downloader.data.DownloadEntity
import com.aven.downloader.data.DownloadStatus
import com.aven.downloader.data.SettingsRepository
import com.aven.downloader.data.StorageSaver
import com.aven.downloader.engine.Engine
import com.aven.downloader.engine.EngineNotReadyException
import com.aven.downloader.engine.ErrorMapper
import com.aven.downloader.engine.FormatPlanner
import com.aven.downloader.engine.MediaInfo
import com.aven.downloader.engine.PlaylistEntry
import com.aven.downloader.engine.DownloadSpec
import com.aven.downloader.engine.DownloadKind
import com.aven.downloader.engine.DirectPlanner
import com.aven.downloader.engine.DirectSource
import com.aven.downloader.engine.Fetched
import com.aven.downloader.engine.StreamFormat
import com.aven.downloader.net.Net
import com.aven.downloader.engine.DriveFiles
import com.aven.downloader.engine.FileChoice
import com.aven.downloader.engine.FileInfo
import com.aven.downloader.engine.ProgressParser
import com.aven.downloader.AvenApp
import com.aven.downloader.util.UrlParser
import kotlinx.coroutines.CancellationException
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import com.aven.downloader.data.Settings
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Queue + runner. Records live in Room, so they survive app restarts.
 * Concurrency (1-5) and the network rule are read from Settings on every scheduling pass.
 */
class DownloadManager(
    private val app: Application,
    private val dao: DownloadDao,
    private val engine: Engine,
    private val settingsRepo: SettingsRepository,
    private val storage: StorageSaver,
    private val notifier: Notifier
) {
    /** Live piece-by-piece progress of a segmented (HLS/DASH) download. Not stored; only while it runs. */
    class FragProgress(
        val done: Int,
        val total: Int,
        val parallel: Int,
        /** Own downloader only: 0..1 fill of every block (one box each), and one row per connection. */
        val cells: FloatArray? = null,
        val conns: List<SegmentedDownloader.Conn> = emptyList(),
        val doneBytes: Long = 0,
        val totalBytes: Long = 0
    )

    data class Summary(val running: Int, val queued: Int, val progress: Int, val blocked: String?, val items: List<DownloadEntity> = emptyList())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fileJobs = ConcurrentHashMap<Long, Deferred<Unit>>()
    private val running = ConcurrentHashMap<Long, Job>()
    private val stopRequests = ConcurrentHashMap<Long, DownloadStatus>()
    private val mutex = Mutex()
    private val kicks = Channel<Unit>(Channel.CONFLATED)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val _blocked = MutableStateFlow<String?>(null)

    private val _frags = MutableStateFlow<Map<Long, FragProgress>>(emptyMap())
    val frags: StateFlow<Map<Long, FragProgress>> = _frags

    val summary: StateFlow<Summary> = combine(dao.observeAll(), _blocked) { list, blocked ->
        val r = list.filter { it.status == DownloadStatus.RUNNING }
        val q = list.count { it.status == DownloadStatus.QUEUED }
        val progress = if (r.isEmpty()) 0 else r.map { it.progress }.average().toInt()
        Summary(r.size, q, progress, if (q > 0) blocked else null, r)
    }.stateIn(scope, SharingStarted.Eagerly, Summary(0, 0, 0, null))

    init {
        scope.launch {
            dao.markRunningInterrupted()   // a download can't be "running" right after process start
            for (ignored in kicks) schedule()
        }
        scope.launch {
            settingsRepo.flow.map { it.maxConcurrent to it.wifiOnly }.distinctUntilChanged().collect { kick() }
        }
        try {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = kick()
                override fun onLost(network: Network) = kick()
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = kick()
            })
        } catch (ignored: Exception) { }
    }

    private fun kick() { kicks.trySend(Unit) }

    // ---------------- public actions ----------------

    suspend fun enqueue(info: MediaInfo, spec: DownloadSpec): Long {
        val id = dao.insert(
            DownloadEntity(
                url = info.sourceUrl,
                title = info.title,
                uploader = info.uploader,
                thumbnail = info.thumbnail,
                durationSec = info.durationSec,
                kind = spec.kind,
                heightCap = spec.heightCap,
                container = spec.container,
                audioFormat = spec.audioFormat,
                formatLabel = spec.label,
                audioLang = spec.audioLang,
                pinned = spec.pinned
            )
        )
        DownloadService.ensureRunning(app)
        kick()
        return id
    }

    /** A plain file (Drive zip / document / original video). */
    suspend fun enqueueFile(info: FileInfo, choice: FileChoice): Long {
        val id = dao.insert(
            DownloadEntity(
                url = info.sourceUrl,
                title = info.title,
                kind = DownloadKind.FILE,
                formatLabel = choice.label,
                fileSource = choice.source,
                totalBytes = choice.estimatedSize
            )
        )
        DownloadService.ensureRunning(app)
        kick()
        return id
    }

    /**
     * A file or HLS stream found by the in-app browser (or typed in as a direct link).
     * [hls] true = a .m3u8 stream: yt-dlp downloads it; otherwise the own multi-connection downloader does.
     */
    suspend fun enqueueDirect(src: DirectSource, pageUrl: String, hls: Boolean, sizeHint: Long = 0L): Long {
        val label = if (hls) "Stream (HLS)" else "File"
        val id = dao.insert(
            DownloadEntity(
                url = pageUrl.ifBlank { src.url },
                title = src.name.ifBlank { Net.fileNameFrom(null, src.url) },
                kind = DownloadKind.FILE,
                formatLabel = label,
                fileSource = (if (hls) "ytdlp:" else "direct:") + src.encode(),
                totalBytes = sizeHint
            )
        )
        DownloadService.ensureRunning(app)
        kick()
        return id
    }

    /** Adds many playlist videos at once, all with the same quality choice. @return how many were added. */
    suspend fun enqueueAll(entries: List<PlaylistEntry>, spec: DownloadSpec): Int {
        for (en in entries) {
            dao.insert(
                DownloadEntity(
                    url = en.url,
                    title = en.title,
                    uploader = en.uploader,
                    thumbnail = en.thumbnail,
                    durationSec = en.durationSec,
                    kind = spec.kind,
                    heightCap = spec.heightCap,
                    container = spec.container,
                    audioFormat = spec.audioFormat,
                    formatLabel = spec.label,
                audioLang = spec.audioLang
                )
            )
        }
        if (entries.isNotEmpty()) {
            DownloadService.ensureRunning(app)
            kick()
        }
        return entries.size
    }

    suspend fun pause(id: Long) {
        if (running.containsKey(id)) {
            stopRequests[id] = DownloadStatus.PAUSED
            stopProcess(id)
        } else {
            val e = dao.get(id) ?: return
            if (e.status == DownloadStatus.QUEUED) dao.setStatus(id, DownloadStatus.PAUSED, null, null, "Paused")
        }
    }

    suspend fun resume(id: Long) {
        val e = dao.get(id) ?: return
        if (e.status == DownloadStatus.PAUSED) {
            dao.requeue(id)
            DownloadService.ensureRunning(app)
            kick()
        }
    }

    suspend fun cancel(id: Long) {
        if (running.containsKey(id)) {
            stopRequests[id] = DownloadStatus.CANCELED
            stopProcess(id)
        } else {
            val e = dao.get(id) ?: return
            if (e.isActive) {
                tmpDir(id).deleteRecursively()
                dao.setStatus(id, DownloadStatus.CANCELED, null, null, null)
            }
        }
    }

    suspend fun retry(id: Long) {
        val e = dao.get(id) ?: return
        when (e.status) {
            DownloadStatus.FAILED -> dao.requeue(id)          // keeps partial data, yt-dlp continues
            DownloadStatus.CANCELED -> dao.restart(id, System.currentTimeMillis())
            else -> return
        }
        DownloadService.ensureRunning(app)
        kick()
    }

    /** IDM "Redownload": forget partial data and start the file again from zero. */
    suspend fun redownload(id: Long) {
        val e = dao.get(id) ?: return
        if (e.status != DownloadStatus.FAILED && e.status != DownloadStatus.CANCELED) return
        tmpDir(id).deleteRecursively()
        dao.restart(id, System.currentTimeMillis())
        DownloadService.ensureRunning(app)
        kick()
    }

    /** IDM "Refresh link": the browser found a fresh address for the same file; keep the partial data and continue. */
    suspend fun replaceLink(id: Long, src: DirectSource, hls: Boolean) {
        val e = dao.get(id) ?: return
        val old = e.fileSource
        // keep the name chosen earlier
        val named = if (old.startsWith("direct:") || old.startsWith("ytdlp:")) {
            val o = runCatching { DirectSource.decode(old.substringAfter(':')) }.getOrNull()
            if (o != null && o.name.isNotBlank()) src.copy(name = o.name) else src
        } else src
        dao.setSource(id, (if (hls) "ytdlp:" else "direct:") + named.encode())
        dao.requeue(id)
        DownloadService.ensureRunning(app)
        kick()
    }

    /** Removes the record; optionally the downloaded file as well. */
    suspend fun remove(id: Long, deleteFile: Boolean) {
        val e = dao.get(id) ?: return
        if (e.isActive) cancel(id)
        if (deleteFile) StorageSaver.deleteFile(app, e.outputUri)
        tmpDir(id).deleteRecursively()
        dao.delete(id)
    }

    suspend fun clearCompleted() = dao.clearCompleted()

    suspend fun clearFailed() {
        dao.observeAll().first()
            .filter { it.status == DownloadStatus.FAILED || it.status == DownloadStatus.CANCELED }
            .forEach { tmpDir(it.id).deleteRecursively() }
        dao.clearFailed()
    }

    val blockedReason: StateFlow<String?> get() = _blocked

    // ---------------- scheduling ----------------

    private suspend fun schedule() = mutex.withLock {
        val s = settingsRepo.flow.first()
        val reason = blockReason(s.wifiOnly)
        _blocked.value = reason
        if (reason != null) {
            // Wi-Fi only and Wi-Fi is gone: put running downloads back in the queue.
            // They continue automatically (from the partial file) when Wi-Fi returns.
            if (s.wifiOnly && reason.startsWith("Waiting for Wi-Fi")) {
                running.keys.toList().forEach { id ->
                    stopRequests[id] = DownloadStatus.QUEUED
                    stopProcess(id)
                }
            }
            return@withLock
        }
        var free = s.maxConcurrent - running.size
        for (q in dao.queued()) {
            if (free <= 0) break
            start(q)
            free--
        }
    }

    private suspend fun start(e: DownloadEntity) {
        DownloadService.ensureRunning(app)
        dao.setStatus(e.id, DownloadStatus.RUNNING, null, null, "Starting…")
        val job = scope.launch(Dispatchers.IO) { runDownload(e.id) }
        running[e.id] = job
        job.invokeOnCompletion {
            running.remove(e.id)
            kick()
        }
    }

    private fun blockReason(wifiOnly: Boolean): String? {
        val net = connectivity.activeNetwork ?: return if (wifiOnly) "Waiting for Wi-Fi" else "No internet connection"
        val caps = connectivity.getNetworkCapabilities(net)
            ?: return if (wifiOnly) "Waiting for Wi-Fi" else "No internet connection"
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return "No internet connection"
        if (wifiOnly && !(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        ) return "Waiting for Wi-Fi (Wi-Fi only is on)"
        return null
    }

    // ---------------- running one download ----------------

    private fun tmpDir(id: Long): File {
        val base = app.getExternalFilesDir(null) ?: app.filesDir
        return File(base, "tmp/$id")
    }

    private class Live {
        @Volatile var percent = 0f
        @Volatile var total = 0L
        @Volatile var speed = 0L
        @Volatile var eta = 0L
        @Volatile var detail: String? = "Starting…"
        @Volatile var streamCount = 1
        @Volatile var streamIndex = 0
        @Volatile var fragDone = 0
        @Volatile var fragTotal = 0
        @Volatile var seg: SegmentedDownloader? = null
    }

    private suspend fun runDownload(id: Long) {
        val e = dao.get(id) ?: return
        val tmp = tmpDir(id)
        val live = Live()
        val ticker = scope.launch {
            while (isActive) {
                delay(500)
                val seg = live.seg
                if (seg != null) {
                    val sn = seg.snapshot()
                    live.total = sn.total
                    live.speed = sn.speed
                    if (sn.total > 0) {
                        live.percent = (sn.done * 100f / sn.total).coerceIn(0f, 100f)
                        live.eta = if (sn.speed > 0) (sn.total - sn.done) / sn.speed else 0L
                    }
                    if (sn.cells.isEmpty()) {
                        _frags.update { it - id }
                    } else {
                        _frags.update {
                            it + (id to FragProgress(
                                sn.cells.count { c -> c >= 0.999f }, sn.cells.size, seg.workers,
                                sn.cells, sn.conns, sn.done, sn.total
                            ))
                        }
                    }
                    dao.setProgress(id, live.percent, live.total, live.speed, live.eta, live.detail)
                    continue
                }
                dao.setProgress(id, live.percent, live.total, live.speed, live.eta, live.detail)
                val total = live.fragTotal
                if (total > 0) {
                    val fp = FragProgress(live.fragDone, total, settingsRepo.flow.first().fragments)
                    _frags.update { it + (id to fp) }
                } else if (_frags.value.containsKey(id)) {
                    _frags.update { it - id }
                }
            }
        }
        try {
            if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()
            tmp.mkdirs()
            val settings = settingsRepo.flow.first()

            val fileSource = fileSourceOf(e)
            var plainFile = fileSource != null
            if (fileSource != null && fileSource.startsWith("ytdlp:")) {
                // An HLS stream found in the browser: yt-dlp downloads it with the page's headers.
                engine.awaitReady()
                val ds = DirectSource.decode(fileSource.removePrefix("ytdlp:"))
                val spec = DownloadSpec(DownloadKind.VIDEO, 0, "mp4", "m4a", "Stream (HLS)", "", "", "")
                plainFile = false
                runYtDlp(id, ds.url, spec, tmp, live, settings, ds.headers())
            } else if (fileSource != null) {
                try {
                    downloadFile(id, fileSource, tmp, live, settings)
                } catch (t: Throwable) {
                    live.seg = null
                    val retryWithYtDlp = t !is CancellationException && t !is YoutubeDL.CanceledException &&
                        !stopRequests.containsKey(id) && fileSource.startsWith("drive:") && e.kind != DownloadKind.AUDIO &&
                        DriveFiles.isVideoName(e.title, null)
                    if (!retryWithYtDlp) throw t
                    // A Drive video whose original file can't be fetched: use yt-dlp (streaming formats) instead.
                    plainFile = false
                    tmp.deleteRecursively(); tmp.mkdirs()
                    live.percent = 0f; live.total = 0; live.speed = 0
                    live.detail = "Trying another way…"
                    engine.awaitReady()
                    val spec = e.toSpec().copy(kind = DownloadKind.VIDEO, heightCap = 0, container = "mp4", pinned = "", fileSource = "")
                    runYtDlp(id, e.url, spec, tmp, live, settings)
                }
            } else {
                // First choice: our own downloader (many connections, merge with Android's muxer). yt-dlp is the fallback.
                val own = downloadOwn(id, e, tmp, live, settings)
                if (own) {
                    plainFile = true
                } else {
                    live.seg = null
                    tmp.deleteRecursively(); tmp.mkdirs()
                    live.percent = 0f; live.total = 0; live.speed = 0; live.streamIndex = 0; live.streamCount = 1
                    live.detail = "Starting…"
                    engine.awaitReady()
                    runYtDlp(id, e.url, e.toSpec(), tmp, live, settings)
                }
            }

            ticker.cancelAndJoin()
            dao.setProgress(id, 100f, live.total, 0, 0, "Saving to storage…")
            val output = findOutput(tmp, plainFile)
                ?: throw IOException("yt-dlp finished but produced no file.")
            val saved = storage.save(output, settings)
            tmp.deleteRecursively()
            dao.setCompleted(
                id, saved.uri.toString(), saved.displayName, saved.sizeBytes, System.currentTimeMillis(),
                if (saved.skipped) "Already existed — skipped (see duplicate handling in Settings)" else null
            )
            if (settings.notifications) notifier.done(id, e.title, true, saved.displayName)
        } catch (c: YoutubeDL.CanceledException) {
            ticker.cancelAndJoin()
            when (stopRequests.remove(id) ?: DownloadStatus.CANCELED) {
                DownloadStatus.PAUSED -> dao.setStatus(id, DownloadStatus.PAUSED, null, null, "Paused")
                DownloadStatus.QUEUED -> dao.setStatus(id, DownloadStatus.QUEUED, null, null, "Waiting for Wi-Fi")
                else -> {
                    tmp.deleteRecursively()
                    dao.setStatus(id, DownloadStatus.CANCELED, null, null, null)
                }
            }
        } catch (t: InterruptedException) {
            ticker.cancelAndJoin()
            throw t
        } catch (t: Throwable) {
            ticker.cancelAndJoin()
            val raw = when (t) {
                is YoutubeDLException, is EngineNotReadyException, is IOException -> t.message
                else -> "${t.javaClass.simpleName}: ${t.message}"
            }
            fail(id, e.title, raw)
        } finally {
            stopRequests.remove(id)
            _frags.update { it - id }
        }
    }

    private suspend fun runYtDlp(
        id: Long, url: String, spec: DownloadSpec, tmp: File, live: Live, settings: Settings,
        extraHeaders: Map<String, String> = emptyMap()
    ) {
        var attempt = 0
        engine.withClients(url) { client ->
            if (attempt++ > 0) {
                // Another YouTube mode: partial files from the previous mode may not match.
                tmp.deleteRecursively()
                tmp.mkdirs()
                live.percent = 0f
                live.total = 0
                live.streamIndex = 0
                live.fragDone = 0
                live.fragTotal = 0
                live.detail = "Retrying with another YouTube mode…"
            }
            val request = YoutubeDLRequest(url)
            val chunked = settings.parts > 4 && UrlParser.isYoutube(url)
            FormatPlanner.buildArgs(spec, settings.fragments, chunked).forEach { a ->
                if (a.value == null) request.addOption(a.option) else request.addOption(a.option, a.value)
            }
            request.addOption("-o", "${tmp.absolutePath}/%(title).100B.%(ext)s")
            extraHeaders.forEach { (k, v) -> request.addOption("--add-header", "$k:$v") }
            val cookieCopy = engine.configure(request, settings, client)
            try {
                YoutubeDL.execute(request, id.toString(), false) { _, eta, line ->
                    ProgressParser.streamCount(line)?.let { live.streamCount = it }
                    if (ProgressParser.isDestinationLine(line)) {
                        live.streamIndex++
                        live.fragDone = 0
                        live.fragTotal = 0
                        live.percent = 0f
                        live.total = 0
                        live.detail = if (live.streamCount > 1) "Downloading stream ${live.streamIndex} of ${live.streamCount}" else "Downloading"
                    }
                    ProgressParser.fragment(line)?.let { (done, total) ->
                        live.fragDone = done
                        live.fragTotal = total
                    }
                    ProgressParser.parse(line)?.let { p ->
                        live.percent = p.percent
                        live.total = p.totalBytes
                        live.speed = p.speedBytesPerSec
                        live.eta = eta
                        if (live.detail == null || live.detail == "Starting…") live.detail = "Downloading"
                    }
                    ProgressParser.postProcessLabel(line)?.let {
                        live.detail = it
                        live.speed = 0
                        live.eta = 0
                    }
                }
            } finally {
                cookieCopy?.delete()
            }
        }
    }

    private fun fileSourceOf(e: DownloadEntity): String? {
        if (e.kind == DownloadKind.FILE && e.fileSource.isNotEmpty()) return e.fileSource
        // Entries of a Drive folder have no duration: they are plain files (zip, pdf, video ...).
        if (e.kind != DownloadKind.AUDIO && e.durationSec == 0 && UrlParser.isDrive(e.url) &&
            !UrlParser.isDriveFolder(e.url) && DriveFiles.docKind(e.url) == null
        ) DriveFiles.fileId(e.url)?.let { return "drive:$it" }
        return null
    }

    private fun stopProcess(id: Long) {
        fileJobs[id]?.cancel()
        try { YoutubeDL.destroyProcessById(id.toString()) } catch (ignored: Throwable) { }
    }

    private fun safeName(n: String) = n.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "download" }

    /** Our own multi-connection downloader (Drive originals, Docs exports). */
    private suspend fun downloadFile(id: Long, source: String, tmp: File, live: Live, settings: Settings) {
        val parts = source.split(":")
        val cookies = AvenApp.instance.cookies
        val headers = HashMap<String, String>()
        headers["User-Agent"] = DriveFiles.UA
        val url: String
        val name: String
        val size: Long
        val ranges: Boolean
        var partsOverride = 0
        live.detail = "Preparing…"
        when (parts[0]) {
            "drive" -> {
                val cookie = cookies.headerFor("drive.usercontent.google.com")
                val p = withContext(Dispatchers.IO) { DriveFiles.probe(parts[1], cookie) }
                url = p.url; name = p.name; size = p.size; ranges = p.ranges
                if (cookie != null) headers["Cookie"] = cookie
            }
            "export" -> {
                val cookie = cookies.headerFor("docs.google.com")
                name = withContext(Dispatchers.IO) { DriveFiles.probeExport(parts[1], parts[2], parts[3], cookie) }
                url = DriveFiles.exportUrl(parts[1], parts[2], parts[3]); size = 0L; ranges = false
                if (cookie != null) headers["Cookie"] = cookie
            }
            "direct" -> {
                val ds = DirectSource.decode(source.substringAfter("direct:"))
                headers.clear()
                headers.putAll(ds.headers())
                if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = Net.DESKTOP_UA
                val p = withContext(Dispatchers.IO) { Net.probe(ds.url, headers) }
                url = p.finalUrl
                name = ds.name.ifBlank { p.fileName }
                size = p.size.coerceAtLeast(0L)
                ranges = p.ranges
                partsOverride = ds.parts
            }
            else -> throw IOException("DL: Unknown file source.")
        }
        if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()
        val seg = SegmentedDownloader(url, headers, File(tmp, safeName(name)), size, ranges, if (partsOverride > 0) partsOverride else settings.fragments)
        runSegmented(id, seg, live, size)
    }

    /** Runs one segmented download and keeps cancel/pause working. */
    private suspend fun runSegmented(id: Long, seg: SegmentedDownloader, live: Live, size: Long) {
        live.seg = seg
        live.total = size
        live.detail = if (seg.workers > 1) "Downloading · ${seg.workers} parts" else "Downloading"
        val job = scope.async(Dispatchers.IO) { seg.run() }
        fileJobs[id] = job
        try {
            job.await()
        } catch (c: CancellationException) {
            if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()
            throw c
        } finally {
            job.cancel()
            fileJobs.remove(id)
        }
        live.seg?.snapshot()?.let { live.total = it.total }
        live.seg = null
    }

    /**
     * Own downloader for video/audio: picks the streams, downloads each with many connections and merges
     * them with Android's MediaMuxer (no FFmpeg). @return false when yt-dlp should do it instead.
     */
    private suspend fun downloadOwn(id: Long, e: DownloadEntity, tmp: File, live: Live, settings: Settings): Boolean {
        if (e.kind == DownloadKind.FILE) return false
        val spec = e.toSpec()
        live.detail = "Reading the link…"
        val fetched = try {
            engine.fetch(e.url)
        } catch (c: CancellationException) {
            if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()
            throw c
        } catch (t: Throwable) {
            return false
        }
        val info = (fetched as? Fetched.Single)?.info ?: return false
        val plan = DirectPlanner.plan(info, spec) ?: return false
        if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()

        try {
            val base = safeName(e.title).take(100).trim().ifBlank { "download" }
            val cancelled = { stopRequests.containsKey(id) }
            val twoStreams = plan.video != null && plan.audio != null
            live.streamCount = if (twoStreams) 2 else 1
            val vFile = File(tmp, if (twoStreams) "$base.v.part" else if (plan.extractAudio) "$base.src.part" else "$base.${plan.ext}")
            val aFile = File(tmp, if (plan.video == null) "$base.${plan.ext}" else "$base.a.part")

            if (plan.video != null) {
                live.streamIndex = 1
                downloadStream(id, plan.video, vFile, live, settings,
                    if (twoStreams) "Downloading video (1 of 2)" else null)
            }
            if (plan.audio != null) {
                live.streamIndex = if (twoStreams) 2 else 1
                downloadStream(id, plan.audio, aFile, live, settings,
                    if (twoStreams) "Downloading audio (2 of 2)" else null)
            }

            if (twoStreams) {
                live.seg = null
                live.detail = "Merging video and audio…"
                live.speed = 0; live.eta = 0; live.percent = 0f
                val out = File(tmp, "$base.${plan.ext}")
                withContext(Dispatchers.IO) {
                    Muxer.merge(vFile, aFile, out, plan.webm, { live.percent = it * 100f }, cancelled)
                }
                vFile.delete(); aFile.delete()
                File(vFile.path + ".blocks").delete(); File(aFile.path + ".blocks").delete()
            } else if (plan.extractAudio) {
                live.seg = null
                live.detail = "Extracting the sound…"
                live.speed = 0; live.eta = 0
                val webm = Muxer.audioMime(vFile)?.let { !it.contains("mp4a") && !it.contains("aac") } ?: false
                val out = File(tmp, "$base.${if (webm) "weba" else "m4a"}")
                withContext(Dispatchers.IO) { Muxer.extractAudio(vFile, out, webm, cancelled) }
                vFile.delete()
                File(vFile.path + ".blocks").delete()
            }
            return true
        } catch (c: CancellationException) {
            if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()
            throw c
        } catch (c: YoutubeDL.CanceledException) {
            throw c
        } catch (t: Throwable) {
            // Server refused (403 / 429), merge not possible on this phone ... -> let yt-dlp try the old way.
            if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()
            return false
        }
    }

    private suspend fun downloadStream(id: Long, f: StreamFormat, dest: File, live: Live, settings: Settings, detail: String?) {
        val headers = HashMap(f.headers)
        if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = Net.DESKTOP_UA
        live.detail = "Preparing…"
        val url = f.url ?: throw IOException("No link for this stream")
        val p = withContext(Dispatchers.IO) { Net.probe(url, headers) }
        if (stopRequests.containsKey(id)) throw YoutubeDL.CanceledException()
        val size = p.size.coerceAtLeast(0L)
        val seg = SegmentedDownloader(p.finalUrl, headers, dest, size, p.ranges, settings.fragments)
        runSegmented(id, seg, live, size)
        if (detail != null) live.detail = detail
    }

    private suspend fun fail(id: Long, title: String, raw: String?) {
        val err = ErrorMapper.map(raw)
        dao.setStatus(id, DownloadStatus.FAILED, err.summary, err.details, null)
        val settings = settingsRepo.flow.first()
        if (settings.notifications) notifier.done(id, title, false, err.summary)
    }

    private fun findOutput(dir: File, plainFile: Boolean): File? =
        dir.listFiles()
            ?.filter { it.isFile && !it.name.endsWith(".blocks") && (plainFile || !isTemporary(it.name)) }
            ?.maxByOrNull { it.length() }

    private fun isTemporary(name: String): Boolean {
        val n = name.lowercase()
        return ".part" in n || n.endsWith(".ytdl") || n.endsWith(".temp") || n.endsWith(".json") ||
            n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp") ||
            n.endsWith(".vtt") || n.endsWith(".srt") || n.endsWith(".description")
    }
}
