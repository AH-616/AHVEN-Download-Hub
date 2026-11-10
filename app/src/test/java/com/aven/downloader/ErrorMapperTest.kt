package com.aven.downloader

import com.aven.downloader.engine.ErrorMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMapperTest {
    @Test fun privateVideo() {
        val e = ErrorMapper.map("ERROR: [youtube] abc: Private video. Sign in if you've been granted access")
        assertEquals("This video is private.", e.summary)
    }

    @Test fun botCheck() {
        val e = ErrorMapper.map("ERROR: [youtube] abc: Sign in to confirm you’re not a bot")
        assertTrue(e.summary.contains("bot verification"))
    }

    @Test fun formatNotAvailable() {
        val e = ErrorMapper.map("ERROR: Requested format is not available. Use --list-formats")
        assertTrue(e.summary.contains("isn't available"))
    }

    @Test fun noSpace() {
        assertEquals("Not enough storage space on the device.", ErrorMapper.map("OSError: [Errno 28] No space left on device").summary)
    }

    @Test fun emptyMessage() {
        assertTrue(ErrorMapper.map(null).summary.startsWith("Something went wrong"))
    }

    @Test fun unknownKeepsDetails() {
        val raw = "ERROR: [facebook] 123: weird new failure"
        val e = ErrorMapper.map(raw)
        assertEquals(raw, e.details)
        assertTrue(e.summary.contains("weird new failure"))
    }
}
