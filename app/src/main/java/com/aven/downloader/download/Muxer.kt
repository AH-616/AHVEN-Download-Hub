package com.aven.downloader.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Puts a downloaded video stream and audio stream into one file with Android's own MediaExtractor/MediaMuxer
 * (no re-encoding, no FFmpeg). MP4 takes H.264/H.265 + AAC, WebM takes VP9 + Opus.
 */
object Muxer {
    private const val BUFFER = 6 * 1024 * 1024

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty().startsWith(prefix)) return i
        }
        return -1
    }

    /** MIME of the first audio track of [file] ("audio/mp4a-latm", "audio/opus"...), or null. */
    fun audioMime(file: File): String? {
        val ex = MediaExtractor()
        return try {
            ex.setDataSource(file.absolutePath)
            val t = findTrack(ex, "audio/")
            if (t < 0) null else ex.getTrackFormat(t).getString(MediaFormat.KEY_MIME)
        } catch (e: Exception) { null } finally { ex.release() }
    }

    /**
     * [video] and [audio] are single-track files. [onProgress] gets 0..1. [cancelled] is polled often;
     * when it returns true the merge stops with a [java.util.concurrent.CancellationException].
     */
    fun merge(video: File, audio: File, out: File, webm: Boolean, onProgress: (Float) -> Unit, cancelled: () -> Boolean) {
        val vx = MediaExtractor()
        val ax = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            vx.setDataSource(video.absolutePath)
            ax.setDataSource(audio.absolutePath)
            val vt = findTrack(vx, "video/")
            val at = findTrack(ax, "audio/")
            if (vt < 0 || at < 0) throw IOException("DL: Couldn't read the downloaded video or audio (unsupported format).")
            vx.selectTrack(vt)
            ax.selectTrack(at)
            val vf = vx.getTrackFormat(vt)
            val af = ax.getTrackFormat(at)
            out.parentFile?.mkdirs()
            out.delete()
            muxer = MediaMuxer(out.absolutePath, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            if (!webm && vf.containsKey(MediaFormat.KEY_ROTATION)) {
                try { muxer.setOrientationHint(vf.getInteger(MediaFormat.KEY_ROTATION)) } catch (ignored: Exception) { }
            }
            val vi = muxer.addTrack(vf)
            val ai = muxer.addTrack(af)
            muxer.start()
            started = true
            val durationUs = if (vf.containsKey(MediaFormat.KEY_DURATION)) vf.getLong(MediaFormat.KEY_DURATION) else 0L

            val buf = ByteBuffer.allocate(BUFFER)
            val info = MediaCodec.BufferInfo()
            var vEnd = false
            var aEnd = false
            var lastReport = 0L
            while (true) {
                if (cancelled()) throw java.util.concurrent.CancellationException("merge cancelled")
                val vTime = if (vEnd) -1L else vx.sampleTime
                val aTime = if (aEnd) -1L else ax.sampleTime
                if (vTime < 0) vEnd = true
                if (aTime < 0) aEnd = true
                if (vEnd && aEnd) break
                val useVideo = !vEnd && (aEnd || vTime <= aTime)
                val ex = if (useVideo) vx else ax
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) { if (useVideo) vEnd = true else aEnd = true; continue }
                val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, ex.sampleTime, flags)
                muxer.writeSampleData(if (useVideo) vi else ai, buf, info)
                if (useVideo && durationUs > 0 && info.presentationTimeUs - lastReport > 1_000_000) {
                    lastReport = info.presentationTimeUs
                    onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
                }
                ex.advance()
            }
            muxer.stop()
            started = false
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("DL: Couldn't merge the video and audio on this phone (${e.javaClass.simpleName}: ${e.message}). " +
                "Pick a lower quality or another format.")
        } finally {
            try { if (started) muxer?.stop() } catch (ignored: Exception) { }
            try { muxer?.release() } catch (ignored: Exception) { }
            vx.release()
            ax.release()
        }
    }

    /** Cuts the sound out of a finished video file (no re-encoding). [webm] true for Opus/Vorbis sound. */
    fun extractAudio(src: File, out: File, webm: Boolean, cancelled: () -> Boolean) {
        val ex = MediaExtractor()
        var muxer: MediaMuxer? = null
        var started = false
        try {
            ex.setDataSource(src.absolutePath)
            val t = findTrack(ex, "audio/")
            if (t < 0) throw IOException("DL: This video has no separate sound track to extract.")
            ex.selectTrack(t)
            out.parentFile?.mkdirs()
            out.delete()
            muxer = MediaMuxer(out.absolutePath, if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val idx = muxer.addTrack(ex.getTrackFormat(t))
            muxer.start()
            started = true
            val buf = ByteBuffer.allocate(BUFFER / 4)
            val info = MediaCodec.BufferInfo()
            while (true) {
                if (cancelled()) throw java.util.concurrent.CancellationException("extract cancelled")
                buf.clear()
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                info.set(0, size, ex.sampleTime, MediaCodec.BUFFER_FLAG_KEY_FRAME)
                muxer.writeSampleData(idx, buf, info)
                ex.advance()
            }
            muxer.stop()
            started = false
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: RuntimeException) {
            throw IOException("DL: Couldn't extract the sound (${e.javaClass.simpleName}: ${e.message}).")
        } finally {
            try { if (started) muxer?.stop() } catch (ignored: Exception) { }
            try { muxer?.release() } catch (ignored: Exception) { }
            ex.release()
        }
    }
}
