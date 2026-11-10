package com.aven.downloader

import com.aven.downloader.engine.ProgressParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressParserTest {
    @Test fun normalLine() {
        val p = ProgressParser.parse("[download]  45.2% of  120.00MiB at    2.50MiB/s ETA 00:31")
        assertNotNull(p)
        assertEquals(45.2f, p!!.percent, 0.001f)
        assertEquals(120L * 1024 * 1024, p.totalBytes)
        assertEquals((2.5 * 1024 * 1024).toLong(), p.speedBytesPerSec)
    }

    @Test fun approximateTotalAndFragments() {
        val p = ProgressParser.parse("[download]  12.0% of ~  50.00MiB at  1.20MiB/s ETA 00:40 (frag 3/20)")
        assertNotNull(p)
        assertEquals(12.0f, p!!.percent, 0.001f)
        assertEquals(50L * 1024 * 1024, p.totalBytes)
    }

    @Test fun unknownSpeedStillParsesPercent() {
        val p = ProgressParser.parse("[download]   0.0% of  10.00KiB at  Unknown B/s ETA Unknown")
        assertNotNull(p)
        assertEquals(10L * 1024, p!!.totalBytes)
        assertEquals(0L, p.speedBytesPerSec)
    }

    @Test fun nonProgressLinesIgnored() {
        assertNull(ProgressParser.parse("[youtube] abc: Downloading webpage"))
        assertNull(ProgressParser.parse("[Merger] Merging formats into \"x.mp4\""))
    }

    @Test fun streamCount() {
        assertEquals(2, ProgressParser.streamCount("[info] abc: Downloading 2 format(s): 137+140"))
        assertNull(ProgressParser.streamCount("something else"))
    }

    @Test fun destinationAndPostProcess() {
        assertTrue(ProgressParser.isDestinationLine("[download] Destination: /tmp/x.f137.mp4"))
        assertEquals("Merging video and audio…", ProgressParser.postProcessLabel("[Merger] Merging formats into \"x.mp4\""))
        assertEquals("Converting audio…", ProgressParser.postProcessLabel("[ExtractAudio] Destination: x.mp3"))
        assertNull(ProgressParser.postProcessLabel("[download] 10% of 1MiB"))
    }
}
