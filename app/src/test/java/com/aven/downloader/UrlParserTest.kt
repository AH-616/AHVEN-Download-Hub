package com.aven.downloader

import com.aven.downloader.util.Platform
import com.aven.downloader.util.UrlParser
import com.aven.downloader.util.UrlResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlParserTest {
    private fun supported(text: String, platform: Platform) {
        val r = UrlParser.parse(text)
        assertTrue("expected supported: $text but was $r", r is UrlResult.Supported)
        assertEquals(platform, (r as UrlResult.Supported).platform)
    }

    private fun unsupported(text: String) {
        val r = UrlParser.parse(text)
        assertTrue("expected unsupported: $text but was $r", r is UrlResult.Unsupported)
    }

    @Test fun youtubeWatch() = supported("https://www.youtube.com/watch?v=dQw4w9WgXcQ", Platform.YOUTUBE)
    @Test fun youtubeMobile() = supported("https://m.youtube.com/watch?v=dQw4w9WgXcQ&t=10s", Platform.YOUTUBE)
    @Test fun youtuBe() = supported("https://youtu.be/dQw4w9WgXcQ?si=abc", Platform.YOUTUBE)
    @Test fun youtubeShorts() = supported("https://www.youtube.com/shorts/abc123", Platform.YOUTUBE)
    @Test fun youtubeWatchWithPlaylistIsSingleVideo() =
        supported("https://www.youtube.com/watch?v=abc&list=PL123", Platform.YOUTUBE)
    @Test fun youtubeNoScheme() = supported("youtube.com/watch?v=abc", Platform.YOUTUBE)
    @Test fun facebookWatch() = supported("https://www.facebook.com/watch/?v=123456", Platform.FACEBOOK)
    @Test fun facebookReel() = supported("https://www.facebook.com/reel/123456", Platform.FACEBOOK)
    @Test fun facebookVideosPath() = supported("https://www.facebook.com/someone/videos/123456/", Platform.FACEBOOK)
    @Test fun fbWatch() = supported("https://fb.watch/abcDEF/", Platform.FACEBOOK)

    @Test fun sharedTextWithMessage() {
        val r = UrlParser.parse("Check this out! https://youtu.be/abc123, it's great")
        assertEquals("https://youtu.be/abc123", (r as UrlResult.Supported).url)
    }

    @Test fun instagramAccepted() = supported("https://www.instagram.com/reel/Cabc123/", Platform.OTHER)
    @Test fun tiktokAccepted() = supported("https://www.tiktok.com/@user/video/12345", Platform.OTHER)
    @Test fun anyOtherSiteIsLeftToYtDlp() = supported("https://example.com/video.mp4", Platform.OTHER)
    @Test fun lookalikeHostIsNotYoutube() = supported("https://notyoutube.com/watch?v=abc", Platform.OTHER)
    @Test fun lookalikeSuffixIsNotYoutube() = supported("https://youtube.com.evil.io/watch?v=abc", Platform.OTHER)
    @Test fun youtubeChannelAccepted() = supported("https://www.youtube.com/@somechannel", Platform.YOUTUBE)
    @Test fun youtubePlaylistAccepted() = supported("https://www.youtube.com/playlist?list=PL123", Platform.YOUTUBE)
    @Test fun youtubeHomeRejected() = unsupported("https://www.youtube.com/")
    @Test fun facebookHomeRejected() = unsupported("https://www.facebook.com/")
    @Test fun emptyRejected() = unsupported("")
    @Test fun plainTextRejected() = unsupported("hello world")
    @Test fun hostWithoutDotRejected() = unsupported("https://localhost/video")

    @Test fun playlistPageDetected() {
        assertTrue(UrlParser.isPlaylistPage("https://www.youtube.com/playlist?list=PL123abc"))
        assertFalse(UrlParser.isPlaylistPage("https://www.youtube.com/watch?v=abc&list=PL123abc"))
        assertFalse(UrlParser.isPlaylistPage("https://example.com/playlist?list=PL123"))
    }

    @Test fun playlistUrlFromWatchLink() {
        assertEquals(
            "https://www.youtube.com/playlist?list=PL123abc",
            UrlParser.playlistUrlOf("https://www.youtube.com/watch?v=abc&list=PL123abc&index=2")
        )
        assertNull(UrlParser.playlistUrlOf("https://www.youtube.com/watch?v=abc"))
        assertNull(UrlParser.playlistUrlOf("https://www.youtube.com/watch?v=abc&list=RDabc"))
    }

    @Test fun extractUrlNullForBlank() = assertNull(UrlParser.extractUrl("   "))
}
