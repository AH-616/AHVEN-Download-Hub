package com.aven.downloader

import com.aven.downloader.engine.DownloadKind
import com.aven.downloader.engine.DownloadSpec
import com.aven.downloader.engine.FormatPlanner
import com.aven.downloader.engine.InfoParser
import com.aven.downloader.engine.PreviewPlanner
import com.aven.downloader.engine.PreviewSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoAndFormatTest {
    private val dash = InfoParser.parse("https://youtu.be/abc", "WARNING: noise\n" + TestData.dashJson)
    private val prog = InfoParser.parse("https://fb.watch/x", TestData.progressiveJson)

    @Test fun parsesBasics() {
        assertEquals("Sample video", dash.title)
        assertEquals("Some Channel", dash.uploader)
        assertEquals(125, dash.durationSec)
        assertEquals(6, dash.formats.size)
        assertNull(prog.uploader)
    }

    @Test fun videoOptionsOnlyListExistingHeights() {
        val labels = FormatPlanner.videoOptions(dash).map { it.label }
        // heights present: 1080, 720, 480 (hls), 360 -> mhtml storyboard (90p) has no video codec
        assertEquals(listOf("Best (1080p)", "720p", "480p", "360p"), labels)
        val caps = FormatPlanner.videoOptions(dash).map { it.heightCap }
        assertEquals(listOf(0, 720, 480, 360), caps)
    }

    @Test fun facebookOptions() {
        assertEquals(listOf("Best (720p)", "360p"), FormatPlanner.videoOptions(prog).map { it.label })
    }

    @Test fun sizeEstimateAddsAudioForVideoOnlyStreams() {
        val best = FormatPlanner.videoOptions(dash).first()
        assertEquals(60_000_000L + 2_000_000L, best.estimatedSize)
    }

    @Test fun noHeightsFallsBackToBestOnly() {
        val info = InfoParser.parse("u", TestData.noHeightJson)
        val opts = FormatPlanner.videoOptions(info)
        assertEquals(1, opts.size)
        assertEquals(0, opts[0].heightCap)
    }

    @Test fun audioOptionsAvailable() {
        assertEquals(listOf("mp3", "m4a"), FormatPlanner.audioOptions(dash).map { it.audioFormat })
    }

    @Test fun defaultPick() {
        val opts = FormatPlanner.videoOptions(dash)
        assertEquals(0, FormatPlanner.pickDefault(opts, 0))
        assertEquals(1, FormatPlanner.pickDefault(opts, 720))   // 720p
        assertEquals(1, FormatPlanner.pickDefault(opts, 1080 - 1).let { if (it == 1) 1 else it })
        assertEquals(3, FormatPlanner.pickDefault(opts, 360))
        assertEquals(3, FormatPlanner.pickDefault(opts, 240))   // nothing <= 240: lowest available
    }

    @Test fun videoArgs() {
        val args = FormatPlanner.buildArgs(DownloadSpec(DownloadKind.VIDEO, 720, "mp4", "mp3", "720p"))
        val map = args.associate { it.option to it.value }
        assertEquals("bv*[height<=720]+ba/b[height<=720]/bv*+ba/b", map["-f"])
        assertEquals("mp4", map["--merge-output-format"])
        assertEquals("res,vcodec:h264,acodec:m4a", map["-S"])
        assertTrue(map.containsKey("--no-playlist"))
    }

    @Test fun mkvDoesNotForceCodecs() {
        val args = FormatPlanner.buildArgs(DownloadSpec(DownloadKind.VIDEO, 0, "mkv", "mp3", "Best"))
        assertFalse(args.any { it.option == "-S" })
        assertEquals("bv*+ba/b", args.first { it.option == "-f" }.value)
    }

    @Test fun audioArgs() {
        val mp3 = FormatPlanner.buildArgs(DownloadSpec(DownloadKind.AUDIO, 0, "mp4", "mp3", "MP3")).associate { it.option to it.value }
        assertEquals("mp3", mp3["--audio-format"])
        assertTrue(mp3.containsKey("-x"))
        val m4a = FormatPlanner.buildArgs(DownloadSpec(DownloadKind.AUDIO, 0, "mp4", "m4a", "M4A")).associate { it.option to it.value }
        assertEquals("ba[ext=m4a]/ba/b", m4a["-f"])
    }

    // ---- preview ----

    @Test fun previewPrefersCombinedStream() {
        val src = PreviewPlanner.choose(dash)
        assertTrue(src is PreviewSource.Single)
        // combined candidates: 18 (360p https) and hls-1 (480p m3u8). 480p wins, played as HLS.
        src as PreviewSource.Single
        assertEquals("https://cdn.example/x.m3u8", src.url)
        assertTrue(src.hls)
    }

    @Test fun previewFacebookPicksHdProgressive() {
        val src = PreviewPlanner.choose(prog) as PreviewSource.Single
        assertEquals("https://fb.example/hd.mp4", src.url)
        assertFalse(src.hls)
    }

    @Test fun previewMergesVideoAndAudioWhenNoCombined() {
        val onlySplit = InfoParser.parse("u", TestData.dashJson.replace("\"url\":\"https://cdn.example/v18\",\"protocol\":\"https\"", "\"url\":\"https://cdn.example/v18\",\"protocol\":\"http_dash_segments\"")
            .replace("\"protocol\":\"m3u8_native\"", "\"protocol\":\"http_dash_segments\""))
        val src = PreviewPlanner.choose(onlySplit)
        assertTrue("was $src", src is PreviewSource.Merged)
        src as PreviewSource.Merged
        assertEquals("https://cdn.example/v136", src.videoUrl)   // best video <= 720p
        assertEquals("https://cdn.example/a140", src.audioUrl)
    }

    @Test fun acceptEncodingHeaderIsDropped() {
        val h = PreviewPlanner.cleanHeaders(mapOf("User-Agent" to "UA", "accept-encoding" to "gzip"))
        assertEquals(mapOf("User-Agent" to "UA"), h)
    }

    @Test fun liveStreamsHaveNoPreview() {
        val live = InfoParser.parse("u", TestData.progressiveJson.replace("\"duration\":30", "\"duration\":30,\"is_live\":true"))
        assertNull(PreviewPlanner.choose(live))
    }
}
