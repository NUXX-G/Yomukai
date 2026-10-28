package com.nelson.yomukai.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DownloadSecurityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun rejects(url: String) = try {
        DownloadSecurity.requireSecureUrl(url)
        fail("expected $url to be rejected")
    } catch (_: DownloadSecurity.InsecureDownloadException) {
    }

    @Test
    fun onlyHttpsOrOnionHttp() {
        assertEquals("https://a.com/x.jpg", DownloadSecurity.requireSecureUrl(" https://a.com/x.jpg "))
        assertEquals("http://abc.onion/x", DownloadSecurity.requireSecureUrl("http://abc.onion/x"))
        rejects("http://a.com/x.jpg")
        rejects("ftp://a.com/x")
        rejects("file:///data/x")
        rejects("javascript:alert(1)")
        rejects("https:///nohost")
        rejects("not a url")
    }

    @Test
    fun contentTypes() {
        assertTrue(DownloadSecurity.isImageType("image/webp; charset=binary"))
        assertFalse(DownloadSecurity.isImageType("text/html"))
        assertFalse(DownloadSecurity.isImageType("image/svg+xml"))
        assertFalse(DownloadSecurity.isImageType(null))
        assertTrue(DownloadSecurity.isVideoType("video/mp4"))
        assertTrue(DownloadSecurity.isVideoType("application/octet-stream"))
        assertFalse(DownloadSecurity.isVideoType("text/html"))
        assertEquals("png", DownloadSecurity.imageExtension("image/png"))
        assertEquals("jpg", DownloadSecurity.imageExtension("image/unknown"))
    }

    @Test
    fun sizeLimit() {
        DownloadSecurity.checkDeclaredSize(-1, 10)
        DownloadSecurity.checkDeclaredSize(10, 10)
        try {
            DownloadSecurity.checkDeclaredSize(11 * 1024 * 1024L, 10L * 1024 * 1024)
            fail()
        } catch (_: DownloadSecurity.InsecureDownloadException) {
        }
    }

    @Test
    fun fileNamesAreSanitised() {
        assertEquals("passwd", DownloadSecurity.sanitizeFileName("../../etc/passwd").substringAfterLast('_'))
        assertFalse('/' in DownloadSecurity.sanitizeFileName("a/b\\c:d"))
        assertEquals("file", DownloadSecurity.sanitizeFileName(".."))
        assertEquals("file", DownloadSecurity.sanitizeFileName("   "))
        assertTrue(DownloadSecurity.sanitizeFileName("x".repeat(500)).length <= 100)
    }

    @Test
    fun pathTraversalIsDetected() {
        val root = tmp.newFolder("downloads")
        assertTrue(DownloadSecurity.isInside(root, File(root, "a/b/page.jpg")))
        assertTrue(DownloadSecurity.isInside(root, root))
        assertFalse(DownloadSecurity.isInside(root, File(root, "../outside.txt")))
        assertFalse(DownloadSecurity.isInside(root, File(root.parentFile, "downloads-evil/x")))
    }

    @Test
    fun mp4MagicAndCompleteness() {
        val good = byteArrayOf(0, 0, 0, 24) + "ftypisom".toByteArray() + ByteArray(4)
        assertTrue(DownloadSecurity.looksLikeMp4(good))
        assertFalse(DownloadSecurity.looksLikeMp4("<html><body>blocked</body></html>".toByteArray()))
        assertTrue(DownloadSecurity.isComplete(100, 100))
        assertFalse(DownloadSecurity.isComplete(100, 99))
        assertTrue(DownloadSecurity.isComplete(-1, 5))
        assertFalse(DownloadSecurity.isComplete(-1, 0))
    }
}
