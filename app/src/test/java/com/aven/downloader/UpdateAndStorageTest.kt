package com.aven.downloader

import com.aven.downloader.data.StorageSaver
import com.aven.downloader.update.UpdatePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateAndStorageTest {
    private val day = 24L * 60 * 60 * 1000

    @Test fun graceIsTwoDays() {
        val t0 = 1_000_000L
        assertFalse(UpdatePolicy.mustUpdate(5, 6, t0, t0 + 1 * day))
        assertFalse(UpdatePolicy.mustUpdate(5, 6, t0, t0 + 2 * day - 1))
        assertTrue(UpdatePolicy.mustUpdate(5, 6, t0, t0 + 2 * day))
        assertFalse(UpdatePolicy.mustUpdate(6, 6, t0, t0 + 9 * day))   // already up to date
    }

    @Test fun tagToCode() {
        assertEquals(17, UpdatePolicy.codeOfTag("build-17"))
        assertNull(UpdatePolicy.codeOfTag("latest"))
    }

    @Test fun picksRightApk() {
        val names = listOf("AHVEN-Downloader-build9-armeabi-v7a.apk", "AHVEN-Downloader-build9-arm64-v8a.apk", "AHVEN-Downloader-build9-universal.apk", "notes.txt")
        assertEquals(1, UpdatePolicy.pickAsset(names, listOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(0, UpdatePolicy.pickAsset(names, listOf("armeabi-v7a")))
        assertEquals(2, UpdatePolicy.pickAsset(names, listOf("x86_64")))
        assertEquals(-1, UpdatePolicy.pickAsset(listOf("a.txt"), listOf("arm64-v8a")))
    }

    @Test fun parsesReleaseJson() {
        val json = """{"tag_name":"build-12","assets":[
            {"name":"AHVEN-Downloader-build12-arm64-v8a.apk","size":1234,"browser_download_url":"https://x/a.apk"},
            {"name":"AHVEN-Downloader-build12-universal.apk","size":99,"browser_download_url":"https://x/u.apk"}]}"""
        val r = UpdatePolicy.parseRelease(json, listOf("arm64-v8a"))!!
        assertEquals(12, r.code)
        assertEquals("https://x/a.apk", r.apkUrl)
        assertEquals(1234L, r.size)
    }

    @Test fun storageCategories() {
        assertEquals(StorageSaver.Category.VIDEOS, StorageSaver.categoryFor("a.MP4"))
        assertEquals(StorageSaver.Category.MUSIC, StorageSaver.categoryFor("a.m4a"))
        assertEquals(StorageSaver.Category.COMPRESSED, StorageSaver.categoryFor("data.zip"))
        assertEquals(StorageSaver.Category.DOCUMENTS, StorageSaver.categoryFor("x.docx"))
        assertEquals(StorageSaver.Category.PROGRAMS, StorageSaver.categoryFor("app.apk"))
        assertEquals(StorageSaver.Category.SUBTITLES, StorageSaver.categoryFor("a.srt"))
        assertEquals(StorageSaver.Category.OTHERS, StorageSaver.categoryFor("noext"))
    }
}
