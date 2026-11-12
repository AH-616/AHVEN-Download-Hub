package com.aven.downloader

import com.aven.downloader.engine.DownloadKind
import com.aven.downloader.engine.DownloadSpec
import com.aven.downloader.engine.Engine
import com.aven.downloader.engine.FormatPlanner
import com.aven.downloader.engine.InfoParser
import com.aven.downloader.engine.MediaInfo
import com.aven.downloader.engine.PlaylistEntry
import com.aven.downloader.engine.PlaylistEstimator
import com.aven.downloader.engine.ProgressParser
import com.aven.downloader.util.Platform
import com.aven.downloader.util.UrlParser
import com.aven.downloader.util.UrlResult
import com.aven.downloader.engine.StreamFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistAndAudioTest {
    private val playlistJson = """{"_type":"playlist","title":"My list","entries":[
        {"id":"aaa","url":"https://www.youtube.com/watch?v=aaa","title":"One","duration":60},
        {"id":"bbb","ie_key":"Youtube","title":"Two","duration":120},
        {"title":"no url"}]}"""

    @Test fun parsesPlaylistEntries() {
        assertTrue(InfoParser.isPlaylist(playlistJson))
        val p = InfoParser.parsePlaylist("https://www.youtube.com/playlist?list=x", playlistJson)
        assertEquals("My list", p.title)
        assertEquals(2, p.entries.size)
        assertEquals("https://www.youtube.com/watch?v=bbb", p.entries[1].url)
    }

    @Test fun estimatesOnlySelected() {
        val c = PlaylistEstimator.choices.first { it.label == "Audio M4A" }
        val e = listOf(PlaylistEntry("u1", "a", 100, null, null), PlaylistEntry("u2", "b", 0, null, null))
        val t = PlaylistEstimator.total(e, c)
        assertEquals(100L * 130 * 1000 / 8, t.bytes)
        assertEquals(1, t.unknownCount)
    }

    private fun audio(id: String, lang: String, note: String) =
        StreamFormat(id, "m4a", 0, 0, "none", "mp4a", 0.0, 128.0, 0, null, "https", emptyMap(), lang, note)

    private val multi = MediaInfo("u", "t", null, 60, null, null, false, listOf(
        audio("1", "hi", "Hindi"), audio("2", "en-US", "English (US), original (default)")))

    @Test fun offersAudioLanguagesOnlyWhenSeveral() {
        val tracks = FormatPlanner.audioTracks(multi)
        assertEquals(2, tracks.size)
        assertTrue(tracks[0].isDefault)
        assertTrue(FormatPlanner.audioTracks(multi.copy(formats = listOf(audio("1", "hi", "Hindi")))).isEmpty())
    }

    @Test fun languageGoesIntoSelector() {
        val a = FormatPlanner.buildArgs(DownloadSpec(DownloadKind.VIDEO, 720, "mp4", "mp3", "720p", "hi"))
            .first { it.option == "-f" }.value!!
        assertTrue(a.contains("[language^=hi]"))
        val d = FormatPlanner.buildArgs(DownloadSpec(DownloadKind.VIDEO, 720, "mp4", "mp3", "720p"))
            .first { it.option == "-f" }.value!!
        assertFalse(d.contains("language"))
    }

    @Test fun clientFallbackRules() {
        assertFalse(Engine.shouldTryNextClient("ERROR: Private video"))
        assertFalse(Engine.shouldTryNextClient(null))
    }

    // ---- size estimate follows the real format choice ----
    private fun video(id: String, h: Int, codec: String, size: Long) =
        StreamFormat(id, "mp4", h, h * 16 / 9, codec, "none", 0.0, 0.0, size, null, "https", emptyMap())

    private val estInfo = MediaInfo("u", "t", null, 100, null, null, false, listOf(
        video("a", 1080, "avc1.640028", 300_000_000L),
        video("b", 1080, "vp09.00.40.08", 200_000_000L),
        video("c", 1080, "av01.0.08M.08", 150_000_000L),
        audio("m", "en", "m4a").copy(ext = "m4a", fileSize = 3_000_000L),
        audio("o", "en", "opus").copy(ext = "webm", acodec = "opus", fileSize = 4_000_000L, abr = 160.0)
    ))

    @Test fun mp4EstimateUsesH264NotTheBiggestOrSmallest() {
        // yt-dlp -S vcodec:h264 picks the H.264 stream (300 MB) + M4A audio (3 MB)
        assertEquals(303_000_000L, FormatPlanner.videoOptions(estInfo, "mp4").first().estimatedSize)
    }

    @Test fun mkvEstimateUsesNewestCodec() {
        // default sort prefers AV1 (150 MB); audio = best bitrate (opus 4 MB)
        assertEquals(154_000_000L, FormatPlanner.videoOptions(estInfo, "mkv").first().estimatedSize)
    }

    @Test fun audioEstimatesUseTheRealStreams() {
        val opts = FormatPlanner.audioOptions(estInfo).associate { it.audioFormat to it.estimatedSize }
        assertEquals(3_000_000L, opts["m4a"])
        assertEquals(4_000_000L, opts["opus"])
        assertFalse(opts.containsKey("mp3"))
    }

    // ---- speed ----
    @Test fun fragmentsAndChunkArgs() {
        val spec = DownloadSpec(DownloadKind.VIDEO, 0, "mp4", "mp3", "Best")
        val a = FormatPlanner.buildArgs(spec, 16, true).associate { it.option to it.value }
        assertEquals("16", a["-N"])
        assertEquals("10M", a["--http-chunk-size"])
        val d = FormatPlanner.buildArgs(spec).associate { it.option to it.value }
        assertEquals("4", d["-N"])
        assertFalse(d.containsKey("--http-chunk-size"))
    }

    @Test fun parsesFragmentProgress() {
        val line = "[download]  12.3% of ~ 100.00MiB at  1.20MiB/s ETA 00:30 (frag 12/100)"
        assertEquals(12 to 100, ProgressParser.fragment(line))
        assertEquals(null, ProgressParser.fragment("[download]  50.0% of 10.00MiB at 1.00MiB/s ETA 00:05"))
    }

    // ---- Google Drive ----
    @Test fun driveFolderLinksAreNormalised() {
        val id = "1AbCdEfGhIjKlMnOpQrStUvWxYz012345"
        assertTrue(UrlParser.isDriveFolder("https://drive.google.com/drive/u/0/folders/$id?usp=sharing"))
        assertEquals("https://drive.google.com/drive/folders/$id", UrlParser.normalizeDrive("https://drive.google.com/drive/u/0/folders/$id?usp=sharing"))
        assertEquals("https://drive.google.com/drive/folders/$id", UrlParser.normalizeDrive("https://drive.google.com/folderview?id=$id"))
    }

    @Test fun driveFileLinksLoseTrackingParameters() {
        assertFalse(UrlParser.isDriveFolder("https://drive.google.com/file/d/abc123/view?usp=sharing"))
        assertEquals("https://drive.google.com/file/d/abc123/view", UrlParser.normalizeDrive("https://drive.google.com/file/d/abc123/view?usp=sharing"))
        assertEquals("https://drive.google.com/file/d/xyz/view", UrlParser.normalizeDrive("https://drive.google.com/open?id=xyz"))
        val r = UrlParser.parse("https://drive.google.com/file/d/abc123/view?usp=sharing") as UrlResult.Supported
        assertEquals(Platform.DRIVE, r.platform)
    }
}
