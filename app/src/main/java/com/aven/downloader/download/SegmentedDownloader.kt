package com.aven.downloader.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLongArray

/**
 * Downloads one file in blocks over several connections (HTTP Range), writing into [dest].
 * With [ranges] false (or an unknown size) it is a single plain stream.
 * Finished blocks are remembered in `<dest>.blocks`, so a paused/failed download continues where it stopped.
 * Written to be read by progress code: [snapshot] is cheap and thread-safe.
 */
class SegmentedDownloader(
    private val url: String,
    private val headers: Map<String, String>,
    private val dest: File,
    private val totalSize: Long,
    private val ranges: Boolean,
    parts: Int
) {
    class Conn(val index: Int, val block: Int, val done: Long, val left: Long, val speed: Long)
    class Snapshot(val done: Long, val total: Long, val speed: Long, val cells: FloatArray, val conns: List<Conn>)

    @Volatile private var segmented = ranges && totalSize > 0
    private val blockSize: Long = if (segmented) maxOf(MIN_BLOCK, (totalSize + MAX_BLOCKS - 1) / MAX_BLOCKS) else 0L
    private val blocks: Int = if (segmented) ((totalSize + blockSize - 1) / blockSize).toInt() else 0
    val workers: Int = if (segmented) minOf(parts.coerceIn(1, 16), maxOf(1, blocks)) else 1

    private val blockBytes = AtomicLongArray(blocks)
    private val connBytes = AtomicLongArray(workers)
    private val connBlock = AtomicIntegerArray(workers).also { for (i in 0 until workers) it.set(i, -1) }
    private val nextBlock = AtomicInteger(0)
    private val openConns = ConcurrentLinkedQueue<HttpURLConnection>()
    private val stateFile = File(dest.path + ".blocks")

    private fun blockLen(i: Int): Long = minOf(blockSize, totalSize - i * blockSize)

    // ---- progress ----
    private val lastBytes = LongArray(workers)
    private val speeds = LongArray(workers)
    private var lastAt = 0L

    @Synchronized
    fun snapshot(): Snapshot {
        val now = System.nanoTime()
        if (lastAt == 0L) lastAt = now
        val dt = (now - lastAt) / 1e9
        if (dt >= 0.3) {
            for (w in 0 until workers) {
                val b = connBytes.get(w)
                val inst = ((b - lastBytes[w]) / dt).toLong().coerceAtLeast(0)
                speeds[w] = if (speeds[w] == 0L) inst else (speeds[w] * 0.5 + inst * 0.5).toLong()
                lastBytes[w] = b
            }
            lastAt = now
        }
        val done: Long
        val cells: FloatArray
        if (segmented) {
            var d = 0L
            cells = FloatArray(blocks) { i ->
                val bb = blockBytes.get(i); d += bb
                (bb.toFloat() / blockLen(i)).coerceIn(0f, 1f)
            }
            done = d
        } else {
            cells = FloatArray(0)
            done = connBytes.get(0)
        }
        val conns = (0 until workers).map { w ->
            val b = connBlock.get(w)
            if (b < 0 || !segmented) Conn(w + 1, -1, 0, 0, speeds[w])
            else {
                val bd = blockBytes.get(b)
                Conn(w + 1, b, bd, blockLen(b) - bd, speeds[w])
            }
        }
        return Snapshot(done, totalSize, speeds.sum(), cells, conns)
    }

    // ---- running ----
    suspend fun run() = withContext(Dispatchers.IO) {
        try {
            if (segmented) {
                try { runSegmented() } catch (e: RangeIgnored) {
                    // The server answers 200 to range requests: fall back to one plain stream.
                    segmented = false
                    runSingle()
                }
            } else runSingle()
        } finally {
            openConns.forEach { try { it.disconnect() } catch (ignored: Exception) { } }
            openConns.clear()
        }
    }

    private suspend fun runSegmented() {
        val resumable = dest.exists() && dest.length() == totalSize && stateFile.exists()
        if (resumable) {
            stateFile.readLines().mapNotNull { it.trim().toIntOrNull() }.filter { it in 0 until blocks }.forEach {
                blockBytes.set(it, blockLen(it))
            }
        } else {
            dest.parentFile?.mkdirs()
            dest.delete(); stateFile.delete()
            RandomAccessFile(dest, "rw").use { it.setLength(totalSize) }
        }
        coroutineScope {
            for (w in 0 until workers) launch(Dispatchers.IO) { worker(w) }
        }
        // every block must be complete
        for (i in 0 until blocks) if (blockBytes.get(i) != blockLen(i)) throw IOException("DL: Download ended early. Tap retry to continue.")
        stateFile.delete()
    }

    private suspend fun worker(w: Int) {
        RandomAccessFile(dest, "rw").use { raf ->
            while (true) {
                val b = nextBlock.getAndIncrement()
                if (b >= blocks) break
                if (blockBytes.get(b) == blockLen(b)) continue
                connBlock.set(w, b)
                downloadBlock(w, b, raf)
                synchronized(stateFile) { stateFile.appendText("$b\n") }
            }
            connBlock.set(w, -1)
        }
    }

    private suspend fun downloadBlock(w: Int, b: Int, raf: RandomAccessFile) {
        var attempt = 0
        val buf = ByteArray(64 * 1024)
        while (true) {
            val start = b * blockSize + blockBytes.get(b)
            val end = b * blockSize + blockLen(b) - 1
            if (start > end) return
            val c = connect(start, end)
            try {
                val code = c.responseCode
                if (code == 429 || code == 403) throw IOException("DL: Google Drive refused the extra connections (HTTP $code). Lower \"Number of parts\" in Settings and retry.")
                if (code == 200) throw RangeIgnored()
                if (code != 206) throw IOException("HTTP $code")
                c.inputStream.use { ins ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = ins.read(buf)
                        if (n < 0) break
                        raf.seek(b * blockSize + blockBytes.get(b))
                        raf.write(buf, 0, n)
                        blockBytes.addAndGet(b, n.toLong())
                        connBytes.addAndGet(w, n.toLong())
                    }
                }
                if (blockBytes.get(b) >= blockLen(b)) return
                throw IOException("Connection closed early")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (e.message?.startsWith("DL:") == true) throw e
                if (++attempt >= 6) throw IOException("DL: Download failed after several tries (${e.message}). Tap retry to continue.")
                delay(attempt * 1500L)
            } finally {
                openConns.remove(c); try { c.disconnect() } catch (ignored: Exception) { }
            }
        }
    }

    private suspend fun runSingle() {
        dest.parentFile?.mkdirs()
        var attempt = 0
        while (true) {
            dest.delete()
            connBytes.set(0, 0)
            connBlock.set(0, -1)
            val c = connect(-1, -1)
            try {
                val code = c.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val buf = ByteArray(64 * 1024)
                RandomAccessFile(dest, "rw").use { raf ->
                    c.inputStream.use { ins ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = ins.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            connBytes.addAndGet(0, n.toLong())
                        }
                    }
                }
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (++attempt >= 3) throw IOException("DL: Download failed (${e.message}). Tap retry.")
                delay(attempt * 1500L)
            } finally {
                openConns.remove(c); try { c.disconnect() } catch (ignored: Exception) { }
            }
        }
    }

    private fun connect(start: Long, end: Long): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 30_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("Accept-Encoding", "identity")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (start >= 0) c.setRequestProperty("Range", "bytes=$start-$end")
        openConns.add(c)
        return c
    }

    private class RangeIgnored : IOException("server ignored range")

    companion object {
        const val MIN_BLOCK = 512L * 1024
        const val MAX_BLOCKS = 100L
    }
}
