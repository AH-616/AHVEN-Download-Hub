package com.aven.downloader

import com.aven.downloader.engine.DownloadKind
import com.aven.downloader.engine.DownloadSpec
import com.aven.downloader.engine.DriveFiles
import com.aven.downloader.engine.FormatPlanner
import com.aven.downloader.engine.InfoParser
import com.aven.downloader.util.UrlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAndPinTest {
    @Test fun fileIdsAndDocKinds() {
        assertEquals("ABC_123-x", DriveFiles.fileId("https://drive.google.com/file/d/ABC_123-x/view"))
        assertEquals("ID9", DriveFiles.fileId("https://drive.google.com/open?id=ID9"))
        assertEquals("DOC1", DriveFiles.fileId("https://docs.google.com/document/d/DOC1/edit"))
        assertEquals("document", DriveFiles.docKind("https://docs.google.com/document/d/DOC1/edit"))
        assertEquals("spreadsheets", DriveFiles.docKind("https://docs.google.com/spreadsheets/d/S/edit#gid=0"))
        assertNull(DriveFiles.docKind("https://drive.google.com/file/d/X/view"))
        assertEquals("https://docs.google.com/presentation/d/P/export?format=pptx", DriveFiles.exportUrl("presentation", "P", "pptx"))
        assertEquals(listOf("pdf", "docx", "txt"), DriveFiles.exportsFor("document").map { it.fmt })
    }

    @Test fun headersParsing() {
        assertEquals("my file.zip", DriveFiles.filenameFrom("attachment; filename=\"my file.zip\"; filename*=UTF-8''my%20file.zip"))
        assertEquals("a.pdf", DriveFiles.filenameFrom("attachment; filename=\"a.pdf\""))
        assertEquals(12345L, DriveFiles.totalFromContentRange("bytes 0-0/12345"))
        assertTrue(DriveFiles.isVideoName("clip.MP4", null))
        assertTrue(DriveFiles.isVideoName("x", "video/webm"))
        assertTrue(!DriveFiles.isVideoName("a.zip", "application/zip"))
    }

    @Test fun confirmFormAndFolder() {
        val html = """<form id="download-form" action="https://drive.usercontent.google.com/download" method="get">
            <input type="hidden" name="id" value="F1"><input type="hidden" name="confirm" value="t">
            <input type="hidden" name="uuid" value="u-1"></form>"""
        val u = DriveFiles.confirmUrl(html)
        assertNotNull(u)
        assertTrue(u!!.startsWith("https://drive.usercontent.google.com/download?"))
        assertTrue("id=F1" in u && "uuid=u-1" in u)
        assertNull(DriveFiles.confirmUrl("<html>nothing</html>"))
        val folder = """<div class="flip-entry" id="entry-AAA"><a href="https://drive.google.com/file/d/AAA/view"><div class="flip-entry-title">One.mp4</div></a></div>
            <div class="flip-entry" id="entry-BBB"><a href="https://drive.google.com/drive/folders/BBB"><div class="flip-entry-title">Sub</div></a></div>"""
        val e = DriveFiles.parseFolder(folder)
        assertEquals(2, e.size)
        assertEquals("One.mp4", e[0].name)
        assertTrue(!e[0].isFolder && e[1].isFolder)
    }

    @Test fun pinnedFormatsComeFirst() {
        val info = InfoParser.parse("https://youtu.be/abc", TestData.dashJson)
        val opts = FormatPlanner.videoOptions(info, "mp4")
        val p720 = opts.first { it.height == 720 }
        assertEquals("136+140", p720.pinned)
        val args = FormatPlanner.buildArgs(DownloadSpec(DownloadKind.VIDEO, 720, "mp4", "mp3", "720p", pinned = p720.pinned))
            .associate { it.option to it.value }
        assertTrue(args["-f"]!!.startsWith("136+140/"))
        val noPin = FormatPlanner.buildArgs(DownloadSpec(DownloadKind.VIDEO, 720, "mp4", "mp3", "720p")).associate { it.option to it.value }
        assertTrue(noPin["-f"]!!.startsWith("bv*"))
    }

    @Test fun knownHostsForClipboard() {
        assertTrue(UrlParser.isKnownVideoHost("https://www.instagram.com/reel/x"))
        assertTrue(UrlParser.isKnownVideoHost("https://drive.google.com/file/d/x/view"))
        assertTrue(!UrlParser.isKnownVideoHost("https://example.com/page"))
    }
}
